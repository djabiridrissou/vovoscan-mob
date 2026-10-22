package com.vovoscan.app.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.sources.BrandAlert
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.reader.presentation.DownloadAction
import com.vovoscan.feature.reader.presentation.DownloadGroup
import com.vovoscan.feature.reader.presentation.DownloadRow
import com.vovoscan.feature.reader.presentation.DownloadStatus
import com.vovoscan.feature.reader.presentation.DownloadsIntent
import com.vovoscan.feature.reader.presentation.DownloadsViewModel
import org.koin.compose.viewmodel.koinViewModel

/** Downloads (from the Library): chapters and books on the device by title, the space they use, Wi-Fi only. */
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenTitle: (sourceKey: String, titleId: String) -> Unit,
    viewModel: DownloadsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A delete waiting for its confirmation: "source|chapter" or "title|source|title id".
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(I18n.text("common.backArrow"), onBack)
                Kicker(I18n.text("downloads.title"))
                Text(I18n.text("downloads.headline"), style = MaterialTheme.typography.headlineSmall)
                if (state.total.isNotEmpty()) Text(state.total, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.wifiOnlyLabel, style = MaterialTheme.typography.bodyMedium)
                        Text(state.wifiOnlyHelp, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = state.wifiOnly,
                        onCheckedChange = { viewModel.onIntent(DownloadsIntent.SetWifiOnly(it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
                    )
                }
                state.notice?.let { OfflineNotice(it) }
                if (state.empty) Text(state.emptyMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.groups, key = { it.sourceKey + "/" + it.titleId }) { group ->
            Group(
                group,
                onOpenTitle = { onOpenTitle(group.sourceKey, group.titleId) },
                onDeleteTitle = { confirm = "title|${group.sourceKey}|${group.titleId}" },
                onAct = { row ->
                    if (row.action == DownloadAction.Delete) {
                        confirm = "${row.sourceKey}|${row.chapterId}"
                    } else {
                        viewModel.onIntent(DownloadsIntent.Act(row.sourceKey, row.chapterId))
                    }
                },
                onRemove = { row -> viewModel.onIntent(DownloadsIntent.Remove(row.sourceKey, row.chapterId)) },
            )
        }
    }
    confirm?.let { target ->
        val parts = target.split("|")
        val title = parts[0] == "title"
        BrandAlert(
            title = if (title) I18n.text("downloads.deleteAllTitle") else I18n.text("downloads.deleteOneTitle"),
            message = I18n.text("downloads.needsNetwork"),
            confirmLabel = I18n.text("common.delete"),
            onConfirm = {
                viewModel.onIntent(
                    if (title) DownloadsIntent.DeleteTitle(parts[1], parts[2]) else DownloadsIntent.Act(parts[0], parts[1]),
                )
                confirm = null
            },
            onCancel = { confirm = null },
        )
    }
}

@Composable
private fun Group(
    group: DownloadGroup,
    onOpenTitle: () -> Unit,
    onDeleteTitle: () -> Unit,
    onAct: (DownloadRow) -> Unit,
    onRemove: (DownloadRow) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpenTitle)) {
                Text(group.titleName, style = MaterialTheme.typography.titleMedium)
                Text(group.meta, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                I18n.text("common.delete"),
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
                modifier = Modifier.clickable(onClick = onDeleteTitle).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            )
        }
        group.rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium)
                    row.text?.let {
                        val color = if (row.status == DownloadStatus.Failed) Brand.Danger else Brand.Muted
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(color = color))
                    }
                }
                if (row.removable) {
                    Text(
                        I18n.text("common.remove"),
                        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
                        modifier = Modifier.clickable { onRemove(row) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                DownloadControl(row) { onAct(row) }
            }
        }
    }
}
