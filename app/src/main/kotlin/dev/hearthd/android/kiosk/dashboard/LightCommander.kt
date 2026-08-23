package dev.hearthd.android.kiosk.dashboard

import androidx.compose.runtime.staticCompositionLocalOf
import dev.hearthd.android.kiosk.settings.HearthdSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The light *write* path: fire-and-forget commands to hearthd. Read state still
 * arrives through the dashboard's `/state` poll; this only sends changes.
 *
 * hearthd dispatches commands asynchronously and doesn't return the new state,
 * so callers pair each command with an optimistic UI overlay and rely on the
 * poll (nudged sooner via [LightController]) to confirm. Methods are
 * non-suspending and return immediately — the network happens on a background
 * coroutine.
 */
interface LightCommander {
    /** Turn a light on or off (OnOff cluster). */
    fun setOn(entityId: String, endpoint: Int, on: Boolean)

    /** Set brightness 1..254 (LevelControl MoveToLevel). Does not change on/off. */
    fun setLevel(entityId: String, endpoint: Int, level: Int)

    // Colour (ColorControl) is intentionally absent until hearthd's colour
    // read/command shape is pinned down — see the widget's colour page TODO.
}

/** The no-op used in previews and whenever hearthd control isn't configured. */
object NoopLightCommander : LightCommander {
    override fun setOn(entityId: String, endpoint: Int, on: Boolean) {}
    override fun setLevel(entityId: String, endpoint: Int, level: Int) {}
}

/** Provided by the host so widgets can act without threading callbacks through the tree. */
val LocalLightCommander = staticCompositionLocalOf<LightCommander> { NoopLightCommander }

/**
 * A [LightCommander] backed by hearthd's `POST /v1/entities/{entity_id}/command`.
 *
 * [settings] is read afresh on every command so a URL change takes effect without
 * rebuilding this; when hearthd isn't enabled/configured, commands are dropped.
 * After each dispatched command it invokes [onCommandSent] (after a short delay)
 * so the host can re-poll `/state` and confirm the change quickly.
 */
class LightController(
    private val scope: CoroutineScope,
    private val settings: () -> HearthdSettings,
    private val onCommandSent: suspend () -> Unit,
    private val client: OkHttpClient = OkHttpClient(),
) : LightCommander {

    override fun setOn(entityId: String, endpoint: Int, on: Boolean) {
        val command = JSONObject()
            .put("cluster", "OnOff")
            .put("command", if (on) "On" else "Off")
        send(entityId, endpoint, command)
    }

    override fun setLevel(entityId: String, endpoint: Int, level: Int) {
        val moveToLevel = JSONObject()
            .put("level", level.coerceIn(MIN_LEVEL, MAX_LEVEL))
            .put("transition_time", JSONObject.NULL)
        val command = JSONObject()
            .put("cluster", "LevelControl")
            .put("command", JSONObject().put("MoveToLevel", moveToLevel))
        send(entityId, endpoint, command)
    }

    private fun send(entityId: String, endpoint: Int, command: JSONObject) {
        val s = settings()
        if (!s.enabled || !s.configured) return
        val url = "${s.baseUrl.trimEnd('/')}/v1/entities/$entityId/command"
        val payload = JSONObject().put("endpoint", endpoint).put("command", command)

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val body = payload.toString().toRequestBody(JSON)
                    val request = Request.Builder().url(url).post(body).build()
                    client.newCall(request).execute().use { /* fire-and-forget */ }
                }
            }
            // Give the command a beat to take effect, then nudge a re-poll so the
            // polled state (and any optimistic overlay) reconciles quickly.
            delay(NUDGE_DELAY_MS)
            runCatching { onCommandSent() }
        }
    }

    private companion object {
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 254
        const val NUDGE_DELAY_MS = 1_000L
        val JSON = "application/json".toMediaType()
    }
}
