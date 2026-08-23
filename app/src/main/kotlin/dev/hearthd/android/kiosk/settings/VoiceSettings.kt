package dev.hearthd.android.kiosk.settings

/**
 * Voice-assistant preferences (Alpha). Off by default and inert until a Home
 * Assistant URL is set. Auth uses HA's trusted_networks provider, so there's no
 * token to store here — only where to reach HA and which pipeline to run.
 */
data class VoiceSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    /** HA pipeline id; blank runs HA's preferred pipeline. */
    val pipelineId: String = "",
) {
    val configured: Boolean get() = baseUrl.isNotBlank()
}
