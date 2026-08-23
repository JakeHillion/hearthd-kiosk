package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.hearthd.android.kiosk.dashboard.Binding
import dev.hearthd.android.kiosk.dashboard.LightCommander
import dev.hearthd.android.kiosk.dashboard.LocalLightCommander
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.dashboard.resolveObject
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** One light in a [LightGroupWidget]. Capabilities are static (baked into the template). */
data class LightConfig(
    val entityId: String,
    val name: String,
    val endpoint: Int,
    val dimmable: Boolean,
    val color: Boolean,
)

/**
 * A group of lights with two interactions. Tapping the tile toggles the group:
 * everything off if any are on, otherwise the [onTargets] subset on (all, if
 * [onTargets] is empty). The expand affordance opens a full-screen modal — a
 * vertical pager of per-light controls (and a colour page once any light
 * supports it).
 *
 * Read state comes from [source] (the `lights` map in `/state`, keyed by
 * entity_id); writes go straight to hearthd via [LocalLightCommander]. Because a
 * command doesn't return the new state, each action is applied to a local
 * optimistic overlay that reconciles against the next poll.
 */
data class LightGroupWidget(
    val title: String,
    val source: Binding,
    val onTargets: List<String>,
    val lights: List<LightConfig>,
) : Widget {

    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        if (lights.isEmpty()) return

        val commander = LocalLightCommander.current
        val lightsState = source.resolveObject(state) ?: JSONObject()
        val overrides = remember { LightOverrides() }

        // Effective state = optimistic overlay over the polled value. Recomputed
        // every recomposition, so a fresh poll or an overlay edit both show.
        val runtime = lights.associate { cfg ->
            val polled = lightsState.optJSONObject(cfg.entityId)
            val on = overrides.on[cfg.entityId] ?: (polled?.optBoolean("on", false) ?: false)
            val level = overrides.level[cfg.entityId] ?: polledLevel(polled) ?: DEFAULT_LEVEL
            cfg.entityId to LightRuntime(on, level)
        }

        // Drop overlay entries once the poll agrees, and expire any that never do
        // (a dropped command), so external changes aren't masked forever.
        LaunchedEffect(state) {
            lights.forEach { cfg ->
                val polled = lightsState.optJSONObject(cfg.entityId)
                overrides.reconcile(cfg.entityId, polled?.optBoolean("on", false) ?: false, polledLevel(polled))
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                overrides.expire(OVERRIDE_TTL_MS)
            }
        }

        val countOn = runtime.values.count { it.on }
        val anyOn = countOn > 0

        val toggleGroup: () -> Unit = {
            if (anyOn) {
                // Any on → all off (explicit Off; we know the target state).
                lights.filter { runtime.getValue(it.entityId).on }.forEach {
                    commander.setOn(it.entityId, it.endpoint, false)
                    overrides.setOn(it.entityId, false)
                }
            } else {
                // All off → turn on the configured subset (or all if unset).
                val targets = if (onTargets.isEmpty()) lights.map { it.entityId } else onTargets
                lights.filter { it.entityId in targets }.forEach {
                    commander.setOn(it.entityId, it.endpoint, true)
                    overrides.setOn(it.entityId, true)
                }
            }
        }

        var showModal by remember { mutableStateOf(false) }

        GroupTile(
            title = title,
            countOn = countOn,
            total = lights.size,
            anyOn = anyOn,
            onToggle = toggleGroup,
            onExpand = { showModal = true },
            modifier = modifier,
        )

        if (showModal) {
            LightModal(
                title = title,
                lights = lights,
                runtime = runtime,
                commander = commander,
                overrides = overrides,
                onClose = { showModal = false },
            )
        }
    }

    companion object {
        fun parse(obj: JSONObject): LightGroupWidget {
            val array = obj.optJSONArray("lights") ?: JSONArray()
            val lights = (0 until array.length()).map { i ->
                val l = array.getJSONObject(i)
                LightConfig(
                    entityId = l.getString("entity_id"),
                    name = l.optString("name"),
                    endpoint = l.optInt("endpoint", 1),
                    dimmable = l.optBoolean("dimmable", false),
                    color = l.optBoolean("color", false),
                )
            }
            val targetsArray = obj.optJSONArray("on_targets")
            val onTargets = if (targetsArray != null) {
                (0 until targetsArray.length()).map { targetsArray.getString(it) }
            } else {
                emptyList()
            }
            return LightGroupWidget(
                title = obj.optString("title"),
                source = Binding.of(obj.opt("source")),
                onTargets = onTargets,
                lights = lights,
            )
        }
    }
}

/** Effective (overlay-resolved) state of one light. [level] is 1..254. */
private data class LightRuntime(val on: Boolean, val level: Int)

private const val MAX_LEVEL = 254
private const val MIN_LEVEL = 1
private const val DEFAULT_LEVEL = 254

// A light at its dimmest still shows a visible sliver of fill, so "on" reads as on.
private const val MIN_FRACTION = 0.06f

// How long an optimistic overlay entry survives with no confirming poll before it's
// discarded (e.g. a command that never took), letting the real state show through.
private const val OVERRIDE_TTL_MS = 6_000L

private fun polledLevel(obj: JSONObject?): Int? {
    if (obj == null || obj.isNull("level")) return null
    return obj.optInt("level").coerceIn(MIN_LEVEL, MAX_LEVEL)
}

private fun levelPercent(level: Int): Int = (level * 100 / MAX_LEVEL).coerceIn(0, 100)

/**
 * The optimistic overlay: values applied locally the instant a command is sent,
 * shown until the poll confirms them or they expire. Snapshot-backed maps so
 * reads recompose.
 */
private class LightOverrides {
    val on = mutableStateMapOf<String, Boolean>()
    val level = mutableStateMapOf<String, Int>()
    private val stampedAt = mutableStateMapOf<String, Long>()

    fun setOn(entityId: String, value: Boolean) {
        on[entityId] = value
        stampedAt[entityId] = System.currentTimeMillis()
    }

    fun setLevel(entityId: String, value: Int) {
        level[entityId] = value.coerceIn(MIN_LEVEL, MAX_LEVEL)
        stampedAt[entityId] = System.currentTimeMillis()
    }

    /** Clear overlay values the poll now agrees with. */
    fun reconcile(entityId: String, polledOn: Boolean, polledLevel: Int?) {
        if (on[entityId] == polledOn) on.remove(entityId)
        if (polledLevel != null && level[entityId] == polledLevel) level.remove(entityId)
        if (entityId !in on && entityId !in level) stampedAt.remove(entityId)
    }

    /** Drop overlay entries older than [ttlMs] whatever the poll says. */
    fun expire(ttlMs: Long) {
        val cutoff = System.currentTimeMillis() - ttlMs
        stampedAt.filterValues { it < cutoff }.keys.toList().forEach { entityId ->
            on.remove(entityId)
            level.remove(entityId)
            stampedAt.remove(entityId)
        }
    }
}

/** The dashboard tile: big tap toggles the group, the corner glyph opens the modal. */
@Composable
private fun GroupTile(
    title: String,
    countOn: Int,
    total: Int,
    anyOn: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    val container =
        if (anyOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val onContainer =
        if (anyOn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Surface(
            color = container,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxSize().clickable { onToggle() },
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = if (anyOn) "💡" else "🔌", style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = onContainer,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (anyOn) "$countOn of $total on" else "All off",
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Expand-to-modal affordance. A separate tap target in the corner so it
        // doesn't compete with the toggle covering the rest of the tile.
        Text(
            text = "⤢",
            style = MaterialTheme.typography.headlineSmall,
            color = onContainer,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onExpand() }
                .padding(16.dp),
        )
    }
}

/**
 * The full-screen control modal: a vertical pager. Page one is the per-light
 * grid; a colour page follows only when a light supports colour.
 */
@Composable
private fun LightModal(
    title: String,
    lights: List<LightConfig>,
    runtime: Map<String, LightRuntime>,
    commander: LightCommander,
    overrides: LightOverrides,
    onClose: () -> Unit,
) {
    val anyColor = lights.any { it.color }
    val pageCount = if (anyColor) 2 else 1

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header: title and a close control.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onClose() }
                            .padding(12.dp),
                    )
                }

                val pagerState = rememberPagerState(pageCount = { pageCount })
                Row(modifier = Modifier.fillMaxSize()) {
                    VerticalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxHeight()) { page ->
                        if (page == 0) {
                            LightGrid(lights, runtime, commander, overrides)
                        } else {
                            ColorPage(lights.filter { it.color })
                        }
                    }
                    if (pageCount > 1) {
                        PageDots(pagerState.currentPage, pageCount)
                    }
                }
            }
        }
    }
}

/** A 2-up grid of light tiles filling the page — each light gets substantial space. */
@Composable
private fun LightGrid(
    lights: List<LightConfig>,
    runtime: Map<String, LightRuntime>,
    commander: LightCommander,
    overrides: LightOverrides,
) {
    val columns = 2
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        lights.chunked(columns).forEach { rowLights ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                rowLights.forEach { cfg ->
                    val rt = runtime.getValue(cfg.entityId)
                    LightCell(
                        cfg = cfg,
                        on = rt.on,
                        level = rt.level,
                        onToggle = {
                            val next = !rt.on
                            commander.setOn(cfg.entityId, cfg.endpoint, next)
                            overrides.setOn(cfg.entityId, next)
                        },
                        onSetLevel = { level ->
                            // Dragging brightness from off implies turning on.
                            if (!rt.on) {
                                commander.setOn(cfg.entityId, cfg.endpoint, true)
                                overrides.setOn(cfg.entityId, true)
                            }
                            commander.setLevel(cfg.entityId, cfg.endpoint, level)
                            overrides.setLevel(cfg.entityId, level)
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                // Pad a short final row so its tiles keep the same width.
                repeat(columns - rowLights.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One light tile. Dimmable lights fill left→right in proportion to brightness;
 * a tap toggles on/off and a horizontal drag sets the level (committing on
 * release, previewed live). Non-dimmable lights are a plain tap-to-toggle tile.
 */
@Composable
private fun LightCell(
    cfg: LightConfig,
    on: Boolean,
    level: Int,
    onToggle: () -> Unit,
    onSetLevel: (Int) -> Unit,
    modifier: Modifier,
) {
    // Latest callbacks, so the long-lived gesture detectors never call a stale one.
    val toggle by rememberUpdatedState(onToggle)
    val setLevel by rememberUpdatedState(onSetLevel)

    var widthPx by remember { mutableIntStateOf(0) }
    // Non-null only mid-drag: the previewed level before it's committed on release.
    var dragLevel by remember { mutableStateOf<Int?>(null) }

    val dragging = dragLevel != null
    val shownLevel = dragLevel ?: level
    val fraction = when {
        !cfg.dimmable -> if (on) 1f else 0f
        dragging || on -> (shownLevel.toFloat() / MAX_LEVEL).coerceIn(MIN_FRACTION, 1f)
        else -> 0f
    }
    val showsOn = on || dragging

    val fillColor = MaterialTheme.colorScheme.primary
    val onFill = MaterialTheme.colorScheme.onPrimary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val onTrack = MaterialTheme.colorScheme.onSurfaceVariant

    var cellModifier = modifier
        .clip(RoundedCornerShape(24.dp))
        .background(track)
        .onSizeChanged { widthPx = it.width }
        .pointerInput(cfg.entityId) { detectTapGestures { toggle() } }
    if (cfg.dimmable) {
        cellModifier = cellModifier.pointerInput(cfg.entityId) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    dragLevel?.let { setLevel(it) }
                    dragLevel = null
                },
                onDragCancel = { dragLevel = null },
            ) { change, _ ->
                if (widthPx > 0) {
                    val f = (change.position.x / widthPx).coerceIn(0f, 1f)
                    dragLevel = (f * MAX_LEVEL).roundToInt().coerceIn(MIN_LEVEL, MAX_LEVEL)
                }
            }
        }
    }

    Box(modifier = cellModifier) {
        // Brightness fill, growing from the left.
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .align(Alignment.CenterStart)
                    .background(fillColor),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = cfg.name,
                style = MaterialTheme.typography.headlineSmall,
                color = if (showsOn) onFill else onTrack,
            )
            Text(
                text = when {
                    !showsOn -> "Off"
                    cfg.dimmable -> "${levelPercent(shownLevel)}%"
                    else -> "On"
                },
                style = MaterialTheme.typography.titleLarge,
                color = if (showsOn) onFill else onTrack,
            )
        }
    }
}

/**
 * Colour page — shown only when a light supports colour. Read-only for now: the
 * hearthd ColorControl read/command shape isn't pinned down, so there's no write
 * path yet (see [LightCommander]). Once it lands, this becomes hue/sat controls.
 */
@Composable
private fun ColorPage(colorLights: List<LightConfig>) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Colour", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Colour control is coming once hearthd exposes it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        colorLights.forEach { Text(it.name, style = MaterialTheme.typography.titleMedium) }
    }
}

/** A vertical strip of dots marking the current pager page. */
@Composable
private fun PageDots(current: Int, count: Int) {
    Column(
        modifier = Modifier.fillMaxHeight().padding(end = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val color =
                if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
