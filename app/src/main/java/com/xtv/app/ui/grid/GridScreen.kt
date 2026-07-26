package com.xtv.app.ui.grid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.xtv.app.R
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.xtv.app.core.model.MediaItem
import com.xtv.app.ui.viewer.ReelPolicy
import kotlinx.coroutines.delay

/**
 * The escape hatch: the whole reel at a glance, for picking one item out of order.
 *
 * **Fixed card geometry, never a staggered grid.** Compose resolves D-pad moves with
 * `13·majorAxis² + minorAxis²` over candidate rectangles. Uneven card heights desynchronise the
 * columns, so a diagonal neighbour can win over the correct next card in your own column — and
 * because the reverse geometry differs, Up does not bring you back. Non-reciprocal focus is what
 * users experience as "the grid is broken". A uniform lattice makes the metric predictable.
 *
 * Aspect variation is absorbed *inside* the card instead: portrait and landscape posters both sit in
 * the same box, letterboxed rather than cropped, because cropping is how you hide the subject.
 */
@Composable
fun GridScreen(
    items: List<MediaItem>,
    onPlay: (Int) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val state = rememberLazyGridState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(5) { runCatching { first.requestFocus() }; delay(60) } }

    Column(Modifier.fillMaxSize().background(Color(0xFF0E0E0E))) {
        Text(
            stringResource(R.string.grid_count, items.size),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(start = 48.dp, top = 27.dp, bottom = 12.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = state,
            // 48/27dp is the overscan-safe inset for TV; the focus scale also grows cards slightly
            // outside their bounds, and this keeps the outer row from being clipped.
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            // The no-arg overload is the stable one; the `onRestoreFailed` variant most samples use
            // is deprecated and experimental.
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                MediaCard(
                    item = item,
                    onClick = { onPlay(index) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun MediaCard(item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF1A1A1A))) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = null,
                // Fit, not Crop: the poster frame is the only clue to what this is.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            item.durationMs?.takeIf { it > 0 }?.let {
                Text(
                    ReelPolicy.formatDuration(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            "@${item.author.username}",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
