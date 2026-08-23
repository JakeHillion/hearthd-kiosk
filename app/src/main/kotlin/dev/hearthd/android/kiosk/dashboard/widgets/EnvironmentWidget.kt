package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hearthd.android.kiosk.dashboard.Binding
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.dashboard.resolveDouble
import org.json.JSONObject

/**
 * Room environment: an optional [name] heading over an optional [temperature] and
 * an optional [humidity], each reading a slot fed from state (a bare number, e.g.
 * `{"$": "sensors.lounge.temp_c"}`). The two are independent — the widget shows
 * whichever resolve to a number, and a muted dash when neither does, so a template
 * can carry just one.
 *
 * Each reading is tinted by its own value so the room reads at a glance rather
 * than needing the number parsed: temperature runs cool-blue → warm-red across a
 * living-space comfort band, humidity dry-amber → wet-blue. The tint is on the
 * value itself over a neutral surface, keeping it legible while the colour still
 * carries the signal.
 */
data class EnvironmentWidget(
    val name: String,
    val temperature: Binding?,
    val humidity: Binding?,
) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        val tempC = temperature?.resolveDouble(state)
        val humidityPct = humidity?.resolveDouble(state)

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(28.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                if (name.isNotBlank()) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (tempC == null && humidityPct == null) {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                tempC?.let {
                    Reading("🌡", "$it°", "Temperature", temperatureColor(it))
                }
                humidityPct?.let {
                    Reading("💧", "$it%", "Humidity", humidityColor(it))
                }
            }
        }
    }

    companion object {
        fun parse(obj: JSONObject) = EnvironmentWidget(
            name = obj.optString("name"),
            temperature = obj.opt("temperature")?.let { Binding.of(it) },
            humidity = obj.opt("humidity")?.let { Binding.of(it) },
        )
    }
}

/** One tinted reading: the coloured value with a muted glyph + label beneath it. */
@Composable
private fun Reading(glyph: String, value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.displayMedium,
            color = color,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "$glyph $label",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Cool-blue when cold, through green in the comfort band, to warm-red when hot (°C). */
private fun temperatureColor(c: Double): Color = colorForValue(
    c,
    10.0 to Color(0xFF42A5F5), // cold — blue
    18.0 to Color(0xFF26C6DA), // cool — cyan
    21.0 to Color(0xFF66BB6A), // comfortable — green
    25.0 to Color(0xFFFFA726), // warm — orange
    30.0 to Color(0xFFEF5350), // hot — red
)

/** Dry-amber when parched, green through the comfortable band, to wet-blue when humid (%). */
private fun humidityColor(pct: Double): Color = colorForValue(
    pct,
    20.0 to Color(0xFFFFB74D), // dry — amber
    40.0 to Color(0xFF66BB6A), // comfortable — green
    60.0 to Color(0xFF26C6DA), // muggy — cyan
    80.0 to Color(0xFF42A5F5), // humid — blue
)

/**
 * Piecewise-linear colour ramp: [value] is placed between the two nearest
 * [stops] (ascending by threshold) and their colours are lerped. Values below
 * the first or above the last clamp to the end colour.
 */
private fun colorForValue(value: Double, vararg stops: Pair<Double, Color>): Color {
    if (value <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (lo, loColor) = stops[i - 1]
        val (hi, hiColor) = stops[i]
        if (value <= hi) {
            val t = ((value - lo) / (hi - lo)).toFloat()
            return lerp(loColor, hiColor, t)
        }
    }
    return stops.last().second
}
