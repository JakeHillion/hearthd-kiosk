package dev.hearthd.android.kiosk.settings

/** A release channel, matching the manifest path `android/kiosk/<id>.json`. */
enum class Channel(val id: String, val label: String) {
    MAIN("main", "Main"),
    CANARY("canary", "Canary"),
    ;

    companion object {
        fun fromId(id: String?): Channel = entries.firstOrNull { it.id == id } ?: MAIN
    }
}

/** Discrete update-check intervals offered by the slider, in minutes. */
val INTERVAL_STOPS = listOf(1, 15, 60, 360, 1440)

fun formatInterval(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes < 1440 -> "${minutes / 60}h"
    else -> "${minutes / 1440}d"
}

/**
 * User-controlled update preferences. Auto-update is off by default: with
 * [enabled] false the app makes no network requests at all.
 */
data class UpdateSettings(
    val enabled: Boolean = false,
    val channel: Channel = Channel.MAIN,
    val intervalMinutes: Int = 360,
)
