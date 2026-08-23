package dev.hearthd.android.kiosk.update

import org.json.JSONObject

/** The subset of `android/kiosk/<channel>.json` the updater consumes. */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
) {
    companion object {
        fun fromJson(text: String): UpdateManifest {
            val obj = JSONObject(text)
            return UpdateManifest(
                versionCode = obj.getInt("versionCode"),
                versionName = obj.getString("versionName"),
                apkUrl = obj.getString("apkUrl"),
                sha256 = obj.getString("sha256"),
            )
        }
    }
}
