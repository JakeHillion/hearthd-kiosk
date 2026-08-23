package dev.hearthd.android.kiosk.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Obtains a Home Assistant access token without anyone typing one, by running
 * HA's login flow against the `trusted_networks` auth provider. Because the
 * kiosk connects from a trusted IP (configure `allow_bypass_login: true` with a
 * single trusted user on HA), the flow returns an authorization code with no
 * password step; if HA instead returns a user-picker form we select the first
 * offered user. The code is exchanged for an access + refresh token, cached in
 * memory, and refreshed as it expires.
 *
 * IndieAuth note: HA validates `client_id`/`redirect_uri`. Using the HA base URL
 * for both makes them same-origin, which satisfies the check without hosting a
 * discovery page. This is the fiddliest part of the integration and the most
 * likely thing to need tweaking against a specific HA setup.
 */
class HomeAssistantAuth(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    private val base = baseUrl.trimEnd('/')
    private val clientId = base
    private val redirectUri = base

    private var accessToken: String? = null
    private var accessExpiryMs = 0L
    private var refreshToken: String? = null

    /** A currently-valid access token, refreshing or re-authenticating as needed. */
    suspend fun accessToken(): String {
        val now = System.currentTimeMillis()
        accessToken?.let { if (now < accessExpiryMs - REFRESH_SKEW_MS) return it }
        refreshToken?.let { rt ->
            runCatching { return refresh(rt) }
        }
        return login()
    }

    private suspend fun login(): String = withContext(Dispatchers.IO) {
        val start = JSONObject()
            .put("client_id", clientId)
            .put("handler", JSONArray().put("trusted_networks").put(JSONObject.NULL))
            .put("redirect_uri", redirectUri)
        var flow = postJson("$base/auth/login_flow", start.toString())

        if (flow.optString("type") == "form") {
            val flowId = flow.getString("flow_id")
            val userId = firstUserOption(flow)
                ?: throw IOException("trusted_networks form offered no users to select")
            flow = postJson(
                "$base/auth/login_flow/$flowId",
                JSONObject().put("client_id", clientId).put("user", userId).toString(),
            )
        }

        if (flow.optString("type") != "create_entry") {
            throw IOException("login_flow was not authorized (type=${flow.optString("type")}); is this IP trusted?")
        }
        exchangeCode(flow.getString("result"))
    }

    private suspend fun exchangeCode(code: String): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .build()
        storeTokens(postForm("$base/auth/token", form))
    }

    private suspend fun refresh(rt: String): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", rt)
            .add("client_id", clientId)
            .build()
        // A refresh response omits the refresh token; keep the one we have.
        storeTokens(postForm("$base/auth/token", form), keepRefresh = rt)
    }

    private fun storeTokens(json: JSONObject, keepRefresh: String? = null): String {
        val token = json.getString("access_token")
        accessToken = token
        accessExpiryMs = System.currentTimeMillis() + json.optLong("expires_in", 1800L) * 1000L
        refreshToken = json.optString("refresh_token", null) ?: keepRefresh ?: refreshToken
        return token
    }

    private fun firstUserOption(flow: JSONObject): String? {
        // data_schema is a list of field descriptors; the trusted_networks step
        // exposes a "user" select whose options are [id, label] pairs.
        val schema = flow.optJSONArray("data_schema") ?: return null
        for (i in 0 until schema.length()) {
            val field = schema.optJSONObject(i) ?: continue
            if (field.optString("name") != "user") continue
            val options = field.optJSONArray("options") ?: return null
            val first = options.optJSONArray(0) ?: return null
            return first.optString(0, null)
        }
        return null
    }

    private fun postJson(url: String, json: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        return execute(req)
    }

    private fun postForm(url: String, form: FormBody): JSONObject {
        val req = Request.Builder().url(url).post(form).build()
        return execute(req)
    }

    private fun execute(req: Request): JSONObject {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from ${req.url}: $body")
            return JSONObject(body)
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        const val REFRESH_SKEW_MS = 30_000L
    }
}
