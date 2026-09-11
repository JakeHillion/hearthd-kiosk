package dev.hearthd.android.kiosk.settings

import dev.hearthd.android.kiosk.wakeword.WakeWordModel
import org.json.JSONObject

/**
 * The device configuration a template can dictate under "full template control".
 * One field per managed domain.
 *
 * Deliberately absent: the dashboard and the update settings. The dashboard's
 * `/state` URL is the address the device is pointed at to *reach* the template,
 * so it can't be dictated by the template it fetches; update cadence stays a
 * local, per-device choice.
 *
 * Missing keys fall back to each domain's built-in default rather than the local
 * value: when a device is under template control the template is authoritative,
 * and a locally-set value from before could be stale and surprising.
 */
data class ManagedConfig(
    val hearthd: HearthdSettings = HearthdSettings(),
    val snapcast: SnapcastSettings = SnapcastSettings(),
    val wakeWord: WakeWordSettings = WakeWordSettings(),
    val voice: VoiceSettings = VoiceSettings(),
) {
    companion object {
        /** Parse the template's `settings` object; every field is optional. */
        fun fromJson(obj: JSONObject): ManagedConfig = ManagedConfig(
            hearthd = obj.optJSONObject("hearthd")?.let { h ->
                HearthdSettings(
                    enabled = h.optBoolean("enabled", false),
                    baseUrl = h.optString("base_url", ""),
                )
            } ?: HearthdSettings(),
            snapcast = obj.optJSONObject("snapcast")?.let { s ->
                SnapcastSettings(
                    enabled = s.optBoolean("enabled", false),
                    host = s.optString("host", ""),
                    port = s.optInt("port", SnapcastSettings.DEFAULT_PORT),
                    volumeSync = s.optBoolean("volume_sync", false),
                    controlPort = s.optInt("control_port", SnapcastSettings.DEFAULT_CONTROL_PORT),
                )
            } ?: SnapcastSettings(),
            wakeWord = obj.optJSONObject("wake_word")?.let { w ->
                val model = WakeWordModel.fromId(w.optString("model", null))
                WakeWordSettings(
                    enabled = w.optBoolean("enabled", false),
                    model = model,
                    threshold = w.optDouble("threshold", model.defaultThreshold.toDouble())
                        .toFloat(),
                )
            } ?: WakeWordSettings(),
            voice = obj.optJSONObject("voice")?.let { v ->
                VoiceSettings(
                    enabled = v.optBoolean("enabled", false),
                    baseUrl = v.optString("base_url", ""),
                    pipelineId = v.optString("pipeline_id", ""),
                )
            } ?: VoiceSettings(),
        )
    }
}
