package dev.hearthd.android.kiosk.screen

import androidx.compose.runtime.staticCompositionLocalOf
import dev.hearthd.android.kiosk.dashboard.Binding

/**
 * What a `screen_power` widget asks of the kiosk: resolve [on] against whatever
 * state arrives next, and hold the panel awake for [wakeSeconds] after someone
 * touches it before going dark again.
 *
 * The widget hands over the [Binding] itself rather than a resolved boolean, and
 * that is the whole point: once the screen is off the composition can't run, so
 * something outside the UI has to keep resolving the slot against each new poll.
 * Passing the binding also makes a literal and a `{"$": …}` slot the same case
 * here — [Binding.resolve] already knows the difference.
 */
data class ScreenPowerRequest(
    val on: Binding,
    val wakeSeconds: Long,
)

/** What a widget needs of the screen: a way to say who's driving it. */
interface ScreenPowerHandle {
    /** Take over the display, or pass null to hand it back to the system. */
    fun request(request: ScreenPowerRequest?)
}

/** The no-op used in previews and wherever the host hasn't provided a controller. */
object NoScreenPower : ScreenPowerHandle {
    override fun request(request: ScreenPowerRequest?) {}
}

/**
 * Provided by the host so a widget can drive the panel without threading
 * callbacks through the template tree — the same arrangement as
 * `LocalLightCommander` and `LocalNowPlaying`, and for the same reason: the
 * display is a fact about the device, not a value carried by `/state`.
 */
val LocalScreenPower = staticCompositionLocalOf<ScreenPowerHandle> { NoScreenPower }
