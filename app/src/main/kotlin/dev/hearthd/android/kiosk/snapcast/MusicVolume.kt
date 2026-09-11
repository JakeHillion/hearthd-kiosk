package dev.hearthd.android.kiosk.snapcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * The device's music stream (`STREAM_MUSIC`), where snapclient's Oboe output
 * plays: Oboe tags its stream as media/music unless told otherwise, and
 * snapclient doesn't tell it otherwise. The stream has [max] + 1 discrete levels,
 * so a server percent maps onto it with [toIndex] and back with [toPercent].
 */
class MusicVolume(private val context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val max: Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    /**
     * Android couples the two: setting the stream to 0 mutes it and setting any
     * other index unmutes it, so a stream at 0 always reads as muted whether or
     * not anyone asked. [muted] is therefore only meaningful, and only reported,
     * above 0.
     */
    data class Level(val index: Int, val muted: Boolean)

    fun current(): Level {
        val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return Level(index, index > 0 && audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    /**
     * Set the stream to [level] and return where it actually ended up, which is
     * what a caller should expect to observe. Best-effort: the platform can
     * refuse a volume change (a do-not-disturb policy, say), and that must
     * never take the kiosk down.
     */
    fun apply(level: Level): Level {
        runCatching {
            val stream = AudioManager.STREAM_MUSIC
            val index = level.index.coerceIn(0, max)
            when {
                index == 0 -> audio.setStreamVolume(stream, 0, 0)
                // Setting an index unmutes, so an already-muted stream keeps its
                // old index (silent either way) and takes the new one on unmute,
                // rather than passing audibly through unmuted on the way.
                level.muted -> if (!audio.isStreamMute(stream)) {
                    audio.setStreamVolume(stream, index, 0)
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                }
                else -> {
                    if (audio.isStreamMute(stream)) {
                        audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                    }
                    audio.setStreamVolume(stream, index, 0)
                }
            }
        }
        return current()
    }

    /**
     * The stream's level each time the system reports it changed, whatever moved
     * it — the hardware volume keys handled by the OS, the system volume panel,
     * or [apply] above (callers wanting only outside changes filter those out).
     */
    fun changes(): Flow<Level> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val stream = intent?.getIntExtra(EXTRA_STREAM_TYPE, AudioManager.STREAM_MUSIC)
                if (stream == AudioManager.STREAM_MUSIC) trySend(current())
            }
        }
        val filter = IntentFilter().apply {
            addAction(VOLUME_CHANGED_ACTION)
            addAction(STREAM_MUTE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    fun toPercent(index: Int): Int = (index * 100f / max).roundToInt().coerceIn(0, 100)

    fun toIndex(percent: Int): Int = (percent / 100f * max).roundToInt().coerceIn(0, max)

    private companion object {
        // The broadcasts the system's own volume panel listens to. They're part
        // of AudioManager but hidden from the SDK, hence the literals.
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}
