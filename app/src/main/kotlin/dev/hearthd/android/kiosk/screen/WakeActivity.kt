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
 * Calling `setTurnScreenOn(true)` on the kiosk activity already sitting behind
 * the dark panel does nothing: the platform arms that flag only as part of an
 * activity launch, so the activity has to be a new one.
 */
class WakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Also declared in the manifest: the attributes are read when the
        // system decides whether to resume this activity at all, which happens
        // before any of this runs.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // Long enough to be drawn, since the wake happens as the activity
        // resumes, and short enough that the kiosk behind it is back before
        // anyone looks at the panel.
        lifecycleScope.launch {
            delay(VISIBLE_MILLIS)
            finish()
        }
    }

    private companion object {
        const val VISIBLE_MILLIS = 400L
    }
}
