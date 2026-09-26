package dev.hearthd.android.kiosk.screen

import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * Lights a dark panel. Fires the deprecated wake lock and a [WakeActivity]
 * launch together: neither is reliable alone across devices, they don't
 * conflict, and the cost of the redundant one is a few milliseconds.
 */
class ScreenWaker(private val context: Context) {
    private val powerManager: PowerManager
        get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun wake() {
        if (powerManager.isInteractive) return

        // ON_AFTER_RELEASE restarts the display timeout rather than cutting the
        // screen when the lock lapses, so the panel stays lit as if touched.
        @Suppress("DEPRECATION")
        powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            WAKE_LOCK_TAG,
        ).acquire(WAKE_LOCK_MS)

        context.startActivity(
            Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        const val WAKE_LOCK_TAG = "hearthd:screen-wake"
        const val WAKE_LOCK_MS = 3_000L
    }
}
