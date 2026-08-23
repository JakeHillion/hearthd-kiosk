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
 */
class TemplateClient(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun fetchState(stateUrl: String): StateResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(stateUrl).build()
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
