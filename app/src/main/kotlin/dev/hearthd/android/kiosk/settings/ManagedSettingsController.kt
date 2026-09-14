package dev.hearthd.android.kiosk.settings

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
 * Takes the template body the dashboard poll already fetched and persists its
 * top-level `settings` blob via [saveConfig], for [SettingsRepository]'s overlay
 * to read.
 *
 * A consumer rather than a poller: the settings live in the same
 * content-addressed document as the widget tree, so fetching them again would
 * mean a second request for bytes the kiosk already holds — and, since the
 * dashboard poll moved to the service, a second *lifetime* too, leaving live
 * state and stale settings read from one document.
 *
 * [state] therefore reports on the settings handling alone. A failed poll is the
 * dashboard's error to show; here it simply means [lastUpdatedEpochMs] stops
 * advancing, which is what staleness looks like.
 *
 * A template with no `settings` key still counts as success — it stores an empty
 * blob, so every managed domain falls back to its built-in default.
 */
class ManagedSettingsController(
    private val saveConfig: suspend (String) -> Unit,
) {
    private val runLock = Mutex()

    private val _state = MutableStateFlow(ManagedUiState())
    val state: StateFlow<ManagedUiState> = _state.asStateFlow()

    /**
     * Persist the `settings` blob out of an already-verified [templateJson].
     * [refreshIntervalSeconds] is the dashboard's cadence, carried through only
     * so the Managed pane can show how often this is refreshed.
     */
    suspend fun accept(templateJson: String, refreshIntervalSeconds: Int) = runLock.withLock {
        try {
            val settings = JSONObject(templateJson).optJSONObject("settings") ?: JSONObject()
            saveConfig(settings.toString())
            _state.update {
                it.copy(
                    status = ManagedStatus.LIVE,
                    refreshIntervalSeconds = refreshIntervalSeconds,
                    lastUpdatedEpochMs = System.currentTimeMillis(),
                    message = null,
                )
            }
        } catch (e: Exception) {
            // A body that parses as a template but whose settings won't store.
            _state.update { it.copy(status = ManagedStatus.ERROR, message = e.message) }
        }
    }

    /** Reset status when template control is turned off. The cached blob stays. */
    fun clear() {
        _state.value = ManagedUiState()
    }
}
