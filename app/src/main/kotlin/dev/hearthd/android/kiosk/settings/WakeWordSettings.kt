package dev.hearthd.android.kiosk.settings

import dev.hearthd.android.kiosk.wakeword.WakeWordModel

/**
 * User-controlled wake-word preferences. Off by default: with [enabled] false
 * the app never opens the microphone, matching the opt-in stance of updates.
 */
data class WakeWordSettings(
    val enabled: Boolean = false,
    val model: WakeWordModel = WakeWordModel.DEFAULT,
    val threshold: Float = WakeWordModel.DEFAULT.defaultThreshold,
)

/** Bounds for the sensitivity slider; a higher threshold means fewer triggers. */
val THRESHOLD_RANGE = 0.1f..0.9f
