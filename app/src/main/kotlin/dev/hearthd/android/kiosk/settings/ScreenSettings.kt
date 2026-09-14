package dev.hearthd.android.kiosk.settings

/**
 * Remote display control preferences. Off by default and inert until device
 * administration is granted as well: this says the device is willing to let its
 * dashboard drive the panel, while the grant is what makes a screen-off legal
 * at all — and that one can only ever be given by someone standing at the
 * device.
 */
data class ScreenSettings(
    val enabled: Boolean = false,
)
