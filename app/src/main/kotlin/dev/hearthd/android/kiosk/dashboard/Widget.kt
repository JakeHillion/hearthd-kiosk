package dev.hearthd.android.kiosk.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.hearthd.android.kiosk.dashboard.widgets.CarouselWidget
import dev.hearthd.android.kiosk.dashboard.widgets.ClockWidget
import dev.hearthd.android.kiosk.dashboard.widgets.EnvironmentWidget
import dev.hearthd.android.kiosk.dashboard.widgets.GridWidget
import dev.hearthd.android.kiosk.dashboard.widgets.LightGroupWidget
import dev.hearthd.android.kiosk.dashboard.widgets.NowPlayingWidget
import dev.hearthd.android.kiosk.dashboard.widgets.PhotoScreensaverWidget
import dev.hearthd.android.kiosk.dashboard.widgets.WeatherWidget
import org.json.JSONObject

/**
 * A node in a dashboard template tree. Each concrete widget lives in its own
 * file under [dashboard.widgets] and owns everything about itself: its parsed
 * fields, its [parse] factory, and how it draws via [Render]. Adding a widget is
 * one new file plus one arm in [parseWidget] — nothing else changes.
 *
 * [Render] reads live values out of [state] on each recomposition, so a
 * state-only refresh never rebuilds the tree.
 *
 * Not sealed: each widget lives in its own file under [dashboard.widgets], and a
 * sealed hierarchy can't span packages. Nothing switches over the widget types —
 * parsing dispatches on the type string, rendering is polymorphic — so sealing
 * would buy nothing.
 */
interface Widget {
    @Composable
    fun Render(state: JSONObject, modifier: Modifier = Modifier)
}

/** A parsed template: a schema [version] and the [root] widget to render. */
data class Template(val version: Int, val root: Widget) {
    companion object {
        fun fromJson(text: String): Template {
            val obj = JSONObject(text)
            return Template(
                version = obj.optInt("version", 1),
                root = parseWidget(obj.getJSONObject("root")),
            )
        }
    }
}

/** The single dispatch point that turns a `{"type": …}` object into a [Widget]. */
fun parseWidget(obj: JSONObject): Widget = when (val type = obj.optString("type")) {
    "carousel" -> CarouselWidget.parse(obj)
    "clock" -> ClockWidget.parse(obj)
    "environment" -> EnvironmentWidget.parse(obj)
    "grid" -> GridWidget.parse(obj)
    "weather" -> WeatherWidget.parse(obj)
    "light_group" -> LightGroupWidget.parse(obj)
    "now_playing" -> NowPlayingWidget.parse(obj)
    "photo_screensaver" -> PhotoScreensaverWidget.parse(obj)
    else -> UnknownWidget(type)
}

/**
 * A forward-compatibility placeholder for a widget type this build doesn't know.
 * An older kiosk shows a small marker instead of crashing on a newer template.
 */
data class UnknownWidget(val type: String) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        Text(
            text = "Unsupported widget: ${type.ifBlank { "?" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}
