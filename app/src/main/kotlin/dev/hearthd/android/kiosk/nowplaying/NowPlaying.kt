package dev.hearthd.android.kiosk.nowplaying

import kotlinx.coroutines.flow.Flow

/**
 * The track playing on this device right now, as much of it as the source knows.
 * Every field is optional: sources differ in what they carry, and a given track
 * may simply not have an album or a cover.
 *
 * A null [NowPlaying] — rather than an instance with every field null — is how a
 * source says nothing is playing at all.
 */
data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Cover art as something Coil can load: an http(s) URL or a `data:` URI. */
    val artUri: String? = null,
)

/**
 * Somewhere the kiosk can learn what's playing. Implementations are cold: the
 * flow does its work — opening sockets, subscribing to the system — only while
 * it's collected, which is what lets [NowPlayingService] run nothing at all when
 * no widget on screen is showing now-playing.
 */
interface NowPlayingSource {
    val nowPlaying: Flow<NowPlaying?>
}
