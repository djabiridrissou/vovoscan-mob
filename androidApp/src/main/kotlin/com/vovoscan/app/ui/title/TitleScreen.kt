package com.vovoscan.app.ui.title

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.downloads.DownloadControl
import com.vovoscan.app.ui.sources.BrandAlert
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.catalog.presentation.TitleDetailViewModel
import com.vovoscan.feature.library.presentation.FollowViewModel
import com.vovoscan.feature.reader.presentation.DownloadAction
import com.vovoscan.feature.reader.presentation.DownloadStatus
import com.vovoscan.feature.reader.presentation.TitleDownloadsIntent
import com.vovoscan.feature.reader.presentation.TitleDownloadsUiState
import com.vovoscan.feature.reader.presentation.TitleDownloadsViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Title page. Three features share it, unaware of each other: details (catalog), following (library) and downloads
 * (reader).
 */
@Composable
fun TitleScreen(
    sourceKey: String,
    titleId: String,
    onBack: () -> Unit,
    onRead: (chapterId: String, startPage: Int) -> Unit,
    onEpisode: (chapterId: String) -> Unit,
    detail: TitleDetailViewModel = koinViewModel(key = "title:$sourceKey/$titleId") { parametersOf(sourceKey, titleId) },
    follow: FollowViewModel = koinViewModel(key = "follow:$sourceKey/$titleId") { parametersOf(sourceKey, titleId) },
    downloads: TitleDownloadsViewModel = koinViewModel(key = "titleDownloads:$sourceKey/$titleId") { parametersOf(sourceKey, titleId) },
) {
    val state by detail.state.collectAsStateWithLifecycle()
    val followState by follow.state.collectAsStateWithLifecycle()
    val downloadState by downloads.state.collectAsStateWithLifecycle()
    // A delete waiting for its confirmation: a chapter id, or "*" for every download of the title.
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AsyncImage(
                state.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(260.dp).background(Brand.Ink2),
            )
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("← Back", onBack)
                if (state.unavailable) {
                    Text("This title isn't saved on this device yet.", style = MaterialTheme.typography.bodyMedium)
                }
                state.notice?.let { OfflineNotice(it, onRetry = if (state.canRetry) detail::refresh else null) }
                state.error?.let { ErrorText(it) }
                if (state.unavailable && state.canRetry) GhostButton("Try again", detail::refresh)
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(state.meta, style = MaterialTheme.typography.bodySmall)
                // Resume where the reader left off, otherwise start at the first chapter (the list runs newest to oldest).
                val resume = followState.resumeChapterId
                when {
                    resume != null ->
                        PrimaryButton(followState.resumeLabel(state.isBook) ?: "Continue →", { onRead(resume, followState.resumePage) })
                    state.chapters.isNotEmpty() ->
                        PrimaryButton(if (state.isBook) "Read →" else "Start reading →", { onRead(state.chapters.last().id, 0) })
                }
                if (state.name.isNotEmpty()) GhostButton(followState.label, { follow.toggle(state.name, state.coverUrl) })
                Text(state.synopsis, style = MaterialTheme.typography.bodyMedium)
                if (state.genres.isNotEmpty()) Text(state.genres.joinToString(" ✦ "), style = MaterialTheme.typography.bodySmall)
                DownloadsBlock(downloadState, downloads::onIntent) { confirmDelete = "*" }
                if (state.chapters.isNotEmpty()) {
                    Text(
                        if (state.isBook) "Contents" else "Chapters",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        items(state.chapters, key = { it.id }) { chapter ->
            val download = downloadState.row(chapter.id)
            Row(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .background(Brand.Ink2, RoundedCornerShape(12.dp))
                    .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
                    .clickable { onRead(chapter.id, 0) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(chapter.label, style = MaterialTheme.typography.bodyMedium)
                    if (chapter.meta.isNotEmpty()) Text(chapter.meta, style = MaterialTheme.typography.bodySmall)
                    download.text?.let {
                        val color = if (download.status == DownloadStatus.Failed) Brand.Danger else Brand.Muted
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(color = color))
                    }
                }
                // A comic of our catalog: the chapter's voiced episode, next to reading it.
                if (state.offersEpisodes) {
                    Text(
                        "Episode",
                        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Paper),
                        modifier =
                            Modifier
                                .border(1.dp, Brand.Line, RoundedCornerShape(999.dp))
                                .clickable { onEpisode(chapter.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                DownloadControl(download) {
                    if (download.action == DownloadAction.Delete) {
                        confirmDelete = chapter.id
                    } else {
                        downloads.onIntent(TitleDownloadsIntent.Act(chapter.id))
                    }
                }
            }
        }
    }
    confirmDelete?.let { target ->
        val all = target == "*"
        BrandAlert(
            title = if (all) downloadState.deleteAllTitle else "Delete this download?",
            message =
                if (all) {
                    downloadState.deleteAllMessage
                } else {
                    "${state.chapterLabel(target)} will need the network again."
                },
            confirmLabel = "Delete",
            onConfirm = {
                downloads.onIntent(if (all) TitleDownloadsIntent.DeleteAll else TitleDownloadsIntent.Act(target))
                confirmDelete = null
            },
            onCancel = { confirmDelete = null },
        )
    }
}

/**
 * Downloads of the title: what is on the device, why the queue waits, "Download next 5", "Download all" (a book:
 * "Download the book"), delete.
 */
@Composable
private fun DownloadsBlock(
    state: TitleDownloadsUiState,
    onIntent: (TitleDownloadsIntent) -> Unit,
    onDeleteAll: () -> Unit,
) {
    state.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    state.notice?.let { OfflineNotice(it) }
    if (!state.offersDownloads) return
    val next = state.downloadNextLabel
    val all = state.downloadAllLabel
    if (next != null || all != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            next?.let { GhostButton(it, { onIntent(TitleDownloadsIntent.DownloadNext) }, Modifier.weight(1f)) }
            all?.let { GhostButton(it, { onIntent(TitleDownloadsIntent.DownloadAll) }, Modifier.weight(1f)) }
        }
    }
    if (state.canDeleteAll) {
        Text(
            state.deleteAllLabel,
            style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
            modifier = Modifier.clickable(onClick = onDeleteAll).padding(vertical = 4.dp),
        )
    }
}
