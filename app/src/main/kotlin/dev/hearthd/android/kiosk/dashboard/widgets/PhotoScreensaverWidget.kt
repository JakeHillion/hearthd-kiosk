package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.hearthd.android.kiosk.dashboard.Binding
import dev.hearthd.android.kiosk.dashboard.ScreensaverScaffold
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.dashboard.parseWidget
import dev.hearthd.android.kiosk.dashboard.resolveObject
import dev.hearthd.android.kiosk.dashboard.resolveString
import dev.hearthd.android.kiosk.nowplaying.LocalNowPlaying
import dev.hearthd.android.kiosk.nowplaying.NowPlaying
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * A digital photo frame that doubles as the screensaver: the resting state is a
 * frame of [photos] with the date, time, and weather overlaid; a touch reveals
 * [child] (the dashboard) for [dwellSeconds] before the photos return. It wraps
 * its child so it can nest under the ambient screensaver — the photos rest by
 * day, and a dark room lets the ambient clock take over above it.
 *
 * Two [mode]s share this frame, differing only in how the image is chosen:
 *  - `slideshow` (default): [photos] is a list of image URLs, cycled every
 *    [rotateSeconds].
 *  - `solar`: [photos] is a list of `metadata.json` URLs, one per dynamic
 *    wallpaper collection. Each day at [rolloverHour] local a collection is
 *    picked at random (held until the next rollover), and within it the frame
 *    tracking the live sun position from [sun] is shown — a wallpaper that moves
 *    with the real sun. [rotateSeconds] is unused here.
 *
 * Everything dynamic comes from state: [photos] is a slot, [weather] the same
 * `{temp_c, condition}` object the weather widget reads, [timezone] a live IANA
 * id, and [sun] a `{elevation, azimuth}` object (degrees). The overlay's layout
 * and formatting are fixed. With no photos configured it falls back to the
 * overlay on black, still dimming the dashboard while idle.
 *
 * What's playing sits opposite the clock, bottom-right, and comes from the
 * device rather than from state — Snapcast today, via `LocalNowPlaying`. It's on
 * unless [showNowPlaying] turns it off, and shows nothing at all when nothing is
 * playing, so a silent house is just the photo.
 */
data class PhotoScreensaverWidget(
    val child: Widget,
    val photos: Binding,
    val timezone: Binding,
    val weather: Binding,
    val sun: Binding,
    val mode: String,
    val showNowPlaying: Boolean,
    val dwellSeconds: Long,
    val rotateSeconds: Long,
    val rolloverHour: Int,
) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        // Watch from here rather than inside the frame: the frame exists only
        // while the saver is showing, so subscribing there would drop the
        // source's link on every touch and reconnect it after the dwell.
        val nowPlaying = if (showNowPlaying) {
            LocalNowPlaying.current.state.collectAsStateWithLifecycle().value
        } else {
            null
        }

        ScreensaverScaffold(
            // The photo frame is the resting state, so it's always armed; the
            // scaffold shows it whenever the surface is idle.
            armed = true,
            dwellMillis = dwellSeconds * 1_000L,
            saver = { PhotoFrame(state, nowPlaying) },
            child = { child.Render(state, Modifier.fillMaxSize()) },
            modifier = modifier.fillMaxSize(),
        )
    }

    @Composable
    private fun PhotoFrame(state: JSONObject, nowPlaying: NowPlaying?) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (mode == MODE_SOLAR) SolarLayer(state) else SlideshowLayer(state)

            // A soft bottom scrim keeps the white overlay legible over a bright
            // photo without darkening the whole frame.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.65f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )

            Overlay(state)
            nowPlaying?.let {
                NowPlayingOverlay(
                    it,
                    Modifier.align(Alignment.BottomEnd).padding(32.dp),
                )
            }
        }
    }

    /** The default frame: cycle through the image URLs in [photos]. */
    @Composable
    private fun SlideshowLayer(state: JSONObject) {
        val urls = resolveUrls(state)
        if (urls.isEmpty()) return

        var index by remember(urls) { mutableIntStateOf(0) }
        LaunchedEffect(urls, rotateSeconds) {
            if (urls.size <= 1) return@LaunchedEffect
            while (true) {
                delay(rotateSeconds * 1_000L)
                index = (index + 1) % urls.size
            }
        }
        Crossfade(
            targetState = index.coerceIn(urls.indices),
            animationSpec = tween(PHOTO_FADE_MILLIS),
            label = "photo",
        ) { i ->
            PhotoImage(urls[i])
        }
    }

    /**
     * The solar frame: pick today's collection from [photos] (metadata URLs),
     * then show the frame nearest the live sun. Falls back to the collection's
     * first frame until a sun position arrives, and to bare black on an empty or
     * unreachable collection — either way the overlay still draws over the top.
     */
    @Composable
    private fun SolarLayer(state: JSONObject) {
        val metadataUrls = resolveUrls(state)
        if (metadataUrls.isEmpty()) return

        val zoneId = timezone.resolveString(state)
        val zone = remember(zoneId) {
            runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
        }
        val index = rememberDailyIndex(zone, rolloverHour, metadataUrls.size)
        val collection by rememberSolarCollection(metadataUrls[index.coerceIn(metadataUrls.indices)])
        val frames = collection?.frames.orEmpty()
        if (frames.isEmpty()) return

        val sunData = sun.resolveObject(state)
        val elevation = sunData?.optDouble("elevation")?.takeUnless { it.isNaN() }
        val azimuth = sunData?.optDouble("azimuth")?.takeUnless { it.isNaN() }
        val frame = if (elevation != null && azimuth != null) {
            frames.nearestTo(elevation, azimuth)
        } else {
            frames.first()
        }

        SolarImage(frame?.url)
    }

    /**
     * Show [desiredUrl] with a straight swap — no crossfade, since the frames of
     * a solar day are deliberately quiet transitions — but never blank the frame
     * to get there. The last image known to have loaded stays on top until the
     * desired one has actually loaded underneath; a failed load simply leaves the
     * old image in place. Both layers share Coil's cache, so the top one costs
     * nothing once it catches up.
     */
    @Composable
    private fun SolarImage(desiredUrl: String?) {
        var committedUrl by remember { mutableStateOf<String?>(null) }
        Box(modifier = Modifier.fillMaxSize()) {
            // Underneath: laid out and drawn, so it truly loads. Commit only on
            // success, which is what promotes it to the visible layer above.
            if (desiredUrl != null) {
                AsyncImage(
                    model = desiredUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Success) committedUrl = desiredUrl
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            committedUrl?.let { PhotoImage(it) }
        }
    }

    /** One full-bleed, cropped image — the shared look of both frames. */
    @Composable
    private fun PhotoImage(url: String) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }

    /** Date and time bottom-left, weather to its right. Fixed layout. */
    @Composable
    private fun BoxScope.Overlay(state: JSONObject) {
        val zoneId = timezone.resolveString(state)
        val zone = remember(zoneId) {
            runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
        }
        val dateFormat = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
        val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
        var now by remember(zone) { mutableStateOf(ZonedDateTime.now(zone)) }
        LaunchedEffect(zone) {
            while (true) {
                now = ZonedDateTime.now(zone)
                delay(10_000)
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(32.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column {
                Text(
                    text = now.format(dateFormat),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    text = now.format(timeFormat),
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                )
            }

            val data = weather.resolveObject(state)
            val tempC = data?.optDouble("temp_c")?.takeUnless { it.isNaN() }
            val condition = data?.optString("condition").orEmpty()
            if (tempC != null || condition.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(conditionGlyph(condition), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = tempC?.let { "${it.roundToInt()}°" } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )
                }
            }
        }
    }

    private fun resolveUrls(state: JSONObject): List<String> {
        val array = photos.resolve(state) as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotBlank() }
        }
    }

    companion object {
        const val MODE_SOLAR = "solar"

        fun parse(obj: JSONObject) = PhotoScreensaverWidget(
            child = parseWidget(obj.getJSONObject("child")),
            photos = Binding.of(obj.opt("photos")),
            timezone = Binding.of(obj.opt("timezone")),
            weather = Binding.of(obj.opt("weather")),
            sun = Binding.of(obj.opt("sun")),
            mode = obj.optString("mode").ifBlank { "slideshow" },
            showNowPlaying = obj.optBoolean("show_now_playing", true),
            dwellSeconds = obj.optLong("dwell_seconds", 20L).coerceAtLeast(1L),
            rotateSeconds = obj.optLong("rotate_seconds", 30L).coerceAtLeast(1L),
            rolloverHour = obj.optInt("rollover_hour", 4).coerceIn(0, 23),
        )
    }
}

private const val PHOTO_FADE_MILLIS = 1_000
private val NOW_PLAYING_ART_SIZE = 72.dp
private val NOW_PLAYING_TEXT_WIDTH = 280.dp

/**
 * What's playing, opposite the clock. The text is right-aligned and the cover
 * hugs the corner, so the block reads outward from the frame the way the date
 * and weather do on the other side. The text is bounded and clipped to one line
 * each: a long title must not run back across the photo into the clock.
 */
@Composable
private fun NowPlayingOverlay(nowPlaying: NowPlaying, modifier: Modifier = Modifier) {
    val subtitle = listOfNotNull(nowPlaying.artist, nowPlaying.album).joinToString(" · ")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = NOW_PLAYING_TEXT_WIDTH),
        ) {
            nowPlaying.title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        nowPlaying.artUri?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(NOW_PLAYING_ART_SIZE).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

/** Placeholder icon: map a condition string to an emoji until real icons land. */
private fun conditionGlyph(condition: String): String = when (condition.lowercase()) {
    "clear", "sunny", "clear_night" -> "☀️"
    "partly_cloudy", "partlycloudy" -> "⛅"
    "cloudy", "overcast" -> "☁️"
    "rain", "rainy", "showers" -> "🌧️"
    "snow", "snowy" -> "❄️"
    "fog", "foggy", "mist" -> "🌫️"
    "thunderstorm", "storm" -> "⛈️"
    else -> "🌡️"
}
