package com.vovoscan.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.sources.SourcePicker
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.catalog.presentation.CatalogIntent
import com.vovoscan.feature.catalog.presentation.CatalogViewModel
import com.vovoscan.feature.catalog.presentation.TitleCard
import com.vovoscan.feature.sources.presentation.SourcesViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CatalogScreen(
    onOpenTitle: (sourceKey: String, titleId: String) -> Unit,
    viewModel: CatalogViewModel = koinViewModel(),
    sources: SourcesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sourcesState by sources.state.collectAsStateWithLifecycle()
    val sourceKeys = sourcesState.chips.map { it.key }
    LaunchedEffect(sourceKeys) { viewModel.onIntent(CatalogIntent.SourcesChanged(sourceKeys)) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Kicker("Catalog")
                Text("Pick a title.", style = MaterialTheme.typography.headlineSmall)
                // Two features side by side, unaware of each other: sources (picker) and catalog.
                SourcePicker(sourcesState.chips, state.sourceKey) { viewModel.onIntent(CatalogIntent.SelectSource(it)) }
                BrandField("Search", state.query, { viewModel.onIntent(CatalogIntent.Query(it)) })
                state.offlineNotice?.let { OfflineNotice(it) }
                state.error?.let { ErrorText(it) }
                if (state.empty) Text(state.emptyMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.visible, key = { it.sourceKey + "/" + it.id }) { card -> TitleTile(card) { onOpenTitle(card.sourceKey, card.id) } }
        if (state.canLoadMore && state.results == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GhostButton(if (state.loadingMore) "Loading…" else "Load more", { viewModel.onIntent(CatalogIntent.LoadMore) })
            }
        }
    }
}

/** Title tile: cover, format badge, name. */
@Composable
private fun TitleTile(
    card: TitleCard,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.Ink2)
            .border(2.dp, Brand.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Brand.Line)) {
            AsyncImage(card.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(
                card.formatLabel,
                style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink),
                modifier =
                    Modifier
                        .align(
                            Alignment.TopStart,
                        ).padding(8.dp)
                        .background(Brand.Yellow)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.padding(12.dp)) {
            Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(card.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
