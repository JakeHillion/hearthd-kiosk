package dev.hearthd.android.kiosk.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import ai.onnxruntime.OrtEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class WakeWordStatus { DISABLED, NO_PERMISSION, STARTING, LISTENING, ERROR }

data class WakeWordUiState(
    val status: WakeWordStatus = WakeWordStatus.DISABLED,
    val model: WakeWordModel? = null,
    val lastScore: Float = 0f,
    val lastDetectionEpochMs: Long? = null,
    val message: String? = null,
)

/** A single wake-word detection, surfaced to the kiosk surface as a popup. */
data class WakeWordDetection(
    val model: WakeWordModel,
    val score: Float,
    val epochMs: Long,
)

/**
 * Owns the microphone and the inference pipeline. Mirrors UpdateController: it
 * holds the UI state and does nothing — never touching the mic — until [run] is
 * called. [run] listens until its coroutine is cancelled (a settings change or
 * the app leaving the foreground), so the caller gates it behind the opt-in and
 * the runtime permission.
 */
class WakeWordDetector(private val appContext: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val _state = MutableStateFlow(WakeWordUiState())
    val state: StateFlow<WakeWordUiState> = _state.asStateFlow()

    // A replayless event stream; the kiosk collects it to flash the popup. Extra
    // buffer so a detection is never dropped if the collector is momentarily busy.
    private val _events = MutableSharedFlow<WakeWordDetection>(extraBufferCapacity = 4)
    val events: SharedFlow<WakeWordDetection> = _events.asSharedFlow()

    // Every captured mic frame, published for the voice pipeline to stream after
    // a detection. Hot and lossy (drops oldest under backpressure) so wake-word
    // scoring is never held up by a slow consumer; nobody collects it unless a
    // voice turn is running.
    private val _audioFrames = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val audioFrames: SharedFlow<ShortArray> = _audioFrames.asSharedFlow()

    fun markDisabled() = _state.update { WakeWordUiState(status = WakeWordStatus.DISABLED) }

    fun markNoPermission() = _state.update { WakeWordUiState(status = WakeWordStatus.NO_PERMISSION) }

    /**
     * Capture audio and detect [model] until cancelled. Suspends for the whole
     * listening session. Caller must hold RECORD_AUDIO.
     */
    suspend fun run(model: WakeWordModel, threshold: Float) {
        _state.update { WakeWordUiState(status = WakeWordStatus.STARTING, model = model) }
        try {
            val pipeline = OnnxWakeWordPipeline(
                env = env,
                melspecModel = readAsset(MELSPEC_ASSET),
                embeddingModel = readAsset(EMBEDDING_ASSET),
                wakeWordModel = readAsset(model.asset),
            )
            pipeline.use { listen(it, model, threshold) }
        } catch (e: Exception) {
            _state.update {
                WakeWordUiState(status = WakeWordStatus.ERROR, model = model, message = e.message)
            }
        }
    }

    @SuppressLint("MissingPermission") // Caller gates on the runtime grant; see MainActivity.
    private suspend fun listen(
        pipeline: OnnxWakeWordPipeline,
        model: WakeWordModel,
        threshold: Float,
    ) = withContext(Dispatchers.Default) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuffer, CHUNK * 8),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _state.update {
                WakeWordUiState(
                    status = WakeWordStatus.ERROR,
                    model = model,
                    message = "Microphone unavailable",
                )
            }
            return@withContext
        }

        try {
            record.startRecording()
            _state.update { WakeWordUiState(status = WakeWordStatus.LISTENING, model = model) }

            val chunk = ShortArray(CHUNK)
            var lastFiredMs = 0L
            while (currentCoroutineContext().isActive) {
                if (!readFully(record, chunk)) break // stopped or errored

                // Publish a copy for the voice pipeline (chunk is reused below).
                _audioFrames.tryEmit(chunk.copyOf())

                val score = pipeline.process(chunk)
                val now = System.currentTimeMillis()
                val fired = score >= threshold && now - lastFiredMs >= REFRACTORY_MS
                if (fired) {
                    lastFiredMs = now
                    _events.tryEmit(WakeWordDetection(model, score, now))
                }
                _state.update {
                    it.copy(
                        status = WakeWordStatus.LISTENING,
                        model = model,
                        lastScore = score,
                        lastDetectionEpochMs = if (fired) now else it.lastDetectionEpochMs,
                    )
                }
            }
        } finally {
            // Runs on cancellation too, so the mic is always released.
            runCatching { record.stop() }
            record.release()
        }
    }

    /** Fill [out] with a full frame; false if the record stopped or errored. */
    private fun readFully(record: AudioRecord, out: ShortArray): Boolean {
        var offset = 0
        while (offset < out.size) {
            val n = record.read(out, offset, out.size - offset)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    private fun readAsset(name: String): ByteArray =
        appContext.assets.open(name).use { it.readBytes() }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK = 1280
        // One popup per utterance: ignore further hits for a beat after firing.
        const val REFRACTORY_MS = 2000L
    }
}
