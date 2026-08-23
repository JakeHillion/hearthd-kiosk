package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.dashboard.parseWidget
import org.json.JSONArray
import org.json.JSONObject

/**
 * A responsive grid of child widgets. Children flow in template order into a
 * fixed number of equal cells (row-major: first child top-left, filling left to
 * right, top to bottom), and the whole grid stretches to fill its slot.
 *
 * The point is device independence: rather than baking in a column count, the
 * grid derives one from the space it's actually given. Column count is
 * [columns] when the template pins it, otherwise as many [minCellWidth]-wide
 * cells as fit — so the same template is 2-up on the kiosk today and reflows to
 * one column on a phone or three on a wide panel without edits. Either way it's
 * clamped to at most one column per child, so a two-child grid never leaves an
 * empty column.
 *
 * Rows share height equally and cells share width equally, matching the
 * fill-the-screen feel of the rest of the dashboard; a short final row keeps its
 * cells the same width as the rows above via spacer padding.
 */
data class GridWidget(
    val children: List<Widget>,
    val columns: Int?,
    val minCellWidth: Dp,
    val spacing: Dp,
) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        if (children.isEmpty()) return

        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            // Resolve the column count against the width we were actually handed,
            // then cap it so we never draw more columns than there are children.
            val cols = (columns ?: adaptiveColumns(maxWidth, minCellWidth, spacing))
                .coerceIn(1, children.size)

            Column(
                modifier = Modifier.fillMaxSize().padding(spacing),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                children.chunked(cols).forEach { rowChildren ->
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        rowChildren.forEach { child ->
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                child.Render(state, Modifier.fillMaxSize())
                            }
                        }
                        // Pad a short final row so its cells keep the full width.
                        repeat(cols - rowChildren.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    companion object {
        private val DEFAULT_MIN_CELL_WIDTH = 300.dp
        private val DEFAULT_SPACING = 16.dp

        fun parse(obj: JSONObject): GridWidget {
            val array = obj.optJSONArray("children") ?: JSONArray()
            val children = (0 until array.length()).map { i ->
                parseWidget(array.getJSONObject(i))
            }
            // A pinned column count wins; 0/absent means "decide from the width".
            val columns = obj.optInt("columns", 0).takeIf { it > 0 }
            return GridWidget(
                children = children,
                columns = columns,
                minCellWidth = obj.optDouble("min_cell_width_dp")
                    .takeUnless { it.isNaN() }?.dp ?: DEFAULT_MIN_CELL_WIDTH,
                spacing = obj.optDouble("spacing_dp")
                    .takeUnless { it.isNaN() }?.dp ?: DEFAULT_SPACING,
            )
        }
    }
}

/**
 * How many [minCellWidth]-wide cells fit across [available], accounting for a
 * [spacing] gap between each. With n columns the content spans
 * `n*cell + (n-1)*spacing`, so the largest fitting n is
 * `(available + spacing) / (minCellWidth + spacing)`. At least one always fits.
 */
private fun adaptiveColumns(available: Dp, minCellWidth: Dp, spacing: Dp): Int {
    val n = ((available + spacing) / (minCellWidth + spacing)).toInt()
    return n.coerceAtLeast(1)
}
