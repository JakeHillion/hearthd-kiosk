package dev.hearthd.android.kiosk.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/**
 * The behaviour shared by the screensaver widgets. [child] is the base layer,
 * always composed and interactive. While [armed] and the surface has been idle
 * for [dwellMillis], [saver] fades in over the top; any touch dismisses it and
 * restarts the idle countdown, so a tap always reveals [child] for at least the
 * dwell before the saver returns. Disarming hides the saver at once. The first
 * arming (with no touch yet) saves immediately, so an already-dark room shows
 * the saver on load rather than after a dwell.
 *
 * Touches are observed in the [PointerEventPass.Initial] pass without being
 * consumed, so they still reach [child] — the dashboard underneath stays live
 * while awake. When the saver is showing it sits on top and swallows the waking
 * tap, so the touch that dismisses it doesn't also fall through to the child.
 */
@Composable
fun ScreensaverScaffold(
    armed: Boolean,
    dwellMillis: Long,
    saver: @Composable () -> Unit,
    child: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bumped on every touch; restarts the idle countdown below.
    var wakeTick by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(armed, wakeTick) {
        saving = false
        if (!armed) return@LaunchedEffect
        // A touch (wakeTick > 0) means the surface was just woken — hold the
        // child visible for the dwell before saving again. The first arming with
        // no touch yet falls through and saves immediately.
        if (wakeTick > 0) delay(dwellMillis)
        saving = true
    }

    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) wakeTick++
                }
            }
        },
    ) {
        child()

        AnimatedVisibility(
            visible = saving,
            enter = fadeIn(tween(SAVER_FADE_MILLIS)),
            exit = fadeOut(tween(SAVER_FADE_MILLIS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Full-bleed catcher: swallows the waking tap so it doesn't reach the
            // child, and keeps the surface awake if tapped again while saving.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { wakeTick++ } },
            ) {
                saver()
            }
        }
    }
}

private const val SAVER_FADE_MILLIS = 600
