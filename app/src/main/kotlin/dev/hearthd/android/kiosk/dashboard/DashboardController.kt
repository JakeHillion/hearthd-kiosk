package dev.hearthd.android.kiosk.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

enum class DashboardStatus { IDLE, LOADING, LIVE, ERROR }

/**
 * What the kiosk needs to draw the dashboard, plus enough status for the Display
 * settings pane. On error the last-good [template]/[state] are kept so the screen
 * stays populated (stale) rather than blanking.
 */
data class DashboardUiState(
    val status: DashboardStatus = DashboardStatus.IDLE,
    val templateHash: String? = null,
    val template: Template? = null,
    val state: JSONObject = JSONObject(),
    val refreshIntervalSeconds: Int = DEFAULT_REFRESH_SECONDS,
    val lastUpdatedEpochMs: Long? = null,
    val message: String? = null,
)

/**
 * Polls `/state`, and fetches a template body by hash only when the hash changes
 * — one template is held at a time (the current one). Mirrors UpdateController:
 * a single run at a time via [runLock], progress exposed as a [StateFlow].
 *
 * Content addressing gives integrity for free: the fetched template body is
 * verified against the requested sha256 before it's parsed.
 */
class DashboardController {
    private val templates = TemplateClient()
    private val runLock = Mutex()

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    // Grows on consecutive failures, resets on success. Drives the caller's wait
    // when a poll throws, so a dead server is retried gently, not hammered.
    private var backoffSeconds = MIN_BACKOFF_SECONDS

    // What was last polled, so a command-triggered nudge can repeat it without
    // the caller having to thread the URL and token back through. Held as one
    // object so a nudge can never pair a new URL with a stale token.
    @Volatile
    private var lastPoll: PollTarget? = null

    /** A `/state` poll's inputs: where to fetch, and who to say we are. */
    private data class PollTarget(val stateUrl: String, val deviceToken: String?)

    /**
     * Run one poll cycle against [stateUrl], identifying this device to the server
     * with [deviceToken] when we have one. Returns the number of seconds to wait
     * before the next call: the server's clamped `refresh_interval` on success, or
     * a growing backoff on failure.
     */
    suspend fun poll(stateUrl: String, deviceToken: String?): Int = runLock.withLock {
        lastPoll = PollTarget(stateUrl, deviceToken)
        if (_state.value.template == null) {
            _state.update { it.copy(status = DashboardStatus.LOADING) }
        }
        try {
            val response = templates.fetchState(stateUrl, deviceToken)
            val current = _state.value
            // Reuse the held template while its hash is unchanged; otherwise fetch
            // and verify the new body and swap the single slot.
            val template =
                if (response.templateHash == current.templateHash && current.template != null) {
                    current.template
                } else {
                    Template.fromJson(templates.fetchTemplateJson(stateUrl, response.templateHash))
                }
            val interval = response.refreshIntervalSeconds
                .coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS)
            _state.update {
                it.copy(
                    status = DashboardStatus.LIVE,
                    templateHash = response.templateHash,
                    template = template,
                    state = response.state,
                    refreshIntervalSeconds = interval,
                    lastUpdatedEpochMs = System.currentTimeMillis(),
                    message = null,
                )
            }
            backoffSeconds = MIN_BACKOFF_SECONDS
            interval
        } catch (e: Exception) {
            _state.update { it.copy(status = DashboardStatus.ERROR, message = e.message) }
            val wait = backoffSeconds
            backoffSeconds = (backoffSeconds * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
            wait
        }
    }

    /** Drop the current template and state, e.g. when the dashboard is disabled. */
    fun clear() {
        backoffSeconds = MIN_BACKOFF_SECONDS
        lastPoll = null
        _state.value = DashboardUiState()
    }

    /**
     * Re-poll the last target immediately, if we've polled at all. Used after a
     * light command so the confirmed state lands without waiting for the next
     * scheduled poll. No-op before the first poll or once cleared.
     */
    suspend fun refreshNow() {
        lastPoll?.let { poll(it.stateUrl, it.deviceToken) }
    }

    companion object {
        // Honour the server's cadence, but never poll absurdly fast or effectively never.
        private const val MIN_REFRESH_SECONDS = 2
        private const val MAX_REFRESH_SECONDS = 3600
        private const val MIN_BACKOFF_SECONDS = 5
        private const val MAX_BACKOFF_SECONDS = 60
    }
}
