package dev.hearthd.android.kiosk.screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A transparent activity whose entire job is to exist for a moment so the
 * display turns on, then get out of the way.
 *
 * The obvious implementation — call `setTurnScreenOn(true)` on the kiosk
 * activity that is already sitting there behind the dark panel — does nothing.
 * The platform arms that flag only as part of an explicit launch, so an
 * already-created activity can hold it and never wake anything. A wake lock
 * alone is no more dependable: it is the deprecated path, and several devices
 * are reported to take it and leave the screen dark regardless.
 *
 * Launching *this* is the route the platform actually supports, and the one
 * alarms and incoming calls use. [ScreenPowerController] fires both together
 * and lets whichever works win.
 */
class WakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Also declared in the manifest: the attributes are read when the
        // system decides whether to resume this activity at all, which happens
        // before any of this runs.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // Long enough to be composed and drawn — the wake happens as the
        // activity resumes — and short enough that the kiosk behind it is back
        // before anyone looks at the panel.
        lifecycleScope.launch {
            delay(VISIBLE_MILLIS)
            finish()
        }
    }

    private companion object {
        const val VISIBLE_MILLIS = 400L
    }
}
