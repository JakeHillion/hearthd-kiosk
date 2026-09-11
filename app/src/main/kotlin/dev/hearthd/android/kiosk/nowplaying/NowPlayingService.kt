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

/** What a widget needs of now-playing: the current track, and a way to act on it. */
interface NowPlayingHandle {
    val state: StateFlow<NowPlaying?>

    fun send(command: NowPlayingCommand)
}

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
) : NowPlayingHandle {

    // The source behind the current track, so a command goes to the thing that
    // is actually playing rather than to everything that might be. Written from
    // the sharing coroutine, read from the UI thread on a button press.
    @Volatile
    private var active: NowPlayingSource? = null

    /** The first source with something to say. */
    override val state: StateFlow<NowPlaying?> = combined(sources) { active = it }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(IDLE_TIMEOUT_MS), null)

    override fun send(command: NowPlayingCommand) {
        active?.send(command)
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 5_000L
    }
}

/**
 * Provided by the host so widgets can read and drive what's playing without
 * threading it through the template tree — the same arrangement as
 * `LocalLightCommander`, and for the same reason: this is a fact about the
 * device, not a value carried by the dashboard's `/state` blob.
 */
val LocalNowPlaying = staticCompositionLocalOf<NowPlayingHandle> { NoNowPlaying }

/** The no-op used in previews and wherever the host hasn't provided a service. */
private object NoNowPlaying : NowPlayingHandle {
    override val state: StateFlow<NowPlaying?> = MutableStateFlow(null)

    override fun send(command: NowPlayingCommand) {}
}

/**
 * Whichever source has something to say, preferring the order they were given,
 * reporting it to [onActive] so commands can be routed back to it.
 */
private fun combined(
    sources: List<NowPlayingSource>,
    onActive: (NowPlayingSource?) -> Unit,
): Flow<NowPlaying?> {
    if (sources.isEmpty()) return flowOf(null)
    return combine(sources.map { it.nowPlaying }) { all ->
        val index = all.indexOfFirst { it != null }
        onActive(sources.getOrNull(index))
        all.getOrNull(index)
    }
}
