package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 *
 * The whole column scales to fit the space it's actually given (see
 * [environmentScale]) rather than using the fixed type scale. Because a
 * [dev.hearthd.android.kiosk.dashboard.widgets.GridWidget] hands every cell the
 * same constraints, all sibling environment widgets in one grid scale
 * identically. Every line is pinned to one row so a too-small cell clips rather
 * than wrapping across two lines.
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

        val values = buildList<Pair<String, String>> {
            if (tempC != null) add(tempC.toString() + "°" to "🌡 Temperature")
            if (humidityPct != null) add(humidityPct.toString() + "%" to "💧 Humidity")
        }
        val nameText = name.takeIf { it.isNotBlank() }

        val nameStyle = MaterialTheme.typography.titleLarge
        val valueStyle = MaterialTheme.typography.displayMedium
        val labelStyle = MaterialTheme.typography.titleMedium
        val dashStyle = MaterialTheme.typography.displayMedium

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(28.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val bounded =
                    constraints.maxWidth != Constraints.Infinity &&
                        constraints.maxHeight != Constraints.Infinity

                // The whole column, including its padding and intra-column spacing,
                // scales by one factor so a tight cell shrinks proportionally rather
                // than letting the fixed chrome crowd out the readings. When keeping
                // the name heading would force the readings below a comfortable size,
                // drop the heading (secondary chrome) so both readings stay readable.
                var showName = nameText != null
                val scale = if (bounded) {
                    val width = constraints.maxWidth.toFloat()
                    val height = constraints.maxHeight.toFloat()
                    var s = environmentScale(
                        width, height, nameText, nameStyle,
                        values, valueStyle, labelStyle,
                        "—".takeIf { values.isEmpty() }, dashStyle,
                    )
                    if (showName && values.isNotEmpty() && s < DROP_NAME_BELOW) {
                        showName = false
                        s = environmentScale(
                            width, height, null, nameStyle,
                            values, valueStyle, labelStyle,
                            null, dashStyle,
                        )
                    }
                    s
                } else {
                    1f
                }

                Column(
                    modifier = Modifier.fillMaxSize().padding(PADDING * scale),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SPACING * scale, Alignment.CenterVertically),
                ) {
                    if (showName) {
                        Text(
                            text = nameText.orEmpty(),
                            style = nameStyle.scaled(scale),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    if (values.isEmpty()) {
                        Text(
                            text = "—",
                            style = dashStyle.scaled(scale),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    } else {
                        tempC?.let {
                            Reading("🌡", "$it°", "Temperature", temperatureColor(it), scale)
                        }
                        humidityPct?.let {
                            Reading("💧", "$it%", "Humidity", humidityColor(it), scale)
                        }
                    }
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
private fun Reading(glyph: String, value: String, label: String, color: Color, scale: Float) {
    val valueStyle = MaterialTheme.typography.displayMedium
    val labelStyle = MaterialTheme.typography.titleMedium
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = valueStyle.scaled(scale),
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = "$glyph $label",
            style = labelStyle.scaled(scale),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * Compute the scale factor that fits this widget's column into the available
 * [availWidth]×[availHeight] (px). It's the smallest that fits both the whole
 * column's height — optional [nameText], one block of value+label per [readings],
 * or the [dashText] when there are none, with [SPACING] between — and the widest
 * single line's measured width, so neither capacity can overflow.
 *
 * The scale applies to the text, the padding and the intra-column spacing alike
 * (see [Render]), so the whole column shrinks proportionally. The fit therefore
 * folds the [PADDING] into both the height and width budgets rather than
 * reserving it as fixed overhead.
 */
@Composable
private fun environmentScale(
    availWidth: Float,
    availHeight: Float,
    nameText: String?,
    nameStyle: TextStyle,
    readings: List<Pair<String, String>>,
    valueStyle: TextStyle,
    labelStyle: TextStyle,
    dashText: String?,
    dashStyle: TextStyle,
): Float {
    val density = LocalDensity.current
    val padPx = with(density) { PADDING.toPx() }
    val spacingPx = with(density) { SPACING.toPx() }

    val measurer = rememberTextMeasurer()
    // Measure each line's true laid-out height (incl. Android's font padding),
    // not the nominal lineHeight, so the sum reflects what's actually rendered.
    fun lineHeight(text: String, style: TextStyle): Float =
        measurer.measure(AnnotatedString(text), style = style).size.height.toFloat()
    fun lineWidth(text: String, style: TextStyle): Float =
        measurer.measure(AnnotatedString(text), style = style).size.width.toFloat()

    // Stacked height at scale 1: line boxes plus the spacing between blocks,
    // with the scale-proportional page padding folded into the budget.
    var content = 0f
    if (nameText != null) content += lineHeight(nameText, nameStyle)
    if (readings.isNotEmpty()) {
        readings.forEach { (value, label) ->
            content += lineHeight(value, valueStyle) + lineHeight(label, labelStyle)
        }
    } else if (dashText != null) {
        content += lineHeight(dashText, dashStyle)
    }
    val blockCount =
        (if (nameText != null) 1 else 0) + (if (readings.isNotEmpty()) readings.size else 1)
    content += spacingPx * (blockCount - 1).coerceAtLeast(0)

    val heightFit =
        if (content + 2 * padPx > 0f) availHeight / (content + 2 * padPx)
        else Float.POSITIVE_INFINITY

    // The widest single line sets the width limit; horizontal padding scales too.
    fun widthFit(line: Float): Float {
        val w = line + 2 * padPx
        return if (w > 0f) availWidth / w else Float.POSITIVE_INFINITY
    }
    var widthFitValue = Float.POSITIVE_INFINITY
    if (nameText != null) widthFitValue = minOf(widthFitValue, widthFit(lineWidth(nameText, nameStyle)))
    readings.forEach { (value, label) ->
        widthFitValue = minOf(widthFitValue, widthFit(lineWidth(value, valueStyle)))
        widthFitValue = minOf(widthFitValue, widthFit(lineWidth(label, labelStyle)))
    }
    if (readings.isEmpty() && dashText != null) {
        widthFitValue = minOf(widthFitValue, widthFit(lineWidth(dashText, dashStyle)))
    }

    return heightFit.coerceAtMost(widthFitValue).coerceIn(MIN_SCALE, MAX_SCALE)
}

/** Scale a style's font and line height together so the column stays proportional. */
private fun TextStyle.scaled(scale: Float): TextStyle =
    copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

private val PADDING = 24.dp
private val SPACING = 20.dp

/** Keep the name heading only while it doesn't crowd the readings below this scale. */
private const val DROP_NAME_BELOW = 0.8f

/** The smallest legible scale before content is dropped or clipped. */
private const val MIN_SCALE = 0.45f

/** Never grow past the design size — a roomy cell just renders at scale 1. */
private const val MAX_SCALE = 1f

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
