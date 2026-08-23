package dev.hearthd.android.kiosk.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Phases the voice popup steps through over one turn. */
enum class VoicePhase { HIDDEN, LISTENING, HEARD, THINKING, RESPONDING, SPEAKING, ERROR }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.HIDDEN,
    val transcript: String = "",
    val response: String = "",
    val error: String? = null,
)

/**
 * Bridges a [VoiceAssistant] turn to the UI: collects the event stream into a
 * [VoiceUiState] the popup renders, and exposes a live [micLevel] for the
 * waveform. Mirrors the other controllers — it holds state and runs nothing
 * until [startTurn]. One turn at a time; a wake word that fires mid-turn (or
 * during the brief post-turn hold) is ignored.
 */
class VoiceController(private val scope: CoroutineScope) {

    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui.asStateFlow()

    // Current mic loudness (0..1), read by the waveform while listening. A
    // "current value" signal, so it's state rather than an event.
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private var turnJob: Job? = null

    /** Run one turn with [assistant], streaming [audio] (16 kHz mono PCM frames). */
    fun startTurn(assistant: VoiceAssistant, audio: Flow<ShortArray>) {
        if (turnJob?.isActive == true) return

        turnJob = scope.launch {
            _ui.value = VoiceUiState(phase = VoicePhase.LISTENING)
            _micLevel.value = 0f

            // Buffer mic frames from *now* — the wake word just fired — into a
            // channel the assistant drains later. The pipeline's connect + auth
            // handshake takes a beat, and without this the words spoken during it
            // would be dropped ("...bedroom light" instead of the whole command).
            // Detection fires at the end of the wake word, so the buffer holds the
            // command from its first word, with no wake-word audio to trip up
            // intent matching. DROP_OLDEST bounds it if setup runs unusually long.
            val frames = Channel<ShortArray>(
                capacity = PREROLL_FRAMES,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val pump = launch(Dispatchers.Default) {
                audio.collect { frame ->
                    _micLevel.value = smooth(_micLevel.value, level(frame))
                    frames.trySend(frame)
                }
            }

            try {
                assistant.runTurn(frames.receiveAsFlow()).collect { event ->
                    _ui.update { reduce(it, event) }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(phase = VoicePhase.ERROR, error = e.message) }
            } finally {
                pump.cancel()
                frames.close()
            }

            // Hold the final frame on screen, then dismiss.
            _micLevel.value = 0f
            delay(if (_ui.value.phase == VoicePhase.ERROR) ERROR_HOLD_MS else DONE_HOLD_MS)
            _ui.value = VoiceUiState(phase = VoicePhase.HIDDEN)
        }
    }

    private fun reduce(state: VoiceUiState, event: VoiceEvent): VoiceUiState = when (event) {
        VoiceEvent.Listening -> state.copy(phase = VoicePhase.LISTENING)
        is VoiceEvent.Transcript -> state.copy(
            phase = if (event.final) VoicePhase.HEARD else VoicePhase.LISTENING,
            transcript = event.text,
        )
        VoiceEvent.Thinking -> state.copy(phase = VoicePhase.THINKING)
        is VoiceEvent.Response -> state.copy(phase = VoicePhase.RESPONDING, response = event.text)
        VoiceEvent.Speaking -> state.copy(phase = VoicePhase.SPEAKING)
        VoiceEvent.Done -> state
        is VoiceEvent.Failed -> state.copy(phase = VoicePhase.ERROR, error = event.message)
    }

    private companion object {
        const val DONE_HOLD_MS = 2500L
        const val ERROR_HOLD_MS = 4000L

        // Pre-roll cap: ~5 s of 80 ms frames, enough to cover pipeline setup
        // before the assistant starts draining.
        const val PREROLL_FRAMES = 64

        /** RMS of a PCM frame, mapped to roughly 0..1 for display. */
        fun level(frame: ShortArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = sqrt(sum / frame.size)
            return (rms.toFloat() / 8000f).coerceIn(0f, 1f)
        }

        /** Light easing so the waveform doesn't jitter frame-to-frame. */
        fun smooth(prev: Float, next: Float): Float = prev * 0.5f + next * 0.5f
    }
}
