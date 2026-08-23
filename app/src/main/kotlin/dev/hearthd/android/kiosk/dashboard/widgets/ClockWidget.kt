package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.hearthd.android.kiosk.dashboard.Binding
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.dashboard.resolveString
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A wall clock. [showSeconds] is a static template choice; [timezone] is a live
 * slot (an IANA zone id like `Europe/London`) so the same template can be
 * pointed at a different zone from state without re-rendering the tree. A blank
 * or unknown zone falls back to the device's own.
 */
data class ClockWidget(
    val showSeconds: Boolean,
    val timezone: Binding,
) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        val zoneId = timezone.resolveString(state)
        val zone = remember(zoneId) {
            runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
        }
        val timePattern = if (showSeconds) "HH:mm:ss" else "HH:mm"
        val timeFormat = remember(timePattern) { DateTimeFormatter.ofPattern(timePattern) }
        val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM") }

        var now by remember(zone) { mutableStateOf(ZonedDateTime.now(zone)) }
        LaunchedEffect(zone) {
            while (true) {
                now = ZonedDateTime.now(zone)
                delay(1_000)
            }
        }

        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = now.format(timeFormat),
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = now.format(dateFormat),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    companion object {
        fun parse(obj: JSONObject) = ClockWidget(
            showSeconds = obj.optBoolean("show_seconds", false),
            timezone = Binding.of(obj.opt("timezone")),
        )
    }
}
