package dev.hearthd.android.kiosk

import android.app.Application
import dev.hearthd.android.kiosk.dashboard.DashboardController
import dev.hearthd.android.kiosk.settings.ManagedSettingsController
import dev.hearthd.android.kiosk.settings.SettingsRepository
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
}
