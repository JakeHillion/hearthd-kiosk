package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import dev.hearthd.android.kiosk.dashboard.Binding
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.dashboard.parseWidget
import dev.hearthd.android.kiosk.screen.LocalScreenPower
import dev.hearthd.android.kiosk.screen.ScreenPowerRequest
import org.json.JSONObject

/**
 * Hands the device's display to the dashboard: while this is mounted, [on]
 * decides whether the screen is lit, and a slot bound to state makes that a
 * remote switch.
 *
 * Draws nothing of its own — it wraps [child] and renders it unchanged. The
 * wrapping is what makes it reliable rather than decorative: at the root of the
 * template it is mounted for as long as the template is, whereas a leaf dropped
 * into a carousel page would quietly stop driving the screen the moment that
 * page scrolled away.
 *
 * It also only *declares*. Resolving [on] against each new poll, and everything
 * to do with the hardware, belongs to the controller behind [LocalScreenPower],
 * which unlike this composition is still running once the panel is dark.
 *
 * Releasing on dispose is the safety property that matters: a template that
 * drops the widget, or a dashboard switched off, gives the display back to the
 * device rather than leaving it stuck dark with nothing left to turn it on.
 */
data class ScreenPowerWidget(
    val child: Widget,
    val on: Binding,
    val wakeSeconds: Long,
) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        val power = LocalScreenPower.current
        DisposableEffect(power, on, wakeSeconds) {
            power.request(ScreenPowerRequest(on = on, wakeSeconds = wakeSeconds))
            onDispose { power.request(null) }
        }
        child.Render(state, modifier)
    }

    companion object {
        fun parse(obj: JSONObject) = ScreenPowerWidget(
            child = parseWidget(obj.getJSONObject("child")),
            on = Binding.of(obj.opt("on")),
            // How long a person who wakes the panel themselves keeps it before
            // the remote "off" takes it back.
            wakeSeconds = obj.optLong("wake_seconds", 120L).coerceAtLeast(0L),
        )
    }
}
