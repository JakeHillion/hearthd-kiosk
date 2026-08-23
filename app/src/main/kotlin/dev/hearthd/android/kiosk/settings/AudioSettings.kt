package dev.hearthd.android.kiosk.settings

/**
 * Volume preferences for the audio classes the device arbitrates between.
 *
 * Music volume isn't here: it's live — the Snapcast server holds it (or the
 * hardware keys, when the server is unreachable), not a stored preference. What
 * remains is the assistant's own loudness and how far music ducks under it, both
 * of which are steady policy the operator (or a template) sets once.
 */
data class AudioSettings(
    /** Loudness of the voice assistant's spoken replies, 0..100. */
    val assistantVolume: Int = DEFAULT_ASSISTANT_VOLUME,
    /** Level music drops to while the assistant has the floor, 0..100. */
    val duckPercent: Int = DEFAULT_DUCK_PERCENT,
) {
    companion object {
        const val DEFAULT_ASSISTANT_VOLUME = 80
        const val DEFAULT_DUCK_PERCENT = 20
    }
}
