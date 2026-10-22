package com.vovoscan.app.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vovoscan.app.ui.common.AppIcons
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.EmptyStateCard
import com.vovoscan.app.ui.common.EpisodeBadge
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.HeartButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.LocalAdaptive
import com.vovoscan.app.ui.common.MarkLines
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.ToggleChip
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.catalog.presentation.CatalogIntent
import com.vovoscan.feature.catalog.presentation.CatalogSource
import com.vovoscan.feature.catalog.presentation.CatalogUiState
import com.vovoscan.feature.catalog.presentation.CatalogViewModel
import com.vovoscan.feature.catalog.presentation.ResumeCard
import com.vovoscan.feature.catalog.presentation.SuggestionKind
import com.vovoscan.feature.catalog.presentation.SuggestionRow
import com.vovoscan.feature.catalog.presentation.SuggestionSection
import com.vovoscan.feature.catalog.presentation.TitleCard
import com.vovoscan.feature.sources.presentation.SourcesViewModel
import org.koin.compose.viewmodel.koinViewModel

private enum class Sheet { Sources, Categories }

@Composable
fun CatalogScreen(
    onOpenTitle: (sourceKey: String, titleId: String) -> Unit,
    onOpenSources: () -> Unit,
    onResume: (sourceKey: String, titleId: String, chapterId: String, page: Int) -> Unit,
    viewModel: CatalogViewModel = koinViewModel(),
    sources: SourcesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sourcesState by sources.state.collectAsStateWithLifecycle()
    // Two features side by side, unaware of each other: sources (the visible ones, in order) and catalog.
    val visible = sourcesState.chips.map { CatalogSource(it.key, it.name) }
    LaunchedEffect(visible) { viewModel.onIntent(CatalogIntent.SourcesChanged(visible)) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val focusManager = LocalFocusManager.current
    // Opening a title from search results or a suggestion teaches the search (the view model decides which).
    val open: (String, String) -> Unit = { sourceKey, titleId ->
        viewModel.onIntent(CatalogIntent.TitleOpened(sourceKey, titleId))
        onOpenTitle(sourceKey, titleId)
    }
    val choose: (SuggestionRow) -> Unit = { row ->
        focusManager.clearFocus()
        if (row.kind == SuggestionKind.Title) {
            open(row.sourceKey.orEmpty(), row.titleId.orEmpty())
        } else {
            viewModel.onIntent(CatalogIntent.UseSearch(row.text))
        }
    }
    val grid = rememberLazyGridState()
    // Scrolled past the search field: it folds into a floating magnifier; a tap floats the field over the catalog.
    val folded by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    var floating by remember { mutableStateOf(false) }
    LaunchedEffect(folded) { if (!folded) floating = false }
    // A search typed in the floating field shows its results from the top.
    LaunchedEffect(state.query) { if (floating) grid.animateScrollToItem(0) }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(LocalAdaptive.current.minTileWidth.dp),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            state = grid,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Kicker(I18n.text("nav.catalog"))
                    Text(I18n.text("catalog.headline"), style = MaterialTheme.typography.headlineSmall)
                    if (state.showSourceFilter || state.showCategoryFilter || state.showEpisodesFilter) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.showSourceFilter) FilterButton(state.sourceFilterLabel, !state.allSources) { sheet = Sheet.Sources }
                            if (state.showCategoryFilter) {
                                FilterButton(state.categoryFilterLabel, state.category != null) {
                                    sheet =
                                        Sheet.Categories
                                }
                            }
                            if (state.showEpisodesFilter) {
                                ToggleChip(state.episodesLabel, state.episodesOnly) { viewModel.onIntent(CatalogIntent.ToggleEpisodes) }
                            }
                        }
                    }
                    state.categoryNote?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted)) }
                    state.safeNote?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted)) }
                    BrandField(
                        I18n.text("common.search"),
                        state.query,
                        { viewModel.onIntent(CatalogIntent.Query(it)) },
                        modifier = Modifier.onFocusChanged { viewModel.onIntent(CatalogIntent.SearchFocused(it.isFocused)) },
                    )
                    if (state.showSuggestions && !floating) SuggestionList(state.suggestions, choose)
                    state.offlineNotice?.let {
                        OfflineNotice(
                            it,
                            onRetry = if (state.canRetry) ({ viewModel.onIntent(CatalogIntent.Refresh) }) else null,
                        )
                    }
                    state.error?.let { ErrorText(it) }
                    state.favoriteError?.let { ErrorText(it) }
                    // The last title read, opened where it was left.
                    state.resume?.takeIf { state.results == null }?.let { resume ->
                        ResumeBanner(resume) { onResume(resume.sourceKey, resume.titleId, resume.chapterId, resume.page) }
                    }
                    if (sourcesState.needsSource) {
                        // A new account has no source on yet: nothing to read until one is added or turned on.
                        EmptyStateCard(
                            sourcesState.firstSourceTitle,
                            sourcesState.firstSourceMessage,
                            sourcesState.firstSourceAction,
                            onOpenSources,
                        )
                    } else if (state.empty) {
                        EmptyStateCard(state.emptyMessage, I18n.text("catalog.pullToRefresh"))
                    }
                }
            }
            items(state.visible, key = { it.sourceKey + "/" + it.id }) { card ->
                TitleTile(
                    card,
                    state.popularLabel,
                    onHeart = { viewModel.onIntent(CatalogIntent.ToggleFavorite(card.sourceKey, card.id)) },
                ) { open(card.sourceKey, card.id) }
            }
            if (state.canLoadMore && state.results == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GhostButton(if (state.loadingMore) I18n.text("common.loading") else I18n.text("catalog.loadMore"), {
                        viewModel.onIntent(CatalogIntent.LoadMore)
                    })
                }
            }
        }
        AnimatedVisibility(
            visible = folded && !floating,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(Brand.Red, CircleShape)
                    .clip(CircleShape)
                    .clickable(onClickLabel = I18n.text("catalog.search")) { floating = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Search, contentDescription = I18n.text("catalog.search"), tint = Brand.Ink)
                // A search already typed: a yellow dot on the magnifier.
                if (state.query.isNotEmpty()) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(10.dp)
                            .background(Brand.Yellow, CircleShape),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = floating,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column {
                FloatingSearch(
                    state.query,
                    { viewModel.onIntent(CatalogIntent.Query(it)) },
                    { viewModel.onIntent(CatalogIntent.SearchFocused(it)) },
                ) { floating = false }
                if (state.showSuggestions) {
                    Box(Modifier.background(Brand.Ink.copy(alpha = 0.92f)).padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                        SuggestionList(state.suggestions, choose)
                    }
                }
            }
        }
    }
    when (sheet) {
        Sheet.Sources -> FilterSheet(I18n.text("nav.sources"), onDismiss = { sheet = null }) { SourceRows(state, viewModel::onIntent) }
        Sheet.Categories ->
            FilterSheet(I18n.text("catalog.categories"), onDismiss = { sheet = null }) {
                CategoryRows(state) {
                    viewModel.onIntent(it)
                    sheet =
                        null
                }
            }
        null -> Unit
    }
}

/** The search field, floating over the catalog once it has scrolled away: no trip back to the top. */
@Composable
private fun FloatingSearch(
    query: String,
    onQuery: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(2.dp, Brand.Red, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AppIcons.Search, contentDescription = null, tint = Brand.Muted)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Brand.Paper),
            cursorBrush = SolidColor(Brand.Red),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { onFocus(it.isFocused) },
        )
        Text(
            "✕",
            style = MaterialTheme.typography.titleMedium.copy(color = Brand.Paper),
            modifier = Modifier.clickable(onClickLabel = I18n.text("common.close"), onClick = onClose).padding(4.dp),
        )
    }
}

/**
 * Under the search field while it has the focus: "Your searches" (↺), "Others searched", "Titles" (cover, name,
 * "Popular ✦ source"). A search fills the field and runs; a title opens.
 */
@Composable
private fun SuggestionList(
    sections: List<SuggestionSection>,
    onChoose: (SuggestionRow) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.Ink2)
            .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp),
    ) {
        sections.forEach { section ->
            Text(
                section.title,
                style = MaterialTheme.typography.labelSmall.copy(color = Brand.Muted),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
            section.rows.forEach { row -> SuggestionLine(row) { onChoose(row) } }
        }
    }
}

@Composable
private fun SuggestionLine(
    row: SuggestionRow,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (row.kind) {
            SuggestionKind.Title ->
                AsyncImage(
                    row.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(30.dp, 40.dp).clip(RoundedCornerShape(4.dp)).background(Brand.Line),
                )
            SuggestionKind.Recent -> Text("↺", style = MaterialTheme.typography.titleMedium.copy(color = Brand.Muted))
            SuggestionKind.Query -> Icon(AppIcons.Search, contentDescription = null, tint = Brand.Muted, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(row.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.detail?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Continue reading": the last title read, its cover, and where it was left. */
@Composable
private fun ResumeBanner(
    card: ResumeCard,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.Ink2)
            .border(2.dp, Brand.Red, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            card.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp, 76.dp).clip(RoundedCornerShape(6.dp)).background(Brand.Line),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.label, style = MaterialTheme.typography.labelMedium.copy(color = Brand.Red))
            Text(card.titleName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(card.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).background(Brand.Red, CircleShape), contentAlignment = Alignment.Center) {
            Text("▶", style = MaterialTheme.typography.titleMedium.copy(color = Brand.Ink))
        }
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
    CheckRow(I18n.text("catalog.all"), state.allSources) { send(CatalogIntent.ShowAllSources) }
    state.sources.forEach { source -> CheckRow(source.name, source.selected) { send(CatalogIntent.ToggleSource(source.key)) } }
}

@Composable
private fun CategoryRows(
    state: CatalogUiState,
    send: (CatalogIntent) -> Unit,
) {
    CheckRow(I18n.text("catalog.allCategories"), state.category == null) { send(CatalogIntent.SelectCategory(null)) }
    // The formats first (every source's comics and books), then each source's own categories under its name.
    state.categoryGroups.forEach { group ->
        group.title?.let { title ->
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(color = Brand.Muted),
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
        }
        group.choices.forEach { category ->
            CheckRow(category.name, category.selected) { send(CatalogIntent.SelectCategory(category.name)) }
        }
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
    popularLabel: String,
    onHeart: () -> Unit,
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
            // The heart in the corner (its own tap: it never opens the card), the episode badge under it.
            HeartButton(card.favorite, card.favoriteAction, Modifier.align(Alignment.TopEnd).padding(6.dp), onHeart)
            card.episodeBadge?.let { EpisodeBadge(it, Modifier.align(Alignment.TopEnd).padding(top = 54.dp, end = 8.dp)) }
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
                if (card.popular) {
                    Text(
                        popularLabel,
                        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Red),
                        modifier =
                            Modifier
                                .background(Brand.Ink.copy(alpha = 0.85f))
                                .border(1.dp, Brand.Red)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (card.started) {
                    Text(
                        I18n.text("catalog.started"),
                        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink),
                        modifier = Modifier.background(Brand.Red).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MarkLines(card.ratingLine, card.favoriteLine)
            Text(card.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
