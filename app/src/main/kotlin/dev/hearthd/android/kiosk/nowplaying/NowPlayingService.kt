package dev.hearthd.android.kiosk.nowplaying

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * The one place the kiosk asks "what's playing?". [sources] feed it and widgets
 * read it; today the only source is Snapcast, but nothing here knows that.
 *
 * Demand-driven, unlike the other controllers: rather than a run loop started
 * from MainActivity, [state] is shared only while something collects it, and
 * because the sources are cold that demand reaches all the way down to their
 * connections. Nothing on screen showing now-playing means no source running and
 * no socket open — the integration costs nothing until it has a consumer.
 * Collecting with `collectAsStateWithLifecycle` makes it foreground-only by the
 * same mechanism, so it needs no lifecycle plumbing of its own.
 *
 * The timeout keeps the link up across a template swap or a configuration
 * change, which would otherwise drop every subscriber for an instant and force a
 * reconnect.
 */
class NowPlayingService(
    scope: CoroutineScope,
    sources: List<NowPlayingSource>,
) {
    /** The first source with something to say. */
    val state: StateFlow<NowPlaying?> = combined(sources)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(IDLE_TIMEOUT_MS), null)

    private companion object {
        const val IDLE_TIMEOUT_MS = 5_000L
    }
}

/**
 * Provided by the host so widgets can read what's playing without threading it
 * through the template tree — the same arrangement as `LocalLightCommander`, and
 * for the same reason: this is a fact about the device, not a value carried by
 * the dashboard's `/state` blob.
 */
val LocalNowPlaying = staticCompositionLocalOf<StateFlow<NowPlaying?>> { MutableStateFlow(null) }

/** Whichever source has something to say, preferring the order they were given. */
private fun combined(sources: List<NowPlayingSource>): Flow<NowPlaying?> {
    if (sources.isEmpty()) return flowOf(null)
    return combine(sources.map { it.nowPlaying }) { all -> all.firstOrNull { it != null } }
}
