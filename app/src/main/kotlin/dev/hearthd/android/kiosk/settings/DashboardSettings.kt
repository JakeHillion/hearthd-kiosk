package dev.hearthd.android.kiosk.settings

/**
 * Dashboard (kiosk UI) preferences. Off by default and inert until a `/state`
 * URL is set: with [enabled] false, or no URL, the app makes no dashboard
 * requests at all and shows the plain fallback surface.
 */
data class DashboardSettings(
    val enabled: Boolean = false,
    /** The `/state` endpoint; the template endpoint is derived as its sibling. */
    val stateUrl: String = "",
) {
    val configured: Boolean get() = stateUrl.isNotBlank()
}
