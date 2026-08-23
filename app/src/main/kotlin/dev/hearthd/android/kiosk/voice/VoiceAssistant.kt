package dev.hearthd.android.kiosk.voice

import kotlinx.coroutines.flow.Flow

/**
 * A single voice turn's worth of feedback. The UI renders whatever arrives, so
 * a backend that streams partial transcripts (emitting [Transcript] with
 * `final = false` repeatedly) lights up the popup word-by-word for free; Home
 * Assistant's STT only sends one final transcript today.
 */
sealed interface VoiceEvent {
    /** Capturing and streaming speech; show the live waveform. */
    data object Listening : VoiceEvent

    /** The recognised speech so far. [final] = true once STT has settled. */
    data class Transcript(val text: String, val final: Boolean) : VoiceEvent

    /** The request is being processed (intent recognition / conversation agent). */
    data object Thinking : VoiceEvent

    /** The assistant's textual reply. */
    data class Response(val text: String) : VoiceEvent

    /** The reply is being spoken aloud. */
    data object Speaking : VoiceEvent

    /** The turn finished cleanly. */
    data object Done : VoiceEvent

    /** The turn failed; [message] is safe to surface. */
    data class Failed(val message: String) : VoiceEvent
}

/**
 * One voice turn: consume [audio] — 16 kHz mono 16-bit PCM frames, starting the
 * moment the wake word fires — and emit [VoiceEvent]s until the turn completes
 * or fails. This is the seam: today the only implementation talks to Home
 * Assistant (Alpha), but nothing above this interface knows that, so a
 * hearthd-native backend can replace it without touching the mic or the UI.
 */
interface VoiceAssistant {
    fun runTurn(audio: Flow<ShortArray>): Flow<VoiceEvent>
}
