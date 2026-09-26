package dev.hearthd.android.kiosk

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.hearthd.android.kiosk.dashboard.DashboardController
import dev.hearthd.android.kiosk.settings.ManagedSettingsController
import dev.hearthd.android.kiosk.settings.SettingsRepository
import dev.hearthd.android.kiosk.snapcast.SnapcastController
import dev.hearthd.android.kiosk.snapcast.SnapcastVolumeSync
import dev.hearthd.android.kiosk.voice.VoiceController
import dev.hearthd.android.kiosk.wakeword.WakeWordDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Holds the objects whose lifetime is the process rather than the screen.
 *
 * The kiosk has two readers of this state: [MainActivity], which draws it, and
 * [dev.hearthd.android.kiosk.service.KioskService], which keeps it moving while
 * nothing is drawn at all. Both need to see the *same* controller — a dashboard
 * polled by the service has to be the one the activity renders when the panel
 * lights up again — so ownership sits here, above both, rather than in either.
 *
 * Nothing here starts any work. The objects are inert until the service runs a
 * loop against them, which keeps "what exists" and "what is running" separate:
 * the process can be alive with every loop stopped, and that's a legible state
 * rather than an accident.
 */
class KioskApp : Application() {
    /** Persisted preferences; a DataStore singleton underneath, so shared by all. */
    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    /**
     * Template control: persists the `settings` blob out of whatever body the
     * dashboard poll last verified.
     */
    val managed: ManagedSettingsController by lazy {
        ManagedSettingsController(saveConfig = { settings.setManagedCache(it) })
    }

    /**
     * The polled dashboard template and state, and the kiosk's only reader of
     * `/state`. Its template body feeds [managed] too, but only while the device
     * has opted into template control — otherwise the blob it persists would be
     * overlaid the moment someone turned the setting on.
     */
    val dashboard: DashboardController by lazy {
        DashboardController(
            onTemplate = { json, interval ->
                if (settings.managedEnabled.first()) managed.accept(json, interval)
            },
        )
    }

    /** The snapclient subprocess, and this device's half of the multi-room stream. */
    val snapcast: SnapcastController by lazy { SnapcastController(this) }

    /**
     * The link that makes the device's music stream the server's mixer. Separate
     * from [snapcast] because the server can be given the volume without the
     * client running, and because it is its own opt-in.
     */
    val volumeSync: SnapcastVolumeSync by lazy { SnapcastVolumeSync(this) }

    /** The microphone and wake-word model, and the frames voice turns stream. */
    val wakeWord: WakeWordDetector by lazy { WakeWordDetector(this) }

    /** The Home Assistant turn a detection starts, and the popup that shows it. */
    val voice: VoiceController by lazy { VoiceController() }

    private val _micPermission by lazy { MutableStateFlow(hasMicPermission()) }

    /**
     * Whether RECORD_AUDIO is granted. Only an activity can ask for it, so the
     * activity calls [refreshMicPermission] whenever it may have changed.
     */
    val micPermission: StateFlow<Boolean> get() = _micPermission

    fun refreshMicPermission() {
        _micPermission.value = hasMicPermission()
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
