package dev.hearthd.android.kiosk.update

import android.content.Context
import dev.hearthd.android.kiosk.BuildConfig
import dev.hearthd.android.kiosk.settings.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class UpdateStatus { IDLE, CHECKING, DOWNLOADING, INSTALLING, UP_TO_DATE, FAILED }

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val lastCheckedEpochMs: Long? = null,
    val latestVersionCode: Int? = null,
    val deviceOwner: Boolean = false,
    val message: String? = null,
)

/**
 * Runs update checks and exposes their progress to the UI. A single check at a
 * time is enforced so the periodic loop and a manual "Check now" can't overlap.
 */
class UpdateController(context: Context) {
    private val updater = Updater(context.applicationContext)
    private val runLock = Mutex()

    private val _state = MutableStateFlow(UpdateUiState(deviceOwner = updater.isDeviceOwner()))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Fetch the channel manifest and, if it's newer than us, download + install. */
    suspend fun check(channel: Channel) = runLock.withLock {
        _state.update { it.copy(status = UpdateStatus.CHECKING, message = null) }
        try {
            val manifest = updater.fetchManifest(channel)
            _state.update {
                it.copy(
                    lastCheckedEpochMs = System.currentTimeMillis(),
                    latestVersionCode = manifest.versionCode,
                )
            }
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                _state.update { it.copy(status = UpdateStatus.UP_TO_DATE) }
                return@withLock
            }
            _state.update { it.copy(status = UpdateStatus.DOWNLOADING) }
            val apk = updater.download(manifest)
            try {
                _state.update { it.copy(status = UpdateStatus.INSTALLING) }
                updater.install(apk)
            } finally {
                // The installer session has its own copy once committed, so the
                // cached download is dead weight either way — drop it so the
                // cache doesn't accumulate an APK per update.
                apk.delete()
            }
        } catch (e: Exception) {
            _state.update { it.copy(status = UpdateStatus.FAILED, message = e.message) }
        }
    }
}
