package com.vovoscan.app.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.sources.presentation.RepositoryRow
import com.vovoscan.feature.sources.presentation.SourceRow
import com.vovoscan.feature.sources.presentation.SourcesIntent
import com.vovoscan.feature.sources.presentation.SourcesViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * "Sources" screen: show, hide and reorder each source, see updates, open a source, add one, manage repositories.
 * No logic here (SourcesViewModel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onAdd: (repositoryId: String?) -> Unit,
    onOpenSource: (key: String) -> Unit,
    viewModel: SourcesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onIntent(SourcesIntent.CheckUpdates) }
    PullToRefreshBox(
        isRefreshing = state.freshness == Freshness.Refreshing,
        onRefresh = { viewModel.onIntent(SourcesIntent.Refresh) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Kicker(I18n.text("nav.sources")) }
                        SmallAction("+ Add") { onAdd(null) }
                    }
                    Text(I18n.text("sources.headline"), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        I18n.text("sources.onDeviceNote"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.updatesLabel?.let { UpdatesBanner(it) }
                    if (state.checkingUpdates) WorkingNotice(I18n.text("sources.checkingUpdates"))
                    state.pendingNotice?.let { OfflineNotice(it) }
                    state.error?.let { MessageBanner(it, error = true) { viewModel.onIntent(SourcesIntent.DismissMessage) } }
                    state.notice?.let { MessageBanner(it, error = false) { viewModel.onIntent(SourcesIntent.DismissMessage) } }
                }
            }
            items(state.rows, key = { it.key }) { row ->
                SourceRowItem(
                    row = row,
                    onOpen = { onOpenSource(row.key) },
                    onIntent = viewModel::onIntent,
                )
            }
            if (state.repositories.isNotEmpty()) {
                item {
                    Text(
                        I18n.text("sources.repositories"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(state.repositories, key = { "repo:" + it.id }) { repo ->
                    RepositoryRowItem(
                        repo = repo,
                        onBrowse = { onAdd(repo.id) },
                        onRemove = { viewModel.onIntent(SourcesIntent.RemoveRepository(repo.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRowItem(
    row: SourceRow,
    onOpen: () -> Unit,
    onIntent: (SourcesIntent) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.Ink2)
            .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceIcon(row.iconUrl, row.initial)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.name, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.versionLabel.isNotEmpty()) Text(row.versionLabel, style = MaterialTheme.typography.bodySmall)
                OriginChip(row.originLabel, row.thirdParty)
                if (row.updateAvailable) UpdateBadge()
                // Cyan: change waiting to be sent (the brand reserves cyan for work in progress).
                if (row.pendingSync) Box(Modifier.size(8.dp).background(Brand.Cyan, CircleShape))
            }
            Text(row.kindsLabel, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(row.engineLabel, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (row.needsSettings) {
                Text(
                    I18n.text("sources.settingsNeededShort"),
                    style = MaterialTheme.typography.bodySmall.copy(color = Brand.Yellow),
                )
            }
        }
        Arrow("↑", row.canMoveUp) { onIntent(SourcesIntent.MoveUp(row.key)) }
        Arrow("↓", row.canMoveDown) { onIntent(SourcesIntent.MoveDown(row.key)) }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = row.enabled,
            onCheckedChange = { onIntent(SourcesIntent.Toggle(row.key)) },
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
        )
    }
}

@Composable
private fun RepositoryRowItem(
    repo: RepositoryRow,
    onBrowse: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.Ink2)
            .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onBrowse)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(repo.name, style = MaterialTheme.typography.bodyMedium)
            Text(repo.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            repo.sourceCountLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (repo.pendingSync) Box(Modifier.size(8.dp).background(Brand.Cyan, CircleShape))
        Box {
            Text(
                "⋯",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { menu = true }.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Brand.Ink2) {
                DropdownMenuItem(
                    text = { Text(I18n.text("common.remove"), style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Danger)) },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/** Yellow highlight: updates found. */
@Composable
private fun UpdatesBanner(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(999.dp))
            .border(1.dp, Brand.Yellow, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Brand.Yellow, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Paper))
    }
}

/** Cyan dot: work in progress. */
@Composable
private fun WorkingNotice(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(Brand.Cyan, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Arrow(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        glyph,
        style = MaterialTheme.typography.titleMedium.copy(color = if (enabled) Brand.Paper else Brand.Line),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
