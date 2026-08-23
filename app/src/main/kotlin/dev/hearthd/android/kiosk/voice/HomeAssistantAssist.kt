package dev.hearthd.android.kiosk.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import dev.hearthd.android.kiosk.audio.AudioPolicy
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Drives a Home Assistant Assist pipeline over its WebSocket API. Wake word is
 * already detected on-device, so the run starts at the `stt` stage — audio only
 * leaves the device after the wake word fires (saving the bandwidth of streaming
 * 24/7). HA owns STT → intent → TTS; we stream mic frames up and play the
 * returned speech back.
 *
 * Flow of one turn (see [runTurn]): connect → auth handshake →
 * `assist_pipeline/run` → on `stt-start` stream binary audio frames → HA's VAD
 * ends the utterance (`stt-vad-end`) → `stt-end` transcript → `intent-end`
 * reply → `tts-end` audio URL, which we play. Each HA event maps to a
 * [VoiceEvent]. This whole class is HA-specific and sits behind [VoiceAssistant]
 * so it can be swapped for a hearthd-native backend later.
 */
class HomeAssistantAssist(
    baseUrl: String,
    private val pipelineId: String?,
    private val audioPolicy: AudioPolicy,
) : VoiceAssistant {

    private val base = baseUrl.trimEnd('/')

    // OkHttp performs the WebSocket upgrade over an http(s) URL — it rejects a
    // ws/wss scheme — so the endpoint stays https here.
    private val wsUrl = "$base/api/websocket"

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // long-lived socket
        .build()

    private val auth = HomeAssistantAuth(client, base)

    override fun runTurn(audio: Flow<ShortArray>): Flow<VoiceEvent> = callbackFlow {
        // Capture the producer: the WebSocket callbacks run on OkHttp threads
        // where this scope is not an implicit receiver.
        val producer = this

        val token = try {
            auth.accessToken()
        } catch (e: Exception) {
            producer.trySend(VoiceEvent.Failed("Couldn't authenticate with Home Assistant: ${e.message}"))
            producer.close()
            return@callbackFlow
        }

        var audioJob: Job? = null
        var handlerId = 0
        var ttsStarted = false

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "auth_required" ->
                        webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                    "auth_ok" ->
                        webSocket.send(runCommand())
                    "auth_invalid" -> {
                        producer.trySend(VoiceEvent.Failed("Home Assistant rejected the token"))
                        producer.close()
                    }
                    "result" ->
                        if (!msg.optBoolean("success", true)) {
                            val err = msg.optJSONObject("error")?.optString("message") ?: "pipeline run rejected"
                            producer.trySend(VoiceEvent.Failed(err))
                            producer.close()
                        }
                    "event" -> handleEvent(webSocket, msg.optJSONObject("event") ?: JSONObject())
                }
            }

            private fun handleEvent(webSocket: WebSocket, event: JSONObject) {
                val data = event.optJSONObject("data") ?: JSONObject()
                when (event.optString("type")) {
                    "run-start" ->
                        handlerId = data.optJSONObject("runner_data")?.optInt("stt_binary_handler_id") ?: 0
                    "stt-start" -> {
                        producer.trySend(VoiceEvent.Listening)
                        audioJob = producer.launch(Dispatchers.Default) {
                            streamAudio(webSocket, handlerId, audio)
                        }
                    }
                    "stt-vad-end" -> {
                        // Speech ended: stop streaming and tell HA we're done sending.
                        audioJob?.cancel()
                        webSocket.send(endMarker(handlerId))
                    }
                    "stt-end" ->
                        producer.trySend(VoiceEvent.Transcript(sttText(data), final = true))
                    "intent-start" ->
                        producer.trySend(VoiceEvent.Thinking)
                    "intent-end" ->
                        producer.trySend(VoiceEvent.Response(responseText(data)))
                    "tts-end" -> {
                        ttsStarted = true
                        val url = absolute(data.optJSONObject("tts_output")?.optString("url"))
                        producer.launch {
                            producer.trySend(VoiceEvent.Speaking)
                            if (url != null) playTts(url)
                            producer.trySend(VoiceEvent.Done)
                            producer.close()
                        }
                    }
                    "run-end" ->
                        // If the pipeline produced no speech, nothing else will close us.
                        if (!ttsStarted) {
                            producer.trySend(VoiceEvent.Done)
                            producer.close()
                        }
                    "error" -> {
                        producer.trySend(VoiceEvent.Failed(data.optString("message", "pipeline error")))
                        producer.close()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                producer.trySend(VoiceEvent.Failed(t.message ?: "connection failed"))
                producer.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                producer.close()
            }
        }

        val webSocket = client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        awaitClose {
            audioJob?.cancel()
            webSocket.cancel()
        }
    }

    /** The `assist_pipeline/run` command; id 1 is the only command we issue. */
    private fun runCommand(): String {
        val cmd = JSONObject()
            .put("id", 1)
            .put("type", "assist_pipeline/run")
            .put("start_stage", "stt")
            .put("end_stage", "tts")
            .put("input", JSONObject().put("sample_rate", SAMPLE_RATE))
        if (!pipelineId.isNullOrBlank()) cmd.put("pipeline", pipelineId)
        return cmd.toString()
    }

    private suspend fun streamAudio(ws: WebSocket, handlerId: Int, audio: Flow<ShortArray>) {
        audio.collect { frame -> ws.send(frame.toWsBytes(handlerId)) }
    }

    /** Play the TTS audio and suspend until it finishes (or errors). */
    private suspend fun playTts(url: String) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                // Start at the assistant class's level and let the policy hold a
                // reference so a volume key pressed while it speaks is heard now.
                setOnPreparedListener { mp ->
                    audioPolicy.bindAssistantPlayer(mp)
                    mp.start()
                }
                setOnCompletionListener { audioPolicy.bindAssistantPlayer(null); resumeOnce(cont); release() }
                setOnErrorListener { _, _, _ ->
                    audioPolicy.bindAssistantPlayer(null); resumeOnce(cont); release(); true
                }
                setDataSource(url)
                prepareAsync()
            }
            cont.invokeOnCancellation { audioPolicy.bindAssistantPlayer(null); runCatching { player.release() } }
        }
    }

    private fun resumeOnce(cont: CancellableContinuation<Unit>) {
        if (cont.isActive) cont.resume(Unit)
    }

    private fun absolute(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http") -> url
        else -> base + url
    }

    private fun sttText(data: JSONObject): String =
        data.optJSONObject("stt_output")?.optString("text").orEmpty()

    private fun responseText(data: JSONObject): String =
        data.optJSONObject("intent_output")
            ?.optJSONObject("response")
            ?.optJSONObject("speech")
            ?.optJSONObject("plain")
            ?.optString("speech")
            .orEmpty()

    private fun endMarker(handlerId: Int): ByteString = byteArrayOf(handlerId.toByte()).toByteString()

    private fun ShortArray.toWsBytes(handlerId: Int): ByteString {
        // 1-byte handler id, then little-endian 16-bit PCM.
        val out = ByteArray(1 + size * 2)
        out[0] = handlerId.toByte()
        var j = 1
        for (s in this) {
            out[j++] = (s.toInt() and 0xFF).toByte()
            out[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out.toByteString()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
