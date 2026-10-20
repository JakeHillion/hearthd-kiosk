package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hearthd.android.kiosk.dashboard.Binding
import dev.hearthd.android.kiosk.dashboard.ScreensaverScaffold
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.dashboard.parseWidget
import dev.hearthd.android.kiosk.dashboard.rememberAmbientLightLux
import dev.hearthd.android.kiosk.dashboard.resolveDouble
import dev.hearthd.android.kiosk.dashboard.resolveString
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A night-time screensaver: when the room goes dark it dims the screen to a faint
 * clock; a touch reveals [child] (the dashboard) for [dwellSeconds] before the
 * clock returns. It wraps its child, so it can sit above another screensaver —
 * darkness takes over from whatever is layered below.
 *
 * The trigger is the ambient-light sensor: the clock comes on once the room
 * falls below [thresholdLux] and clears the moment it brightens back to that
 * threshold, so a lit room returns to the child on its own without a touch. A
 * small deadband on the re-arm (dark) side keeps a reading hovering at the
 * threshold from flickering the clock. On a device with no light sensor it never
 * arms and the child shows as normal.
 *
 * [lux], when set, is a live slot read in place of the device's own sensor — for
 * a room with a better-placed light sensor (say, on a PIR) reported via state.
 * The device sensor then stays unregistered. While the slot is absent or
 * non-numeric the clock holds its current state, as with a sensor that hasn't
 * reported yet.
 *
 * [timezone] is a live slot (an IANA id like `Europe/London`); a blank or
 * unknown zone falls back to the device's own, matching the clock widget.
 */
data class AmbientScreensaverWidget(
    val child: Widget,
    val thresholdLux: Float,
    val dwellSeconds: Long,
    val timezone: Binding,
    val lux: Binding?,
) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        val current = if (lux != null) {
            lux.resolveDouble(state)?.toFloat()
        } else {
            rememberAmbientLightLux().value
        }
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(current) {
            val reading = current ?: return@LaunchedEffect
            armed = when {
                // Show the clock once the room is properly dark.
                !armed && reading < thresholdLux * REARM_FRACTION -> true
                // Clear it as soon as the room brightens back to the threshold, so
                // it returns to the child on its own — no touch needed. The gap
                // down to REARM_FRACTION is the only deadband, there to stop a
                // reading sitting at the threshold from flickering the clock.
                armed && reading >= thresholdLux -> false
                else -> armed
            }
        }

        ScreensaverScaffold(
            armed = armed,
            dwellMillis = dwellSeconds * 1_000L,
            saver = { DimClock(timezone, state) },
            child = { child.Render(state, Modifier.fillMaxSize()) },
            modifier = modifier.fillMaxSize(),
        )
    }

    companion object {
        // Re-arm (show the clock again) only once the room falls back below this
        // fraction of the clear threshold, so it doesn't flicker at the boundary.
        private const val REARM_FRACTION = 0.8f

        fun parse(obj: JSONObject) = AmbientScreensaverWidget(
            child = parseWidget(obj.getJSONObject("child")),
            thresholdLux = obj.optDouble("threshold_lux", 8.0).toFloat(),
            dwellSeconds = obj.optLong("dwell_seconds", 8L).coerceAtLeast(1L),
            timezone = Binding.of(obj.opt("timezone")),
            lux = obj.opt("lux")?.let { Binding.of(it) },
        )
    }
}

/** The faint HH:mm on black shown while the room is dark. */
@Composable
private fun DimClock(timezone: Binding, state: JSONObject) {
    val zoneId = timezone.resolveString(state)
    val zone = remember(zoneId) {
        runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    }
    val format = remember { DateTimeFormatter.ofPattern("HH:mm") }

    // Setting an equal String back into state is a no-op, so a minute-long
    // stretch of identical values recomposes nothing; a 10s tick is plenty to
    // catch the minute rolling over without waking every second.
    var text by remember(zone) { mutableStateOf(ZonedDateTime.now(zone).format(format)) }
    LaunchedEffect(zone) {
        while (true) {
            text = ZonedDateTime.now(zone).format(format)
            delay(10_000)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // Auto-size to the largest that fits the width: the clock is faint by
        // design, so filling the display is what keeps it readable across a
        // dark room. A single line of HH:mm, so width is the limiting bound.
        BasicText(
            text = text,
            style = MaterialTheme.typography.displayLarge.copy(
                color = Color.White.copy(alpha = 0.35f),
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 48.sp,
                maxFontSize = 1_000.sp,
                stepSize = 2.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
    }
}
