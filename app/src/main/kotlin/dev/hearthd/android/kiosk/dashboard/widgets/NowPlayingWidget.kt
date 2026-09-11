package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.nowplaying.LocalNowPlaying
import dev.hearthd.android.kiosk.nowplaying.NowPlaying
import dev.hearthd.android.kiosk.nowplaying.NowPlayingCommand
import dev.hearthd.android.kiosk.nowplaying.NowPlayingHandle
import dev.hearthd.android.kiosk.nowplaying.Playback
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * What's playing, as a tile to drop in a grid. Everything it shows comes from
 * the device rather than from state — Snapcast today — so the template
 * configures nothing but whether the transport row is offered at all: a tile
 * somewhere out of reach can set `show_controls` false and stay a display.
 *
 * Which buttons appear is the source's call, not the template's. A stream
 * carrying a player that accepts commands offers the lot; a bare pipe offers
 * none, and the row disappears rather than showing buttons that quietly do
 * nothing.
 */
data class NowPlayingWidget(
    val showControls: Boolean,
) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        val handle = LocalNowPlaying.current
        val nowPlaying by handle.state.collectAsStateWithLifecycle()

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            val track = nowPlaying
            if (track == null) Idle() else Playing(track, handle)
        }
    }

    /** Nothing playing: say so quietly rather than leaving a blank tile. */
    @Composable
    private fun Idle() {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Nothing playing",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun Playing(nowPlaying: NowPlaying, handle: NowPlayingHandle) {
        val subtitle = listOfNotNull(nowPlaying.artist, nowPlaying.album).joinToString(" · ")
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            nowPlaying.artUri?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // Square, and never taller than the room left over once the
                    // text and transport row have taken theirs.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }
            nowPlaying.title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showControls && nowPlaying.canControl) {
                Spacer(Modifier.height(20.dp))
                Transport(nowPlaying, handle)
            }
        }
    }

    /**
     * Previous, play/pause, next — each present only if the source advertises it,
     * so this row can come out anywhere between empty and full.
     *
     * The pressed state shows immediately and is reconciled by the next update
     * from the source, or dropped after [OPTIMISTIC_TTL_MS] if none comes: the
     * same bargain the light widget strikes with the dashboard poll.
     */
    @Composable
    private fun Transport(nowPlaying: NowPlaying, handle: NowPlayingHandle) {
        var pressed by remember { mutableStateOf<Playback?>(null) }
        LaunchedEffect(pressed, nowPlaying.playback) {
            if (pressed == null) return@LaunchedEffect
            if (pressed == nowPlaying.playback) {
                pressed = null
                return@LaunchedEffect
            }
            delay(OPTIMISTIC_TTL_MS)
            pressed = null
        }
        val playback = pressed ?: nowPlaying.playback
        val playing = playback == Playback.PLAYING

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nowPlaying.canGoPrevious) {
                TransportButton(
                    diameter = SECONDARY_BUTTON,
                    onClick = { handle.send(NowPlayingCommand.PREVIOUS) },
                ) { color -> scale(scaleX = -1f, scaleY = 1f) { drawSkip(color) } }
            }
            // One button for both: which glyph it wears is the playback state,
            // and the source is asked to toggle whichever way it's facing.
            if (nowPlaying.canPlay || nowPlaying.canPause) {
                TransportButton(
                    diameter = PRIMARY_BUTTON,
                    onClick = {
                        pressed = if (playing) Playback.PAUSED else Playback.PLAYING
                        handle.send(NowPlayingCommand.PLAY_PAUSE)
                    },
                ) { color -> if (playing) drawPause(color) else drawPlay(color) }
            }
            if (nowPlaying.canGoNext) {
                TransportButton(
                    diameter = SECONDARY_BUTTON,
                    onClick = { handle.send(NowPlayingCommand.NEXT) },
                ) { color -> drawSkip(color) }
            }
        }
    }

    companion object {
        fun parse(obj: JSONObject) = NowPlayingWidget(
            showControls = obj.optBoolean("show_controls", true),
        )
    }
}

private const val OPTIMISTIC_TTL_MS = 5_000L
private val PRIMARY_BUTTON = 64.dp
private val SECONDARY_BUTTON = 52.dp

// How much of a button the glyph fills, leaving the rest as its margin.
private const val GLYPH_FRACTION = 0.42f

/**
 * A round transport button drawing [glyph] at [diameter]. The glyphs are drawn
 * rather than loaded: three shapes don't justify an icon dependency, and drawn
 * ones scale with the tile.
 */
@Composable
private fun TransportButton(
    diameter: Dp,
    onClick: () -> Unit,
    glyph: DrawScope.(Color) -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter * GLYPH_FRACTION)) { glyph(color) }
    }
}

/** A right-pointing triangle. */
private fun DrawScope.drawPlay(color: Color) {
    drawPath(
        Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.16f)
            lineTo(size.width * 0.84f, size.height * 0.5f)
            lineTo(size.width * 0.28f, size.height * 0.84f)
            close()
        },
        color,
    )
}

/** Two upright bars. */
private fun DrawScope.drawPause(color: Color) {
    val barWidth = size.width * 0.16f
    val barHeight = size.height * 0.68f
    val top = size.height * 0.16f
    drawRect(color, Offset(size.width * 0.26f, top), Size(barWidth, barHeight))
    drawRect(color, Offset(size.width * 0.58f, top), Size(barWidth, barHeight))
}

/** A right-pointing triangle against a bar; mirrored horizontally for previous. */
private fun DrawScope.drawSkip(color: Color) {
    drawPath(
        Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.2f)
            lineTo(size.width * 0.66f, size.height * 0.5f)
            lineTo(size.width * 0.22f, size.height * 0.8f)
            close()
        },
        color,
    )
    drawRect(
        color,
        Offset(size.width * 0.7f, size.height * 0.2f),
        Size(size.width * 0.12f, size.height * 0.6f),
    )
}
