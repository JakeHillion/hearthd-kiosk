package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The world model behind a solar dynamic wallpaper: a collection of [frames],
 * each an image and the sun position it depicts. The kiosk picks one collection
 * per day and then, as the live sun position arrives in `/state`, renders the
 * frame whose stored position is nearest — the wallpaper tracks the real sun.
 *
 * A collection is a `metadata.json` published next to its frame images (the
 * Apple "solar" dynamic-desktop schema). The metadata's URL is the base every
 * frame's relative `file` resolves against, so a collection is just one URL.
 */
data class SolarCollection(val frames: List<SolarFrame>)

/** One frame: an absolute image [url] and the sun's [elevation]/[azimuth] in it. */
data class SolarFrame(
    val url: String,
    val elevation: Double,
    val azimuth: Double,
)

/**
 * The frame whose stored sun position is nearest [elevationDeg]/[azimuthDeg] on
 * the sky. Each direction becomes a unit vector and they're compared by dot
 * product — the largest dot is the smallest angle — so azimuth wraps at 360°
 * and elevation is handled uniformly, with no special-casing at the horizon or
 * the poles. Null only when there are no frames. At night the sun sits well
 * below the horizon, so the nearest frame is naturally the collection's dark one.
 */
fun List<SolarFrame>.nearestTo(elevationDeg: Double, azimuthDeg: Double): SolarFrame? {
    if (isEmpty()) return null
    val (tx, ty, tz) = skyVector(elevationDeg, azimuthDeg)
    return maxByOrNull { frame ->
        val (x, y, z) = skyVector(frame.elevation, frame.azimuth)
        x * tx + y * ty + z * tz
    }
}

/** Unit vector for a sun direction: azimuth clockwise from north, elevation up. */
private fun skyVector(elevationDeg: Double, azimuthDeg: Double): Triple<Double, Double, Double> {
    val el = Math.toRadians(elevationDeg)
    val az = Math.toRadians(azimuthDeg)
    val cosEl = cos(el)
    return Triple(cosEl * sin(az), cosEl * cos(az), sin(el))
}

/**
 * The collection to show for the wallpaper day [epochDay] (days since the epoch,
 * already shifted to the rollover hour) out of [count] collections. Deterministic
 * by design: the same day always yields the same index, so the pick survives
 * restarts and re-polls with no stored state. Seeding a per-day PRNG both spreads
 * the choice evenly across the collections and decorrelates adjacent days.
 */
fun dailyIndex(epochDay: Long, count: Int): Int =
    if (count <= 1) 0 else Random(epochDay).nextInt(count)

/**
 * Today's collection index, recomputed each time the local wallpaper day rolls
 * over at [rolloverHour] (e.g. 04:00). Sleeps until the next boundary rather than
 * polling, so it wakes about once a day. [count] is the number of collections.
 */
@Composable
fun rememberDailyIndex(zone: ZoneId, rolloverHour: Int, count: Int): Int {
    var epochDay by remember(zone, rolloverHour) {
        mutableLongStateOf(wallpaperDay(ZonedDateTime.now(zone), rolloverHour))
    }
    LaunchedEffect(zone, rolloverHour) {
        while (true) {
            val now = ZonedDateTime.now(zone)
            epochDay = wallpaperDay(now, rolloverHour)
            delay(millisUntilRollover(now, rolloverHour))
        }
    }
    return dailyIndex(epochDay, count)
}

/** The local date a sun-day belongs to, treating [rolloverHour] as its start. */
private fun wallpaperDay(now: ZonedDateTime, rolloverHour: Int): Long =
    now.minusHours(rolloverHour.toLong()).toLocalDate().toEpochDay()

/** Millis from [now] to the next [rolloverHour] boundary, never less than a second. */
private fun millisUntilRollover(now: ZonedDateTime, rolloverHour: Int): Long {
    val todaysBoundary = now.toLocalDate().atTime(rolloverHour, 0).atZone(now.zone)
    val next = if (now.isBefore(todaysBoundary)) todaysBoundary else todaysBoundary.plusDays(1)
    return Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
}

/**
 * Load and cache the [SolarCollection] at [metadataUrl], retrying with backoff
 * until it lands. `value` is only ever advanced to a good collection, never
 * cleared: a fetch that can't complete keeps whatever the caller is already
 * showing (the previous day's collection) rather than blanking the wallpaper, so
 * a blip at the daily rollover is invisible and recovers on its own. It starts
 * null only before the very first collection loads. Collections are immutable
 * content, so a fetched one is cached for the process and never re-fetched.
 */
@Composable
fun rememberSolarCollection(metadataUrl: String?): State<SolarCollection?> =
    produceState<SolarCollection?>(initialValue = collectionCache[metadataUrl], metadataUrl) {
        val url = metadataUrl?.takeIf { it.isNotBlank() } ?: return@produceState
        collectionCache[url]?.let {
            value = it
            return@produceState
        }
        var backoffMillis = MIN_BACKOFF_MILLIS
        while (true) {
            val collection =
                runCatching { withContext(Dispatchers.IO) { fetchCollection(url) } }.getOrNull()
            if (collection != null) {
                collectionCache[url] = collection
                value = collection
                return@produceState
            }
            delay(backoffMillis)
            backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }

// Shared across every solar wallpaper on the surface; metadata bodies are tiny.
private val collectionCache = ConcurrentHashMap<String, SolarCollection>()
private val httpClient by lazy { OkHttpClient() }

// Backoff bounds for a failing metadata fetch, mirroring the dashboard poll.
private const val MIN_BACKOFF_MILLIS = 5_000L
private const val MAX_BACKOFF_MILLIS = 60_000L

private fun fetchCollection(metadataUrl: String): SolarCollection {
    val request = Request.Builder().url(metadataUrl).build()
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("metadata fetch failed: HTTP ${response.code}")
        }
        val body = response.body?.string() ?: throw IOException("metadata fetch: empty body")
        return parseSolarCollection(metadataUrl, JSONObject(body))
    }
}

/**
 * Parse a `metadata.json` body into a [SolarCollection]. Each frame's relative
 * `file` is resolved against [metadataUrl], so the metadata's own directory is
 * the image base — no separate base URL to configure or misread. Frames missing
 * a file or a sun position are skipped rather than failing the whole collection.
 */
fun parseSolarCollection(metadataUrl: String, body: JSONObject): SolarCollection {
    val base = metadataUrl.toHttpUrlOrNull()
    val frames = body.optJSONArray("frames") ?: return SolarCollection(emptyList())
    val parsed = (0 until frames.length()).mapNotNull { i ->
        val frame = frames.optJSONObject(i) ?: return@mapNotNull null
        val file = frame.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val elevation = frame.optDouble("elevation").takeUnless { it.isNaN() } ?: return@mapNotNull null
        val azimuth = frame.optDouble("azimuth").takeUnless { it.isNaN() } ?: return@mapNotNull null
        val url = base?.resolve(file)?.toString() ?: return@mapNotNull null
        SolarFrame(url = url, elevation = elevation, azimuth = azimuth)
    }
    return SolarCollection(parsed)
}
