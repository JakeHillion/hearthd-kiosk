package dev.hearthd.android.kiosk.settings

/**
 * hearthd control preferences. The dashboard's `/state` feeds light *state* into
 * the UI (read path); this is the separate *write* path — where to reach hearthd
 * to send commands (toggle, brightness). Off by default and inert until a base
 * URL is set: with [enabled] false, or no URL, taps on light widgets do nothing.
 */
data class HearthdSettings(
    val enabled: Boolean = false,
    /** hearthd base URL, e.g. `https://hearthd.iot.home.jakehillion.me`. */
    val baseUrl: String = "",
) {
    val configured: Boolean get() = baseUrl.isNotBlank()
}
