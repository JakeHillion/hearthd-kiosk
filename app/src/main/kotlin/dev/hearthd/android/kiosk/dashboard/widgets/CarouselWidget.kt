package dev.hearthd.android.kiosk.dashboard.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hearthd.android.kiosk.dashboard.Widget
import dev.hearthd.android.kiosk.dashboard.parseWidget
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One entry in a [CarouselWidget]: a [title] shown in the strip and its [child]. */
data class CarouselPage(val title: String, val child: Widget)

/**
 * The top-level Nest-Hub-style layout: a horizontal strip of topic titles with a
 * swipeable pager beneath. Tapping a title animates to that page; swiping moves
 * one page at a time. Each page holds a single child widget.
 *
 * Pager state is remembered locally, so a state-only refresh keeps the current
 * page; a template change resets it (the host keys the tree on the template hash).
 */
data class CarouselWidget(
    val pages: List<CarouselPage>,
) : Widget {
    @Composable
    override fun Render(state: JSONObject, modifier: Modifier) {
        if (pages.isEmpty()) return

        val pagerState = rememberPagerState(pageCount = { pages.size })
        val scope = rememberCoroutineScope()

        Column(modifier = modifier.fillMaxSize()) {
            // Topic strip. Kept deliberately plain — a scrollable row of titles —
            // rather than a Material tab component, so it survives version churn
            // and leaves the real styling for later.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                pages.forEachIndexed { index, page ->
                    val selected = pagerState.currentPage == index
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .clickable { scope.launch { pagerState.animateScrollToPage(index) } }
                            .padding(vertical = 4.dp),
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    pages[index].child.Render(state, Modifier.fillMaxSize())
                }
            }
        }
    }

    companion object {
        fun parse(obj: JSONObject): CarouselWidget {
            val array = obj.optJSONArray("pages") ?: JSONArray()
            val pages = (0 until array.length()).map { i ->
                val page = array.getJSONObject(i)
                CarouselPage(
                    title = page.optString("title"),
                    child = parseWidget(page.getJSONObject("child")),
                )
            }
            return CarouselWidget(pages)
        }
    }
}
