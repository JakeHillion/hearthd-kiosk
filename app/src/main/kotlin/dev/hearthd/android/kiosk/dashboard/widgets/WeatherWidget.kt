package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.hearthd.android.kiosk.dashboard.Binding
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.dashboard.resolveObject
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Current-conditions weather. Almost everything is app-controlled: the template
 * only supplies [source], a slot pointing at a state object shaped like
 * `{"temp_c": 12.4, "condition": "partly_cloudy"}`. The widget owns how that's
 * drawn. The icon is a placeholder glyph for now — visual polish comes later.
 */
data class WeatherWidget(
    val source: Binding,
) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        val data = source.resolveObject(state)
        val tempC = data?.optDouble("temp_c")?.takeUnless { it.isNaN() }
        val condition = data?.optString("condition").orEmpty()

        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = conditionGlyph(condition),
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = tempC?.let { "${it.roundToInt()}°" } ?: "—",
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
            )
            if (condition.isNotBlank()) {
                Text(
                    text = condition.replace('_', ' '),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    companion object {
        fun parse(obj: JSONObject) = WeatherWidget(
            source = Binding.of(obj.opt("source")),
        )
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
