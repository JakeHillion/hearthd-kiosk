package dev.hearthd.android.kiosk.snapcast

import android.content.Context
import dev.hearthd.android.kiosk.settings.SnapcastSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

enum class VolumeSyncStatus { DISABLED, CONNECTING, SYNCED, ERROR }

/**
 * Live state of the volume link, enough for the Audio settings pane to show
 * that both ends agree — [serverPercent] is the volume the server holds for
 * this client, [deviceIndex] of [deviceMax] the stream level it maps to — and
 * [lastEvent] the most recent transfer in either direction, so a change made in
 * a Snapcast UI or with the volume keys is visibly accounted for on the device.
 */
data class VolumeSyncUiState(
    val status: VolumeSyncStatus = VolumeSyncStatus.DISABLED,
    val server: String = "",
    val message: String? = null,
    val serverPercent: Int? = null,
    val serverMuted: Boolean = false,
    val deviceIndex: Int = 0,
    val deviceMax: Int = 0,
    val lastEvent: String? = null,
)

/**
 * Keeps this device's music volume and the server's volume for this client in
 * step, both ways, over snapserver's JSON-RPC control port (newline-delimited
 * JSON over plain TCP; the server's default is 1705).
 *
 * The server's percent maps onto the [MusicVolume] level, so the server sets the
 * device's real volume — what snapclient's hardware mixer does on Linux. The
 * Oboe player has no such mixer, so the subprocess runs with `--mixer none` and
 * this is the mixer. Server → device: the initial `Server.GetStatus` and
 * `Client.OnVolumeChanged` notifications. Device → server: the stream's
 * change broadcasts, which cover the hardware keys and anything else that moves
 * it, sent as `Client.SetVolume`.
 *
 * On connect the server's value wins. A push never comes back (the server
 * doesn't notify the session that issued a change), but a server value written
 * to the stream does re-emerge as change broadcasts — one per step of the
 * write, so they're left to settle and then compared with where the write
 * ended — and dropped rather than pushed back rounded to the stream's coarser
 * steps.
 *
 * Holds state and runs nothing until [run]; the socket's life is bound to that
 * call, like the subprocess in [SnapcastController].
 */
class SnapcastVolumeSync(private val context: Context) {

    private val music = MusicVolume(context)

    private val _state = MutableStateFlow(VolumeSyncUiState())
    val state: StateFlow<VolumeSyncUiState> = _state.asStateFlow()

    // Where the stream ended up after the last server value was written to it,
    // so the write's own change broadcasts aren't taken for a device change.
    @Volatile
    private var applied: MusicVolume.Level? = null

    // The server mutes a client when either it or its group is muted, and only
    // the client half arrives in Client.OnVolumeChanged; the group half is kept
    // from the last status.
    @Volatile
    private var groupMuted = false

    /** Reflect the "off" state without opening a socket. */
    fun markDisabled() {
        _state.value = VolumeSyncUiState()
    }

    /**
     * Connect to [settings]' control port and keep the volume in sync until this
     * coroutine is cancelled, reconnecting with backoff. Suspends for its lifetime.
     */
    suspend fun run(settings: SnapcastSettings) {
        val server = "${settings.host}:${settings.controlPort}"
        val clientId = SnapcastController.clientId(context)
        var backoffMs = MIN_BACKOFF_MS
        while (true) {
            _state.value = VolumeSyncUiState(
                VolumeSyncStatus.CONNECTING, server,
                deviceIndex = music.current().index, deviceMax = music.max,
            )
            val started = System.currentTimeMillis()
            val failure = try {
                session(settings, clientId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            _state.update {
                it.copy(status = VolumeSyncStatus.ERROR, message = failure.message ?: failure.toString())
            }
            // A link that held for a while earned a quick retry; only repeated
            // immediate failures back off.
            if (System.currentTimeMillis() - started > MAX_BACKOFF_MS) backoffMs = MIN_BACKOFF_MS
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * One connection: ask for status, then exchange notifications and pushes
     * until the socket drops. Always ends by throwing, so the caller retries.
     */
    @OptIn(FlowPreview::class)
    private suspend fun session(settings: SnapcastSettings, clientId: String): Nothing = coroutineScope {
        val socket = Socket()
        try {
            withContext(Dispatchers.IO) {
                socket.connect(InetSocketAddress(settings.host, settings.controlPort), CONNECT_TIMEOUT_MS)
            }
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()
            val outgoing = Channel<JSONObject>(Channel.UNLIMITED)
            val ids = AtomicInteger()
            val send = { method: String, params: JSONObject? ->
                val request = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", ids.incrementAndGet())
                    .put("method", method)
                if (params != null) request.put("params", params)
                outgoing.trySend(request)
                Unit
            }

            launch(Dispatchers.IO) {
                try {
                    for (request in outgoing) {
                        writer.write(request.toString())
                        writer.write("\n")
                        writer.flush()
                    }
                } catch (_: IOException) {
                    // A dead socket: closing it ends the reader, and so the session.
                    runCatching { socket.close() }
                }
            }

            // Device → server: the stream moved, and not by us.
            launch {
                music.changes().debounce(SETTLE_MS).collect { level ->
                    if (level == applied) return@collect
                    val percent = music.toPercent(level.index)
                    send(
                        "Client.SetVolume",
                        JSONObject().put("id", clientId).put(
                            "volume", JSONObject().put("percent", percent).put("muted", level.muted),
                        ),
                    )
                    _state.update {
                        it.copy(
                            serverPercent = percent, serverMuted = level.muted, deviceIndex = level.index,
                            lastEvent = "Sent device level ${level.index}/${music.max}" +
                                "${if (level.muted) " (muted)" else ""} to the server as $percent%",
                        )
                    }
                }
            }

            send("Server.GetStatus", null)
            val receiving = launch(Dispatchers.IO) {
                while (true) {
                    val line = try {
                        reader.readLine() ?: break
                    } catch (_: IOException) {
                        break
                    }
                    handleLine(line, clientId, send)
                }
            }
            receiving.join()
            throw IOException("connection closed")
        } finally {
            // Blocking socket I/O isn't interruptible; closing the socket is what
            // unblocks the reader, and this runs as soon as the scope is cancelled.
            runCatching { socket.close() }
        }
    }

    private fun handleLine(line: String, clientId: String, send: (String, JSONObject?) -> Unit) {
        val msg = runCatching { JSONObject(line) }.getOrNull() ?: return
        val params = msg.optJSONObject("params")
        when (msg.optString("method")) {
            "Client.OnVolumeChanged" -> {
                if (params?.optString("id") != clientId) return
                val volume = params.optJSONObject("volume") ?: return
                adopt(volume.optInt("percent", 100), volume.optBoolean("muted", false) || groupMuted)
            }
            // The whole server picture, sent when clients join or move groups.
            "Server.OnUpdate" -> params?.optJSONObject("server")?.let { applyStatus(it, clientId) }
            // Which group is ours may have changed; ask rather than track.
            "Group.OnMute", "Client.OnConnect" -> send("Server.GetStatus", null)
            // No method: a response to one of our requests.
            "" -> {
                msg.optJSONObject("result")?.optJSONObject("server")?.let { applyStatus(it, clientId) }
                msg.optJSONObject("error")?.let { error ->
                    _state.update { it.copy(message = "Server error: ${error.optString("message")}") }
                }
            }
        }
    }

    /** Find this client in a `Server.GetStatus`-shaped object and adopt its volume. */
    private fun applyStatus(server: JSONObject, clientId: String) {
        val groups = server.optJSONArray("groups")
        for (i in 0 until (groups?.length() ?: 0)) {
            val group = groups!!.optJSONObject(i) ?: continue
            val clients = group.optJSONArray("clients") ?: continue
            for (j in 0 until clients.length()) {
                val client = clients.optJSONObject(j) ?: continue
                if (client.optString("id") != clientId) continue
                val volume = client.optJSONObject("config")?.optJSONObject("volume")
                groupMuted = group.optBoolean("muted", false)
                adopt(
                    volume?.optInt("percent", 100) ?: 100,
                    (volume?.optBoolean("muted", false) ?: false) || groupMuted,
                )
                return
            }
        }
        // Not known to the server yet: it announces the client when it connects.
        _state.update {
            it.copy(status = VolumeSyncStatus.CONNECTING, message = "Server doesn't list client $clientId yet")
        }
    }

    /** Server → device: write the server's volume to the stream. */
    private fun adopt(percent: Int, muted: Boolean) {
        val level = music.apply(MusicVolume.Level(music.toIndex(percent), muted))
        applied = level
        _state.update {
            it.copy(
                status = VolumeSyncStatus.SYNCED, message = null,
                serverPercent = percent, serverMuted = muted, deviceIndex = level.index,
                lastEvent = "Applied server volume $percent%${if (muted) " (muted)" else ""} " +
                    "as device level ${level.index}/${music.max}${if (level.muted) " (muted)" else ""}",
            )
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        // How long the stream's change broadcasts are left to settle before the
        // resulting level is judged; one write to the stream can produce several.
        const val SETTLE_MS = 200L
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
