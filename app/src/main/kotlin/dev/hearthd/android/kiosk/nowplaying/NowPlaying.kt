package dev.hearthd.android.kiosk.nowplaying

import kotlinx.coroutines.flow.Flow

/** Where the player has got to, as far as the source can tell. */
enum class Playback { STOPPED, PAUSED, PLAYING }

/** A transport action, for the sources that accept one. */
enum class NowPlayingCommand { PLAY_PAUSE, NEXT, PREVIOUS }

/**
 * The track playing on this device right now, as much of it as the source knows.
 * Every descriptive field is optional: sources differ in what they carry, and a
 * given track may simply not have an album or a cover.
 *
 * A null [NowPlaying] — rather than an instance with every field null — is how a
 * source says nothing is playing at all.
 *
 * The `can…` flags are the source's own account of what it will accept, not a
 * guess: with Snapcast they come from the stream, so a pipe with no player
 * behind it offers nothing while a Spotify stream offers the lot. Consumers show
 * only what's offered rather than buttons that quietly do nothing, and a source
 * is free to ignore a command it never advertised.
 */
data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Cover art as something Coil can load: an http(s) URL or a `data:` URI. */
    val artUri: String? = null,
    val playback: Playback = Playback.STOPPED,
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canGoNext: Boolean = false,
    val canGoPrevious: Boolean = false,
    /** False when the source takes no commands at all; the rest are then moot. */
    val canControl: Boolean = false,
)

/**
 * Somewhere the kiosk can learn what's playing, and ask it to change. [nowPlaying]
 * is cold: it does its work — opening sockets, subscribing to the system — only
 * while collected, which is what lets [NowPlayingService] run nothing at all when
 * no widget on screen is showing now-playing.
 */
interface NowPlayingSource {
    val nowPlaying: Flow<NowPlaying?>

    /**
     * Ask the player to act. Fire-and-forget and non-blocking, like a light
     * command: the result arrives as an ordinary [nowPlaying] update, and a
     * command sent with nothing playing or nothing connected is dropped.
     */
    fun send(command: NowPlayingCommand)
}
