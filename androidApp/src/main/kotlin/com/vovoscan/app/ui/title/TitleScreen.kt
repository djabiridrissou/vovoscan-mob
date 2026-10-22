package com.vovoscan.app.ui.title

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vovoscan.app.ui.common.EpisodeBadge
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.HeartButton
import com.vovoscan.app.ui.common.MarkLines
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.common.StarRating
import com.vovoscan.app.ui.common.ToggleChip
import com.vovoscan.app.ui.downloads.DownloadControl
import com.vovoscan.app.ui.sources.BrandAlert
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.catalog.presentation.ChapterTarget
import com.vovoscan.feature.catalog.presentation.TitleDetailIntent
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
    /**
     * [readSource], [readTitle]: where the chapter is read (this title, or another source's title of the same series);
     * [language]: open it translated (the language last read in, when this chapter is ready in it); null: as it is.
     */
    onRead: (readSource: String, readTitle: String, chapterId: String, startPage: Int, language: String?) -> Unit,
    onEpisode: (chapterId: String) -> Unit,
    /** "Translate": the chapter opens in the reader on its language picker. */
    onTranslate: (chapterId: String) -> Unit = {},
    /** "Also on": the same series' title page at another source. */
    onOpenTitle: (sourceKey: String, titleId: String) -> Unit = { _, _ -> },
    detail: TitleDetailViewModel = koinViewModel(key = "title:$sourceKey/$titleId") { parametersOf(sourceKey, titleId) },
    follow: FollowViewModel = koinViewModel(key = "follow:$sourceKey/$titleId") { parametersOf(sourceKey, titleId) },
    downloads: TitleDownloadsViewModel = koinViewModel(key = "titleDownloads:$sourceKey/$titleId") { parametersOf(sourceKey, titleId) },
) {
    val state by detail.state.collectAsStateWithLifecycle()
    val followState by follow.state.collectAsStateWithLifecycle()
    val downloadState by downloads.state.collectAsStateWithLifecycle()
    // A chapter row's pill, through the view model: opened once, then answered.
    LaunchedEffect(state.open?.seq) {
        val open = state.open ?: return@LaunchedEffect
        when (open.target) {
            ChapterTarget.Translate -> onTranslate(open.chapterId)
            ChapterTarget.Episode -> onEpisode(open.chapterId)
        }
        detail.onIntent(TitleDetailIntent.OpenHandled(open.seq))
    }
    // A delete waiting for its confirmation: a chapter id, or "*" for every download of the title.
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (state.coverUrl == null) {
                CoverArtPlaceholder(state.name)
            } else {
                AsyncImage(
                    state.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(260.dp).background(Brand.Ink2),
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(I18n.text("common.backArrow"), onBack)
                if (state.unavailable) {
                    Text(I18n.text("catalog.titleNotSaved"), style = MaterialTheme.typography.bodyMedium)
                }
                state.notice?.let { OfflineNotice(it, onRetry = if (state.canRetry) detail::refresh else null) }
                state.error?.let { ErrorText(it) }
                if (state.unavailable && state.canRetry) GhostButton(I18n.text("common.tryAgain"), detail::refresh)
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(state.meta, style = MaterialTheme.typography.bodySmall)
                MarkLines(
                    state.ratingLine,
                    state.favoriteLine,
                    Modifier.clearAndSetSemantics {
                        contentDescription = listOfNotNull(state.ratedBy, state.favoriteLine).joinToString(", ")
                    },
                )
                // Where this account left the title (this device or another), otherwise chapter 1.
                state.readAction?.let { action ->
                    PrimaryButton(action.label, {
                        onRead(
                            action.sourceKey.ifEmpty { sourceKey },
                            action.titleId.ifEmpty { titleId },
                            action.chapterId,
                            action.page,
                            action.language,
                        )
                    })
                    Text(action.detail, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                }
                if (state.name.isNotEmpty()) {
                    GhostButton(followState.label, { follow.toggle(state.name, state.coverUrl) })
                    // The heart, labelled: "Add to favourites" / "Favourite" (it follows the title too).
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeartButton(followState.favorite, followState.favoriteAction) { follow.toggleFavorite(state.name, state.coverUrl) }
                        Text(
                            followState.favoriteLabel,
                            style = MaterialTheme.typography.labelLarge.copy(color = if (followState.favorite) Brand.Red else Brand.Paper),
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                    followState.error?.let { ErrorText(it) }
                }
                if (state.showStars) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StarRating(
                            fills = state.starFills,
                            preview = state::previewFills,
                            title = state.ratingTitle,
                            value = state.ratingValue,
                            score = state.myRating,
                            onRate = { detail.onIntent(TitleDetailIntent.RateAt(it)) },
                            onStep = { detail.onIntent(TitleDetailIntent.StepRating(it)) },
                        )
                        state.rateHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
                        if (state.showClear) {
                            Text(
                                state.clearLabel,
                                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
                                modifier = Modifier.clickable(role = Role.Button) { detail.onIntent(TitleDetailIntent.ClearRating) },
                            )
                        }
                    }
                    state.ratingError?.let { ErrorText(it) }
                }
                Text(state.synopsis, style = MaterialTheme.typography.bodyMedium)
                if (state.genres.isNotEmpty()) Text(state.genres.joinToString(" ✦ "), style = MaterialTheme.typography.bodySmall)
                DownloadsBlock(downloadState, downloads::onIntent) { confirmDelete = "*" }
                if (state.chapters.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.isBook) I18n.text("catalog.contents") else I18n.text("catalog.chapters"),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        // Chapter 1 on top by default; one tap flips the list, remembered for every title.
                        Text(
                            "⇅ " + state.orderLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = Brand.Muted,
                            modifier =
                                Modifier
                                    .clickable { detail.onIntent(TitleDetailIntent.ToggleOrder) }
                                    .padding(8.dp),
                        )
                    }
                    // Only when a chapter has an episode (and the account has `video`): those chapters alone.
                    if (state.showEpisodesFilter) {
                        ToggleChip(state.episodesChip, state.episodesOnly) { detail.onIntent(TitleDetailIntent.ToggleEpisodes) }
                    }
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
                    // The chapter being read: a red edge, so the eye finds it in a long list.
                    .border(if (chapter.current) 2.dp else 1.dp, if (chapter.current) Brand.Red else Brand.Line, RoundedCornerShape(12.dp))
                    .clickable {
                        onRead(
                            chapter.sourceKey.ifEmpty { sourceKey },
                            chapter.titleId.ifEmpty { titleId },
                            chapter.id,
                            0,
                            chapter.openLanguage,
                        )
                    }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(chapter.label, style = MaterialTheme.typography.bodyMedium)
                    // A chapter this source doesn't have, read at another source of the same series.
                    chapter.sourceBadge?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall.copy(color = Brand.Paper),
                            modifier = Modifier.border(1.dp, Brand.Line).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (chapter.meta.isNotEmpty()) Text(chapter.meta, style = MaterialTheme.typography.bodySmall)
                    // Its translation in the language last read in: ready (yellow), or on its way (cyan).
                    chapter.translation?.let {
                        val color = if (chapter.translationReady) Brand.Yellow else Brand.Cyan
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(color = color))
                    }
                    // The languages its episode can be watched in: "▶ Original", "▶ Français", "+2".
                    if (chapter.episodes.isNotEmpty()) {
                        Row(
                            Modifier
                                .padding(top = 2.dp)
                                .clearAndSetSemantics { contentDescription = chapter.episodesA11y.orEmpty() },
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            chapter.episodes.forEach { EpisodeBadge(it) }
                        }
                    }
                    download.text?.let {
                        val color = if (download.status == DownloadStatus.Failed) Brand.Danger else Brand.Muted
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(color = color))
                    }
                }
                // Any comic: the chapter in another language (the reader opens on its picker).
                if (state.offersTranslation && chapter.ownChapter) {
                    Text(
                        state.translateChip,
                        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Paper),
                        modifier =
                            Modifier
                                .border(1.dp, Brand.Line, RoundedCornerShape(999.dp))
                                .clickable { detail.onIntent(TitleDetailIntent.Translate(chapter.id)) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                // Any comic: the chapter's voiced episode, next to reading it.
                if (state.offersEpisodes && chapter.ownChapter) {
                    Text(
                        I18n.text("episode.kicker"),
                        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Paper),
                        modifier =
                            Modifier
                                .border(1.dp, Brand.Line, RoundedCornerShape(999.dp))
                                .clickable { detail.onIntent(TitleDetailIntent.Episode(chapter.id)) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (chapter.ownChapter) {
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
        // The same series at other sources (proven by its pages): open it there.
        if (state.alsoOn.isNotEmpty()) {
            item(key = "alsoOn") {
                Text(
                    state.alsoOnTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(state.alsoOn, key = { "alsoOn/${it.sourceKey}/${it.titleId}" }) { other ->
                Row(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .background(Brand.Ink2, RoundedCornerShape(12.dp))
                        .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
                        .clickable { onOpenTitle(other.sourceKey, other.titleId) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(other.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("›", style = MaterialTheme.typography.titleMedium, color = Brand.Muted)
                }
            }
        }
    }
    confirmDelete?.let { target ->
        val all = target == "*"
        BrandAlert(
            title = if (all) downloadState.deleteAllTitle else I18n.text("downloads.deleteOneTitle"),
            message =
                if (all) {
                    downloadState.deleteAllMessage
                } else {
                    "${state.chapterLabel(target)} will need the network again."
                },
            confirmLabel = I18n.text("common.delete"),
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

/** A title without a cover: its initial stamped with a hard red offset, never an empty grey band. */
@Composable
private fun CoverArtPlaceholder(name: String) {
    val initial = name.firstOrNull()?.uppercase().orEmpty()
    Box(Modifier.fillMaxWidth().height(260.dp).background(Brand.Ink2), contentAlignment = Alignment.Center) {
        Text(
            initial,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
            color = Brand.Red,
            modifier = Modifier.offset(6.dp, 6.dp),
        )
        Text(initial, style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp), color = Brand.Paper)
    }
}
