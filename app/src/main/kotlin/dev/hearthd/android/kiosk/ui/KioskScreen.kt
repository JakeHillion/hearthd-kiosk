package dev.hearthd.android.kiosk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearthd.android.kiosk.R
import dev.hearthd.android.kiosk.dashboard.DashboardUiState
import dev.hearthd.android.kiosk.voice.VoicePhase
import dev.hearthd.android.kiosk.voice.VoiceUiState
import dev.hearthd.android.kiosk.wakeword.WakeWordDetection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The kiosk surface — what the device shows in normal operation. Deliberately
 * trivial for now (just a label); real content comes later.
 *
 * Kiosk mode hides the system bars, so operator controls can't live behind the
 * nav bar. A long-press in the top-right corner drops a tray — just a Settings
 * entry today, with room to grow. It's a hold rather than a swipe because the
 * hidden system bars are themselves revealed by an edge swipe; a deliberate
 * corner press won't be mistaken for that, or triggered by a stray touch.
 */
@Composable
fun KioskScreen(
    detections: SharedFlow<WakeWordDetection>,
    voiceUi: StateFlow<VoiceUiState>,
    micLevel: StateFlow<Float>,
    voiceEngaged: Boolean,
    dashboard: StateFlow<DashboardUiState>,
    onOpenSettings: () -> Unit,
) {
    var trayVisible by remember { mutableStateOf(false) }

    // The most recent detection to surface, and whether its popup is showing.
    // A fresh detection resets the auto-hide timer.
    var detection by remember { mutableStateOf<WakeWordDetection?>(null) }
    var popupVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        detections.collect {
            detection = it
            popupVisible = true
        }
    }
    LaunchedEffect(detection) {
        if (detection != null) {
            delay(POPUP_MILLIS)
            popupVisible = false
        }
    }

    val voice by voiceUi.collectAsStateWithLifecycle()
    val level by micLevel.collectAsStateWithLifecycle()
    val dash by dashboard.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        // The dashboard is the background surface. A template change resets its
        // tree (keyed by hash); a state-only refresh recomposes bound values in
        // place. With nothing configured yet, fall back to a plain label.
        val template = dash.template
        if (template != null) {
            key(dash.templateHash) {
                template.root.Render(dash.state, Modifier.fillMaxSize())
            }
        } else {
            Text(
                text = stringResource(R.string.kiosk_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Invisible hold target in the top-right corner. Nothing is drawn,
        // keeping the surface clean; a long-press opens the tray.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(72.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { trayVisible = true })
                },
        )

        // With voice engaged, the wake word flows straight into the voice sheet,
        // so the bare "detected" blob would be redundant — suppress it.
        WakeWordPopup(visible = popupVisible && !voiceEngaged, detection = detection)

        VoiceSheet(ui = voice, level = level)

        TopTray(
            visible = trayVisible,
            onDismiss = { trayVisible = false },
            onOpenSettings = {
                trayVisible = false
                onOpenSettings()
            },
        )
    }
}

private const val POPUP_MILLIS = 4000L

/**
 * The voice popup — a bottom sheet that rises when a turn starts and fills in as
 * it progresses (listening → transcript → reply), then dismisses itself. Live
 * audio feedback is the [Waveform] while listening; the transcript arrives whole
 * from Home Assistant when you stop speaking.
 */
@Composable
private fun BoxScope.VoiceSheet(ui: VoiceUiState, level: Float) {
    val visible = ui.phase != VoicePhase.HIDDEN

    // Scrim beneath the sheet.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(28.dp)) {
                Text(
                    text = phaseLabel(ui.phase),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (ui.transcript.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "“${ui.transcript}”",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                if (ui.response.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = ui.response,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                ui.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (ui.phase == VoicePhase.LISTENING) {
                    Spacer(Modifier.height(20.dp))
                    Waveform(level = level)
                }
            }
        }
    }
}

private fun phaseLabel(phase: VoicePhase): String = when (phase) {
    VoicePhase.HIDDEN -> ""
    VoicePhase.LISTENING -> "Listening…"
    VoicePhase.HEARD -> "Heard you"
    VoicePhase.THINKING -> "Thinking…"
    VoicePhase.RESPONDING -> "Home Assistant"
    VoicePhase.SPEAKING -> "Speaking…"
    VoicePhase.ERROR -> "Voice error"
}

private const val WAVE_BARS = 32

/**
 * A rolling bar visualiser: each tick shifts history left and appends the
 * current mic level, so louder speech pushes taller bars in from the right.
 */
@Composable
private fun Waveform(level: Float) {
    val latest = rememberUpdatedState(level)
    val bars = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(0f) } } }
    LaunchedEffect(Unit) {
        while (true) {
            bars.removeAt(0)
            bars.add(latest.value)
            delay(70)
        }
    }

    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        val n = bars.size
        val gap = 4.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        val minH = 3.dp.toPx()
        val midY = size.height / 2f
        bars.forEachIndexed { i, v ->
            val h = (size.height * v).coerceAtLeast(minH)
            val x = i * (barW + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, midY - h / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/**
 * A popup "blob" that rises from the bottom edge when a wake word is heard and
 * fades away after a few seconds. Shown only when voice is off; otherwise the
 * voice sheet takes over.
 */
@Composable
private fun BoxScope.WakeWordPopup(visible: Boolean, detection: WakeWordDetection?) {
    // Keep the last detection so the label survives the exit animation, when
    // `detection` may already have been cleared.
    var shown by remember { mutableStateOf<WakeWordDetection?>(null) }
    if (detection != null) shown = detection

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 56.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PulseDot()
                Column {
                    Text(
                        text = stringResource(R.string.wake_detected),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = shown?.model?.label ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/** A softly pulsing dot, a small "I'm listening" flourish beside the label. */
@Composable
private fun PulseDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )
    Box(
        modifier = Modifier
            .size(14.dp)
            .scale(scale)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

/**
 * A tray that drops in from the top edge, over a tap-to-dismiss scrim. Material3
 * only ships a bottom sheet, so this is a minimal top-anchored equivalent.
 */
@Composable
private fun BoxScope.TopTray(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Scrim: fades in beneath the tray and dismisses on tap.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
    }

    // The tray itself, sliding down from above the top edge.
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        Surface(
            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.kiosk_open_settings))
                }
            }
        }
    }
}
