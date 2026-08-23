package dev.hearthd.android.kiosk.audio

import android.content.Context
import dev.hearthd.android.kiosk.settings.SnapcastSettings
import dev.hearthd.android.kiosk.snapcast.SnapcastController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** This client's music volume as the Snapcast server knows it. */
data class RemoteVolume(val percent: Int, val muted: Boolean)

/**
 * A thin JSON-RPC client to snapserver's control port (line-delimited JSON-RPC
 * 2.0 over TCP, snapserver's default 1705). It keeps this device's music volume in
 * sync with the server *both ways*:
 *
 *  - push:   a local volume-key press calls [push], which sends `Client.SetVolume`
 *            for our own client id, so Snapweb and the other rooms reflect it.
 *  - accept: `Client.OnVolumeChanged` notifications (and the initial
 *            `Server.GetStatus` reply) update [remoteVolume], so a change made
 *            elsewhere lands here.
 *
 * The client is identified by the same [SnapcastController.hostId] the streaming
 * client reports, so the server treats them as one device.
 *
 * This is strictly best-effort and never in the path that silences the device:
 * [connected] only turns true once we've read our own volume from the server, and
 * [push] drops commands while disconnected. When it's down, [AudioPolicy] falls
 * back to the local STREAM_MUSIC lever, so the keys always work. Matching the
 * streaming client, it holds state and runs nothing until [run]; the socket's life
 * is tied to that call so cancellation (settings change, backgrounding) closes it.
 */
class SnapcastControl(private val context: Context) : MusicVolumeSink {

    // Latest volume the server has for this client, or null when we don't know it.
    private val _remoteVolume = MutableStateFlow<RemoteVolume?>(null)
    val remoteVolume: StateFlow<RemoteVolume?> = _remoteVolume.asStateFlow()

    // True only once we've learned our own volume from the server: the point from
    // which the server is the authority for music volume.
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // Latest pending volume to push, conflated: only the newest matters, and a key
    // press must never block waiting for the socket.
    private val outgoing = Channel<RemoteVolume>(Channel.CONFLATED)

    override fun push(percent: Int, muted: Boolean) {
        // Nothing to push to until the server knows us; the local lever covers it.
        if (!_connected.value) return
        outgoing.trySend(RemoteVolume(percent.coerceIn(0, 100), muted))
    }

    /** Reflect the "off" state without opening a socket. */
    fun markDisabled() {
        _connected.value = false
        _remoteVolume.value = null
    }

    /**
     * Connect to [settings]' control port and keep the volume in sync until this
     * coroutine is cancelled. Reconnects with backoff; suspends for its lifetime.
     */
    suspend fun run(settings: SnapcastSettings) {
        val clientId = SnapcastController.hostId(context)
        var backoffMs = MIN_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            try {
                session(settings, clientId)
                backoffMs = MIN_BACKOFF_MS
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: swallow and retry. Losing sync just drops us to the
                // local lever until the server is reachable again.
            } finally {
                _connected.value = false
            }
            if (!currentCoroutineContext().isActive) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** One connection: open, ask for status, then read notifications until closed. */
    private suspend fun session(settings: SnapcastSettings, clientId: String) =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            // Blocking socket reads aren't interruptible, so closing the socket is
            // what unblocks them: tie its life to this job (mirrors SnapcastController).
            val killer = currentCoroutineContext().job.invokeOnCompletion {
                runCatching { socket.close() }
            }
            try {
                socket.connect(InetSocketAddress(settings.host, settings.controlPort), CONNECT_TIMEOUT_MS)
                val writer = socket.getOutputStream().bufferedWriter()
                val reader = socket.getInputStream().bufferedReader()
                val ids = AtomicInteger(1)

                // Ask for the current picture; the reply carries our volume.
                writer.append(request(ids.getAndIncrement(), "Server.GetStatus", null)).append('\n')
                writer.flush()

                val writeJob = launch {
                    for (cmd in outgoing) {
                        runCatching {
                            writer.append(
                                request(
                                    ids.getAndIncrement(),
                                    "Client.SetVolume",
                                    volumeParams(clientId, cmd.percent, cmd.muted),
                                ),
                            ).append('\n')
                            writer.flush()
                        }
                    }
                }
                try {
                    reader.forEachLine { handleLine(it, clientId) }
                } finally {
                    writeJob.cancel()
                }
            } finally {
                killer.dispose()
                runCatching { socket.close() }
            }
        }

    private fun handleLine(line: String, clientId: String) {
        val msg = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (msg.optString("method")) {
            "Client.OnVolumeChanged" -> {
                val params = msg.optJSONObject("params") ?: return
                if (params.optString("id") == clientId) adopt(params.optJSONObject("volume"))
            }
            // No method → a response. The only request we send is Server.GetStatus.
            "" -> {
                val server = msg.optJSONObject("result")?.optJSONObject("server") ?: return
                adopt(findClientVolume(server, clientId))
            }
        }
    }

    /** Take the server's volume for this client as the truth. */
    private fun adopt(volume: JSONObject?) {
        volume ?: return
        val percent = volume.optInt("percent", _remoteVolume.value?.percent ?: 100).coerceIn(0, 100)
        val muted = volume.optBoolean("muted", false)
        _remoteVolume.value = RemoteVolume(percent, muted)
        _connected.value = true
    }

    private fun findClientVolume(server: JSONObject, clientId: String): JSONObject? {
        val groups = server.optJSONArray("groups") ?: return null
        for (i in 0 until groups.length()) {
            val clients = groups.optJSONObject(i)?.optJSONArray("clients") ?: continue
            for (j in 0 until clients.length()) {
                val client = clients.optJSONObject(j) ?: continue
                if (client.optString("id") == clientId) {
                    return client.optJSONObject("config")?.optJSONObject("volume")
                }
            }
        }
        return null
    }

    private fun request(id: Int, method: String, params: JSONObject?): String {
        val obj = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) obj.put("params", params)
        return obj.toString()
    }

    private fun volumeParams(clientId: String, percent: Int, muted: Boolean): JSONObject =
        JSONObject()
            .put("id", clientId)
            .put("volume", JSONObject().put("muted", muted).put("percent", percent))

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
