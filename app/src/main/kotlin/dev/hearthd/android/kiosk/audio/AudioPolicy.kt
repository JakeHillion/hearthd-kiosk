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
 * A best-effort channel for telling the Snapcast server this device's music
 * volume, so a local key press shows up in Snapweb and the other rooms' notion of
 * this client. Deliberately fire-and-forget: it must never block a key press, and
 * when it can't get through the local STREAM_MUSIC lever still changes the output.
 */
interface MusicVolumeSink {
    fun push(percent: Int, muted: Boolean)
}

/**
 * The device's single audio authority. Android mixes every output at once, so
 * without one place deciding who is loud the Snapcast music and the assistant's
 * speech just pile on top of each other, and the volume keys land wherever the
 * OS last pointed them.
 *
 * Two classes:
 *  - MUSIC     — the Snapcast subprocess (Oboe, tagged USAGE_MEDIA → STREAM_MUSIC).
 *  - ASSISTANT — the voice pipeline's TTS, a [MediaPlayer] we own in-process and
 *                set the gain on directly (USAGE_ASSISTANT, a separate stream).
 *
 * Music is *ducked* — quietened, not paused — while a voice turn is on screen, so
 * a command can be heard over whatever's playing and the reply lands clearly.
 *
 * Music volume is server-authoritative: when the Snapcast control channel is
 * connected the server holds the level (and Snapweb and the other rooms see it),
 * scaling the audio in software while the local stream stays fully open. But the
 * local STREAM_MUSIC stream is always the lever underneath, with no network in the
 * loop — a volume-down pulls it down instantly and mute pins it to zero — so the
 * device can always be silenced whatever the server does. When the control channel
 * is down that local lever simply is the music level.
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

    // A local mute, toggled by the mute key — the always-works "shut up" lever.
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    // True while a voice turn is on screen: music plays on, quietened.
    @Volatile
    private var ducked = false

    // The TTS player currently speaking, held so a volume key pressed mid-reply is
    // heard on the current utterance, not just the next one.
    @Volatile
    private var assistantPlayer: MediaPlayer? = null

    // Best-effort link to the Snapcast server for two-way volume sync, and whether
    // it currently holds our volume.
    @Volatile
    private var musicSink: MusicVolumeSink? = null

    @Volatile
    private var remoteConnected = false

    // The local media-stream gain (0..100), the only thing that actually reaches
    // the speaker after mute and duck. It is *always* the lever the keys can pull
    // down, with no network in the loop — the guarantee that we can silence the
    // device whatever the server does. In the steady connected state it sits at
    // 100 and the server's software volume carries the level; a volume-down pulls
    // it down at once for instant local effect, and it relaxes back to 100 once the
    // server confirms the new level (so we never double-attenuate for long). While
    // disconnected it simply is the music level.
    @Volatile
    private var localGain = _music.value

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

    /** Toggle the local music mute — the guaranteed local silence. */
    fun toggleMute() {
        _muted.update { !it }
        pushMusic()
        applyMusic()
    }

    private fun nudgeMusic(delta: Int) {
        // Turning it up is also an "un-silence": nobody expects volume-up to do
        // nothing because a mute they forgot about is still latched.
        if (delta > 0) _muted.value = false
        val level = (_music.value + delta).coerceIn(0, 100)
        _music.value = level
        pushMusic()
        localGain = when {
            // Not connected: the local gain simply is the level.
            !remoteConnected -> level
            // Connected volume-down: pull the local gain down now so the output
            // drops this instant, even if the server never hears us. It relaxes
            // back to 100 when the server confirms (see onRemoteVolume).
            delta < 0 -> minOf(localGain, level)
            // Connected volume-up: let the server carry the rise; opening the local
            // gain can't overshoot because the server still holds the old, lower level.
            else -> 100
        }
        applyMusic()
    }

    /** Attach the server sync channel (Snapcast control), or detach with null. */
    fun attachMusicSink(sink: MusicVolumeSink?) {
        musicSink = sink
    }

    /**
     * The server gained or lost our volume. On connect we don't push our own level
     * — the server's value wins (adopted via [onRemoteVolume]), so a change made
     * elsewhere isn't clobbered by a stale local one. On disconnect the local gain
     * takes over at the current level, so the output doesn't jump and the keys keep
     * working directly on the stream.
     */
    fun onRemoteConnected(connected: Boolean) {
        if (remoteConnected == connected) return
        remoteConnected = connected
        if (!connected) localGain = _music.value
        applyMusic()
    }

    /**
     * Adopt a volume the server reports — its status reply, a change made in
     * Snapweb, or the echo of our own push. The server now carries this level in
     * software, so the local gain relaxes back to fully open; mute stays local.
     */
    fun onRemoteVolume(percent: Int, muted: Boolean) {
        _music.value = percent.coerceIn(0, 100)
        _muted.value = muted
        localGain = 100
        applyMusic()
    }

    /** Best-effort: tell the server this device's music volume. */
    private fun pushMusic() {
        musicSink?.push(_music.value, _muted.value)
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

    /**
     * Push the effective gain to the device's media stream. Mute always wins and
     * pins it to zero — the guaranteed local silence, independent of the server —
     * then the duck, then the local gain (which carries the level when disconnected
     * and sits at 100 when the server holds it).
     */
    private fun applyMusic() {
        val base = if (_muted.value) 0 else localGain
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
