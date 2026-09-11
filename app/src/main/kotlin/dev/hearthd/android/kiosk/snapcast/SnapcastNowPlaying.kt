package dev.hearthd.android.kiosk.snapcast

import android.content.Context
import dev.hearthd.android.kiosk.nowplaying.NowPlaying
import dev.hearthd.android.kiosk.nowplaying.NowPlayingCommand
import dev.hearthd.android.kiosk.nowplaying.NowPlayingSource
import dev.hearthd.android.kiosk.nowplaying.Playback
import dev.hearthd.android.kiosk.settings.SnapcastSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

enum class NowPlayingStatus { IDLE, CONNECTING, LIVE, ERROR }

/**
 * Live state of the metadata link, enough for the Audio settings pane to show
 * that the server is being read — [streamId] is the stream this device's group
 * is listening to, which is what everything below is about.
 */
data class SnapcastNowPlayingUiState(
    val status: NowPlayingStatus = NowPlayingStatus.IDLE,
    val server: String = "",
    val message: String? = null,
    val streamId: String? = null,
    val nowPlaying: NowPlaying? = null,
)

/**
 * What's playing on the Snapcast stream this device is listening to, read over
 * snapserver's JSON-RPC control port — the same newline-delimited JSON link
 * [SnapcastVolumeSync] uses, on a separate connection.
 *
 * The device's own group decides which stream that is: `Server.GetStatus` gives
 * the whole picture, our client id finds our group, and the group's `stream_id`
 * names the stream whose properties carry the metadata. From there
 * `Stream.OnProperties` and `Stream.OnUpdate` keep it current, and anything that
 * might have moved us to another group is answered by asking for the status
 * again rather than tracking it.
 *
 * Transport commands go back the other way as `Stream.Control` on the same
 * connection. What the stream will accept is the stream's own account of itself
 * — the `can…` properties — so a pipe with nothing behind it offers no controls
 * while a Spotify stream offers the lot, and the server enforces the same guards
 * again at its end.
 *
 * Unlike the other Snapcast pieces this holds no state and has no `run`: it is a
 * cold flow, live only while collected, so the socket exists only while
 * something is actually showing what's playing (see `NowPlayingService`).
 * [session] is the transport and [handleLine] the protocol, split so that a
 * future single shared connection can drive both this and the volume sync by
 * calling their handlers with one `call`.
 */
class SnapcastNowPlaying(
    private val context: Context,
    scope: CoroutineScope,
    settings: Flow<SnapcastSettings>,
) : NowPlayingSource {

    /**
     * Shared rather than cold-per-collector so the settings pane and the
     * dashboard widgets watching this hold one link between them, not one each.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SnapcastNowPlayingUiState> = settings
        .distinctUntilChanged()
        .flatMapLatest { s ->
            // Opt-in, like every other Snapcast feature: unconfigured, this
            // never opens a socket however many widgets are asking.
            if (!s.enabled || !s.configured) flowOf(SnapcastNowPlayingUiState()) else link(s)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(IDLE_TIMEOUT_MS), SnapcastNowPlayingUiState())

    override val nowPlaying: Flow<NowPlaying?> =
        state.map { it.nowPlaying }.distinctUntilChanged()

    // The live session's way to command the stream it's following, replaced
    // whenever that stream changes and dropped when the connection goes. Written
    // from the session, read from the UI thread on a button press.
    @Volatile
    private var control: ((String) -> Unit)? = null

    /**
     * Ask the stream to act. Dropped when nothing is connected or no stream is
     * ours — the same fire-and-forget footing as a light command sent to an
     * unconfigured hearthd.
     */
    override fun send(command: NowPlayingCommand) {
        control?.invoke(
            when (command) {
                NowPlayingCommand.PLAY_PAUSE -> "playPause"
                NowPlayingCommand.NEXT -> "next"
                NowPlayingCommand.PREVIOUS -> "previous"
            },
        )
    }

    /** Keep a connection to [settings]' control port up, reconnecting with backoff. */
    private fun link(settings: SnapcastSettings): Flow<SnapcastNowPlayingUiState> = channelFlow {
        val server = "${settings.host}:${settings.controlPort}"
        val clientId = SnapcastController.clientId(context)
        var backoffMs = MIN_BACKOFF_MS
        while (true) {
            send(SnapcastNowPlayingUiState(NowPlayingStatus.CONNECTING, server))
            val started = System.currentTimeMillis()
            val failure = try {
                session(settings, clientId, server) { send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            send(
                SnapcastNowPlayingUiState(
                    NowPlayingStatus.ERROR, server, message = failure.message ?: failure.toString(),
                ),
            )
            // A link that held for a while earned a quick retry; only repeated
            // immediate failures back off.
            if (System.currentTimeMillis() - started > MAX_BACKOFF_MS) backoffMs = MIN_BACKOFF_MS
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * One connection: ask for status, then fold every notification into the
     * running [Link] and publish it. Always ends by throwing, so the caller
     * reconnects.
     */
    private suspend fun session(
        settings: SnapcastSettings,
        clientId: String,
        server: String,
        emit: suspend (SnapcastNowPlayingUiState) -> Unit,
    ): Nothing = coroutineScope {
        val socket = Socket()
        try {
            withContext(Dispatchers.IO) {
                socket.connect(InetSocketAddress(settings.host, settings.controlPort), CONNECT_TIMEOUT_MS)
            }
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()
            val outgoing = Channel<JSONObject>(Channel.UNLIMITED)
            val ids = AtomicInteger()
            val call = { method: String, params: JSONObject? ->
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

            call("Server.GetStatus", null)
            var link = Link()
            // The first line proves the link works, whether or not it changed
            // anything — without it a server with nothing playing would read as
            // permanently connecting.
            var live = false
            while (true) {
                val line = withContext(Dispatchers.IO) {
                    try {
                        reader.readLine()
                    } catch (_: IOException) {
                        null
                    }
                } ?: break
                val updated = handleLine(line, clientId, link, call)
                if (updated == link && live) continue
                link = updated
                live = true
                control = link.streamId?.let { id ->
                    { command -> call("Stream.Control", controlParams(id, command)) }
                }
                emit(
                    SnapcastNowPlayingUiState(
                        status = NowPlayingStatus.LIVE,
                        server = server,
                        streamId = link.streamId,
                        nowPlaying = link.nowPlaying,
                    ),
                )
            }
            throw IOException("connection closed")
        } finally {
            control = null
            // Blocking socket I/O isn't interruptible; closing the socket is what
            // unblocks the reader, and this runs as soon as the scope is cancelled.
            runCatching { socket.close() }
        }
    }

    /**
     * What one connection has learned so far: which stream is ours, whether it's
     * carrying audio, and the last properties it published.
     */
    private data class Link(
        val streamId: String? = null,
        val playing: Boolean = false,
        val metadata: NowPlaying? = null,
        val controls: Controls = Controls(),
    ) {
        /**
         * Snapserver holds a stream's last properties after it goes quiet, so a
         * track counts as playing only while the stream is carrying audio, and as
         * paused only on a player that says so. Otherwise the screensaver would
         * show this morning's song all evening — and a plugin that died mid-track
         * would leave it there claiming to still be playing.
         */
        val nowPlaying: NowPlaying?
            get() {
                val track = metadata ?: return null
                // A stream with no player behind it reports no status of its own,
                // so audio flowing is all there is to go on.
                val playback = controls.playback
                    ?: if (playing) Playback.PLAYING else Playback.STOPPED
                if (!playing && playback != Playback.PAUSED) return null
                return track.copy(
                    playback = playback,
                    canPlay = controls.canPlay,
                    canPause = controls.canPause,
                    canGoNext = controls.canGoNext,
                    canGoPrevious = controls.canGoPrevious,
                    canControl = controls.canControl,
                )
            }
    }

    /** Fold one notification or response into [link]. Pure: the transport is [session]'s. */
    private fun handleLine(
        line: String,
        clientId: String,
        link: Link,
        call: (String, JSONObject?) -> Unit,
    ): Link {
        val msg = runCatching { JSONObject(line) }.getOrNull() ?: return link
        val params = msg.optJSONObject("params")
        return when (msg.optString("method")) {
            "Stream.OnProperties" -> {
                if (params == null || params.optString("id") != link.streamId) return link
                // Snapserver sends `{id, properties}`; the published API doc
                // shows the properties flattened alongside the id instead, so
                // accept that shape too.
                val properties = params.optJSONObject("properties") ?: params
                // A partial update carries no metadata, and the server enriches
                // it with what it already holds, so absent means unchanged. The
                // transport properties are always complete, so they just replace.
                link.copy(
                    metadata = nowPlayingOf(properties) ?: link.metadata,
                    controls = controlsOf(properties),
                )
            }
            "Stream.OnUpdate" -> {
                val stream = params?.optJSONObject("stream") ?: return link
                if (stream.optString("id") != link.streamId) return link
                val properties = stream.optJSONObject("properties")
                link.copy(
                    playing = stream.optString("status") == STATUS_PLAYING,
                    metadata = properties?.let { nowPlayingOf(it) } ?: link.metadata,
                    controls = properties?.let { controlsOf(it) } ?: link.controls,
                )
            }
            // The whole server picture, sent when clients join or move groups.
            "Server.OnUpdate" -> params?.optJSONObject("server")
                ?.let { applyStatus(it, clientId) } ?: link
            // Our group, or which stream it plays, may have changed; ask rather
            // than track.
            "Group.OnStreamChanged", "Client.OnConnect" -> {
                call("Server.GetStatus", null)
                link
            }
            // No method: a response to one of our requests.
            "" -> msg.optJSONObject("result")?.optJSONObject("server")
                ?.let { applyStatus(it, clientId) } ?: link
            else -> link
        }
    }

    /**
     * Read a `Server.GetStatus`-shaped object: find this client's group, take the
     * stream it's listening to, and read that stream's state. A client the server
     * doesn't list yet resets to nothing playing — it announces the client when
     * it connects, and we'll be told.
     */
    private fun applyStatus(server: JSONObject, clientId: String): Link {
        val streamId = server.optJSONArray("groups").objects()
            .firstOrNull { group ->
                group.optJSONArray("clients").objects().any { it.optString("id") == clientId }
            }
            ?.optString("stream_id")?.ifBlank { null }
            ?: return Link()

        val stream = server.optJSONArray("streams").objects()
            .firstOrNull { it.optString("id") == streamId }
            ?: return Link(streamId = streamId)

        val properties = stream.optJSONObject("properties")
        return Link(
            streamId = streamId,
            playing = stream.optString("status") == STATUS_PLAYING,
            metadata = properties?.let { nowPlayingOf(it) },
            controls = properties?.let { controlsOf(it) } ?: Controls(),
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L

        // How long the link is held open after the last widget stops watching,
        // so a template swap doesn't cost a reconnect.
        const val IDLE_TIMEOUT_MS = 5_000L

        // A stream's own state, which is about audio flowing rather than what a
        // player reports: "idle", "playing" or "disabled".
        const val STATUS_PLAYING = "playing"
    }
}

/**
 * The transport half of a stream's properties. Separate from the metadata
 * because the two update independently: a partial `Stream.OnProperties` replaces
 * these outright while the server carries the metadata forward.
 */
private data class Controls(
    val playback: Playback? = null,
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canGoNext: Boolean = false,
    val canGoPrevious: Boolean = false,
    val canControl: Boolean = false,
)

/**
 * What a stream's `properties` say its player will accept. A stream with no
 * player behind it — a bare pipe — reports none of this, which is exactly how a
 * consumer learns not to offer controls. `playbackStatus` is absent or "unknown"
 * in the same case, and stays null so the caller can fall back to whether audio
 * is flowing.
 */
private fun controlsOf(properties: JSONObject) = Controls(
    playback = when (properties.optString("playbackStatus")) {
        "playing" -> Playback.PLAYING
        "paused" -> Playback.PAUSED
        "stopped" -> Playback.STOPPED
        else -> null
    },
    canPlay = properties.optBoolean("canPlay"),
    canPause = properties.optBoolean("canPause"),
    canGoNext = properties.optBoolean("canGoNext"),
    canGoPrevious = properties.optBoolean("canGoPrevious"),
    canControl = properties.optBoolean("canControl"),
)

/** The `Stream.Control` params for one command against [streamId]. */
private fun controlParams(streamId: String, command: String): JSONObject = JSONObject()
    .put("id", streamId)
    .put("command", command)
    .put("params", JSONObject())

/**
 * The track out of a stream's `properties`, or null when there isn't one. The
 * metadata schema is MPRIS-shaped and almost entirely optional, so everything
 * here is best-effort; nothing worth showing means nothing playing.
 */
private fun nowPlayingOf(properties: JSONObject): NowPlaying? {
    val metadata = properties.optJSONObject("metadata") ?: return null
    val playing = NowPlaying(
        title = metadata.optString("title").ifBlank { null },
        // Spec says an array of artists; tolerate a bare string.
        artist = metadata.optJSONArray("artist")?.strings()?.joinToString(", ")
            ?: metadata.optString("artist").ifBlank { null },
        album = metadata.optString("album").ifBlank { null },
        artUri = artUri(metadata),
    )
    return playing.takeIf { it != NowPlaying() }
}

/**
 * Cover art as something Coil can load, preferring the raw bytes. When a stream
 * plugin sends `artData`, snapserver caches the image and synthesises an `artUrl`
 * pointing back at its own HTTP server under the server's system hostname — a
 * name this device often can't resolve — while still sending us the bytes. A
 * `data:` URI needs neither that name nor a second fetch, and Coil reads it
 * directly. An `artUrl` without bytes came from the plugin and is meant to be
 * fetched, so it's used as-is.
 */
private fun artUri(metadata: JSONObject): String? {
    val data = metadata.optJSONObject("artData")
    val bytes = data?.optString("data")?.ifBlank { null }
    if (bytes != null) {
        val extension = data.optString("extension").ifBlank { "jpeg" }
        return "data:image/$extension;base64,$bytes"
    }
    return metadata.optString("artUrl").ifBlank { null }
}

/** The objects in a possibly-absent array, skipping anything that isn't one. */
private fun JSONArray?.objects(): List<JSONObject> =
    (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }

/** The non-blank strings in an array. */
private fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { optString(it).ifBlank { null } }
