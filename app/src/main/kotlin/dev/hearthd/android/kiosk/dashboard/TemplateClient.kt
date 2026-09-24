package dev.hearthd.android.kiosk.dashboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/**
 * Fetches the content-addressed `/state` + `/template/<hash>` pair shared by the
 * dashboard and template-control paths. Content addressing gives integrity for
 * free: [fetchTemplateJson] verifies the body against the requested sha256 before
 * returning it, so callers parse only verified bytes.
 *
 * Only `/state` identifies the device (see [DeviceToken]) — that request is where
 * the server decides which view this device gets. The template is addressed by the
 * hash that decision produced, so it needs no identity of its own and stays a
 * shared, cacheable object.
 */
class TemplateClient(private val client: OkHttpClient = OkHttpClient()) {

    /** Fetch `/state`, identifying this device to the server when [deviceToken] is set. */
    suspend fun fetchState(stateUrl: String, deviceToken: String?): StateResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(identified(stateUrl, deviceToken)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("state fetch failed: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("state fetch: empty body")
            StateResponse.fromJson(body)
        }
    }

    /** Fetch and sha256-verify the template body for [hash]; returns its JSON text. */
    suspend fun fetchTemplateJson(stateUrl: String, hash: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(templateUrl(stateUrl, hash)).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("template fetch failed: HTTP ${response.code}")
                }
                val bytes = response.body?.bytes() ?: throw IOException("template fetch: empty body")
                val actual = sha256Hex(bytes)
                if (!actual.equals(hash, ignoreCase = true)) {
                    throw IOException("template sha256 mismatch: expected $hash, got $actual")
                }
                String(bytes, Charsets.UTF_8)
            }
        }

    companion object {
        /**
         * Tag [url] with the device's token. Set rather than added, so a state URL
         * that already carries a `device` param doesn't end up with two — the
         * device's own identity is the one that should reach the server.
         */
        internal fun identified(url: String, deviceToken: String?): String {
            if (deviceToken.isNullOrBlank()) return url
            return url.toHttpUrl().newBuilder()
                .setQueryParameter(DeviceToken.QUERY_PARAM, deviceToken)
                .build()
                .toString()
        }

        /** Derive `…/template/<hash>` as a sibling of the configured `…/state`. */
        internal fun templateUrl(stateUrl: String, hash: String): String {
            val base = stateUrl.toHttpUrl()
            return base.newBuilder()
                .removePathSegment(base.pathSize - 1)
                .addPathSegment("template")
                .addPathSegment(hash)
                .build()
                .toString()
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
