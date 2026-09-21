package dev.hearthd.android.kiosk.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * The two volume classes the device juggles, mirroring Android's own stream
 * model. The hardware keys drive whichever is active; each keeps its own level.
 */
enum class AudioClass { MUSIC, ASSISTANT }

/**
 * The device's single audio authority. Android mixes every output at once, so
 * without one place deciding who is loud the Snapcast music and the assistant's
 * speech just pile on top of each other, and the volume keys land wherever the
 * OS last pointed them.
 *
 * Two classes, deliberately on *different* device streams so ducking one leaves
 * the other alone:
 *  - MUSIC     — the Snapcast subprocess (Oboe, tagged USAGE_MEDIA → STREAM_MUSIC).
 *  - ASSISTANT — the voice pipeline's TTS, a [MediaPlayer] we own in-process. It
 *                plays as accessibility speech (→ STREAM_ACCESSIBILITY), which we
 *                pin wide open so the [MediaPlayer]'s own gain is the only lever on
 *                it — and, crucially, so ducking STREAM_MUSIC never touches it.
 *
 * Music is *ducked* — quietened, not paused — while a voice turn is on screen, so
 * a command can be heard over whatever's playing and the reply lands clearly.
 *
 * This first cut is entirely local: music volume is the device's STREAM_MUSIC
 * level, which is always adjustable with no network in the loop — the property we
 * lean on so the keys can always silence the device. A later change puts the
 * Snapcast server in charge of the music level, keeping this local lever
 * underneath as the guaranteed fallback.
 */
class AudioPolicy(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxStream = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    // One key press moves music by about one native stream step, so the keys feel
    // like the platform's own and never land on a value the stream can't represent.
    private val musicStep = (100f / maxStream).roundToInt().coerceAtLeast(MIN_STEP)

    // Intended music level, 0..100, independent of the transient duck. Seeded from
    // the device's current media volume so we don't jump the moment the app starts.
    private val _music = MutableStateFlow(streamToPercent(audio.getStreamVolume(AudioManager.STREAM_MUSIC)))
    val music: StateFlow<Int> = _music.asStateFlow()

    private val _assistant = MutableStateFlow(DEFAULT_ASSISTANT)
    val assistant: StateFlow<Int> = _assistant.asStateFlow()

    // True while a voice turn is on screen: music plays on, quietened.
    @Volatile
    private var ducked = false

    // The TTS player currently speaking, held so a volume key pressed mid-reply is
    // heard on the current utterance, not just the next one.
    @Volatile
    private var assistantPlayer: MediaPlayer? = null

    init {
        // Pin the accessibility stream (where the assistant plays) wide open, so the
        // assistant's loudness is governed solely by the per-player gain we set and
        // never by a stray device-level accessibility volume. Best-effort: some
        // devices restrict this stream, and a failure just means the gain rides on
        // whatever level the device has — never a crash.
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY)
            audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, max, 0)
        }
    }

    /** Volume-up on the active [cls]. */
    fun volumeUp(cls: AudioClass) = when (cls) {
        AudioClass.MUSIC -> nudgeMusic(musicStep)
        AudioClass.ASSISTANT -> nudgeAssistant(ASSISTANT_STEP)
    }

    /** Volume-down on the active [cls]. */
    fun volumeDown(cls: AudioClass) = when (cls) {
        AudioClass.MUSIC -> nudgeMusic(-musicStep)
        AudioClass.ASSISTANT -> nudgeAssistant(-ASSISTANT_STEP)
    }

    private fun nudgeMusic(delta: Int) {
        _music.update { (it + delta).coerceIn(0, 100) }
        applyMusic()
    }

    private fun nudgeAssistant(delta: Int) {
        _assistant.update { (it + delta).coerceIn(0, 100) }
        applyAssistant()
    }

    /** Duck (or restore) music around a voice turn. Idempotent. */
    fun setDucked(value: Boolean) {
        if (ducked == value) return
        ducked = value
        applyMusic()
    }

    /** Gain (0..1) to start a TTS player at; also re-applied live while it plays. */
    fun assistantGain(): Float = _assistant.value / 100f

    /**
     * Register the TTS player that's currently speaking (null when it stops), so a
     * volume key mid-reply adjusts the live utterance. The player's own attributes
     * keep it off the music stream, so ducking never touches it.
     */
    fun bindAssistantPlayer(player: MediaPlayer?) {
        assistantPlayer = player
        if (player != null) applyAssistant()
    }

    /** Push the intended music level to the device's media stream. */
    private fun applyMusic() {
        val base = _music.value
        val effective = if (ducked) (base * DUCK_FACTOR).roundToInt() else base
        // setStreamVolume can throw under Do Not Disturb / zen policies; a failed
        // volume nudge must never crash the kiosk.
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, percentToStream(effective), 0) }
    }

    private fun applyAssistant() {
        val gain = assistantGain()
        assistantPlayer?.let { runCatching { it.setVolume(gain, gain) } }
    }

    private fun streamToPercent(stream: Int): Int =
        (stream * 100f / maxStream).roundToInt().coerceIn(0, 100)

    private fun percentToStream(percent: Int): Int =
        (percent / 100f * maxStream).roundToInt().coerceIn(0, maxStream)

    private companion object {
        const val DEFAULT_ASSISTANT = 80
        const val ASSISTANT_STEP = 10
        const val MIN_STEP = 5
        // How far music drops while the assistant has the floor.
        const val DUCK_FACTOR = 0.2f
    }
}
