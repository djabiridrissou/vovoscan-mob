package com.vovoscan.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.EmptyStateCard
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.catalog.presentation.CatalogIntent
import com.vovoscan.feature.catalog.presentation.CatalogSource
import com.vovoscan.feature.catalog.presentation.CatalogUiState
import com.vovoscan.feature.catalog.presentation.CatalogViewModel
import com.vovoscan.feature.catalog.presentation.TitleCard
import com.vovoscan.feature.sources.presentation.SourcesViewModel
import org.koin.compose.viewmodel.koinViewModel

private enum class Sheet { Sources, Categories }

@Composable
fun CatalogScreen(
    onOpenTitle: (sourceKey: String, titleId: String) -> Unit,
    onOpenSources: () -> Unit,
    viewModel: CatalogViewModel = koinViewModel(),
    sources: SourcesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sourcesState by sources.state.collectAsStateWithLifecycle()
    // Two features side by side, unaware of each other: sources (the visible ones, in order) and catalog.
    val visible = sourcesState.chips.map { CatalogSource(it.key, it.name) }
    LaunchedEffect(visible) { viewModel.onIntent(CatalogIntent.SourcesChanged(visible)) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
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
                if (state.showSourceFilter || state.showCategoryFilter) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.showSourceFilter) FilterButton(state.sourceFilterLabel, !state.allSources) { sheet = Sheet.Sources }
                        if (state.showCategoryFilter) {
                            FilterButton(state.categoryFilterLabel, state.category != null) {
                                sheet =
                                    Sheet.Categories
                            }
                        }
                    }
                }
                state.categoryNote?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted)) }
                BrandField("Search", state.query, { viewModel.onIntent(CatalogIntent.Query(it)) })
                state.offlineNotice?.let {
                    OfflineNotice(
                        it,
                        onRetry = if (state.canRetry) ({ viewModel.onIntent(CatalogIntent.Refresh) }) else null,
                    )
                }
                state.error?.let { ErrorText(it) }
                if (sourcesState.needsSource) {
                    // A new account has no source on yet: nothing to read until one is added or turned on.
                    EmptyStateCard(
                        sourcesState.firstSourceTitle,
                        sourcesState.firstSourceMessage,
                        sourcesState.firstSourceAction,
                        onOpenSources,
                    )
                } else if (state.empty) {
                    EmptyStateCard(state.emptyMessage, "Pull down to check again.")
                }
            }
        }
        items(state.visible, key = { it.sourceKey + "/" + it.id }) { card -> TitleTile(card) { onOpenTitle(card.sourceKey, card.id) } }
        if (state.canLoadMore && state.results == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GhostButton(if (state.loadingMore) "Loading…" else "Load more", { viewModel.onIntent(CatalogIntent.LoadMore) })
            }
        }
    }
    when (sheet) {
        Sheet.Sources -> FilterSheet("Sources", onDismiss = { sheet = null }) { SourceRows(state, viewModel::onIntent) }
        Sheet.Categories ->
            FilterSheet("Categories", onDismiss = { sheet = null }) {
                CategoryRows(state) {
                    viewModel.onIntent(it)
                    sheet =
                        null
                }
            }
        null -> Unit
    }
}

/** A filter of the catalog: its current value, red when it narrows the catalog. */
@Composable
private fun FilterButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Text(
        "$label ▾",
        style = MaterialTheme.typography.labelLarge.copy(color = if (active) Brand.Ink else Brand.Paper),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .background(if (active) Brand.Red else Brand.Ink2, RoundedCornerShape(999.dp))
                .border(1.dp, if (active) Brand.Red else Brand.Line, RoundedCornerShape(999.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

/** "All", then each visible source: tap to add or remove it (from "All", that source alone). */
@Composable
private fun SourceRows(
    state: CatalogUiState,
    send: (CatalogIntent) -> Unit,
) {
    CheckRow("All", state.allSources) { send(CatalogIntent.ShowAllSources) }
    state.sources.forEach { source -> CheckRow(source.name, source.selected) { send(CatalogIntent.ToggleSource(source.key)) } }
}

@Composable
private fun CategoryRows(
    state: CatalogUiState,
    send: (CatalogIntent) -> Unit,
) {
    CheckRow("All categories", state.category == null) { send(CatalogIntent.SelectCategory(null)) }
    state.categories.forEach { category ->
        CheckRow(category.name, category.selected) { send(CatalogIntent.SelectCategory(category.name)) }
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (checked) "✓" else "",
            style = MaterialTheme.typography.titleMedium.copy(color = Brand.Red),
        )
    }
}

/** Title tile: cover, format and source badges, name. */
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
            Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    card.formatLabel,
                    style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink),
                    modifier = Modifier.background(Brand.Yellow).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    card.sourceName,
                    style = MaterialTheme.typography.labelSmall.copy(color = Brand.Paper),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(Brand.Ink.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(card.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
