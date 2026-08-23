package dev.hearthd.android.kiosk.dashboard

import org.json.JSONObject

/** Fallback poll cadence when `/state` omits `refresh_interval`. */
const val DEFAULT_REFRESH_SECONDS = 30

/**
 * The `/state` document: a content-addressed [templateHash], the server's
 * desired [refreshIntervalSeconds], and the live [state] blob that fills the
 * template's slots. The template body itself is fetched separately, by hash.
 */
data class StateResponse(
    val templateHash: String,
    val refreshIntervalSeconds: Int,
    val state: JSONObject,
) {
    companion object {
        fun fromJson(text: String): StateResponse {
            val obj = JSONObject(text)
            return StateResponse(
                templateHash = obj.getString("template"),
                refreshIntervalSeconds = obj.optInt("refresh_interval", DEFAULT_REFRESH_SECONDS),
                state = obj.optJSONObject("state") ?: JSONObject(),
            )
        }
    }
}
