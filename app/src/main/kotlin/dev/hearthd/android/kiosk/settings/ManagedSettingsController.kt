package dev.hearthd.android.kiosk.settings

import dev.hearthd.android.kiosk.dashboard.TemplateClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

enum class ManagedStatus { IDLE, LOADING, LIVE, ERROR }

/** Status of the template-control poller, for the Settings pane. */
data class ManagedUiState(
    val status: ManagedStatus = ManagedStatus.IDLE,
    val refreshIntervalSeconds: Int = 0,
    val lastUpdatedEpochMs: Long? = null,
    val message: String? = null,
)

/**
 * Polls the template `/state` URL and hands its top-level `settings` blob to
 * [saveConfig], which persists it for [SettingsRepository]'s overlay to read.
 * Mirrors [dev.hearthd.android.kiosk.dashboard.DashboardController]: one run at a
 * time via [runLock], progress exposed as a [StateFlow], gentle backoff on
 * failure. Template integrity (sha256) is verified by [TemplateClient].
 *
 * A template with no `settings` key still counts as a successful poll — it stores
 * an empty blob, so every managed domain falls back to its built-in default.
 */
class ManagedSettingsController(
    private val saveConfig: suspend (String) -> Unit,
) {
    private val templates = TemplateClient()
    private val runLock = Mutex()

    private val _state = MutableStateFlow(ManagedUiState())
    val state: StateFlow<ManagedUiState> = _state.asStateFlow()

    private var backoffSeconds = MIN_BACKOFF_SECONDS

    /**
     * Run one poll against [stateUrl]. Returns seconds to wait before the next
     * call: the server's clamped `refresh_interval` on success, else a growing
     * backoff.
     */
    suspend fun poll(stateUrl: String): Int = runLock.withLock {
        if (_state.value.status == ManagedStatus.IDLE) {
            _state.update { it.copy(status = ManagedStatus.LOADING) }
        }
        try {
            val response = templates.fetchState(stateUrl)
            val templateJson = templates.fetchTemplateJson(stateUrl, response.templateHash)
            val settings = JSONObject(templateJson).optJSONObject("settings") ?: JSONObject()
            saveConfig(settings.toString())
            val interval = response.refreshIntervalSeconds
                .coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS)
            _state.update {
                it.copy(
                    status = ManagedStatus.LIVE,
                    refreshIntervalSeconds = interval,
                    lastUpdatedEpochMs = System.currentTimeMillis(),
                    message = null,
                )
            }
            backoffSeconds = MIN_BACKOFF_SECONDS
            interval
        } catch (e: Exception) {
            _state.update { it.copy(status = ManagedStatus.ERROR, message = e.message) }
            val wait = backoffSeconds
            backoffSeconds = (backoffSeconds * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
            wait
        }
    }

    /** Reset status when template control is turned off. The cached blob stays. */
    fun clear() {
        backoffSeconds = MIN_BACKOFF_SECONDS
        _state.value = ManagedUiState()
    }

    companion object {
        private const val MIN_REFRESH_SECONDS = 2
        private const val MAX_REFRESH_SECONDS = 3600
        private const val MIN_BACKOFF_SECONDS = 5
        private const val MAX_BACKOFF_SECONDS = 60
    }
}
