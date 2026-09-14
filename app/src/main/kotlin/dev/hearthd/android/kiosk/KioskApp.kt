package dev.hearthd.android.kiosk

import android.app.Application
import dev.hearthd.android.kiosk.dashboard.DashboardController
import dev.hearthd.android.kiosk.settings.SettingsRepository

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

    /** The polled dashboard template and state. */
    val dashboard: DashboardController by lazy { DashboardController() }
}
