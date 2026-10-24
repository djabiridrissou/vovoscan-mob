package com.vovoscan.app.ui.episode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.episodes.presentation.EpisodeIntent
import com.vovoscan.feature.episodes.presentation.EpisodeLanguageOption
import com.vovoscan.feature.episodes.presentation.EpisodePhase
import com.vovoscan.feature.episodes.presentation.EpisodeUiState
import com.vovoscan.feature.episodes.presentation.EpisodeViewModel
import com.vovoscan.feature.episodes.presentation.StageChip
import com.vovoscan.feature.episodes.presentation.StageState
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A chapter's episode card: whether it exists, its live progress (followed only while this screen is visible), what
 * the user may do with their own keys, and the MP4 export once it is ready.
 */
@Composable
fun EpisodeScreen(
    chapterId: String,
    titleName: String,
    chapterLabel: String,
    onBack: () -> Unit,
    /** Opens the player in the episode's language ("" = the original). */
    onWatch: (language: String) -> Unit,
    /** The language the card opens in: the next chapter keeps the one just watched. */
    language: String = "",
    viewModel: EpisodeViewModel = koinViewModel(key = "episode:$chapterId~$language") { parametersOf(chapterId, language) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Live progress only while the card is on screen.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onIntent(EpisodeIntent.Start) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(EpisodeIntent.Stop) }
    DisposableEffect(viewModel) { onDispose { viewModel.onIntent(EpisodeIntent.Stop) } }
    // A fresh download link from the ViewModel: hand it to the system once.
    val download = state.videoDownload
    LaunchedEffect(download?.id) {
        if (download == null) return@LaunchedEffect
        val ok = VideoSaver.save(context, download.url, download.fileName)
        viewModel.onIntent(if (ok) EpisodeIntent.VideoHandled else EpisodeIntent.VideoFailed)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(I18n.text("common.backArrow"), onBack)
                Kicker(I18n.text("episode.kicker"))
                if (titleName.isNotEmpty()) Text(titleName, style = MaterialTheme.typography.bodySmall)
                if (chapterLabel.isNotEmpty()) Text(chapterLabel, style = MaterialTheme.typography.headlineSmall)
                state.offlineNotice?.let { OfflineNotice(it) }
            }
        }
        if (state.visible) item { EpisodeCard(state, viewModel::onIntent) { onWatch(state.language) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeCard(
    state: EpisodeUiState,
    send: (EpisodeIntent) -> Unit,
    onWatch: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, if (state.phase == EpisodePhase.Running) Brand.Cyan else Brand.Line, RoundedCornerShape(12.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.languageOptions.isNotEmpty()) LanguageButton(state, send)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.live) LiveDot()
        }
        state.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (state.stages.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.stages.forEach { StageChipView(it) }
            }
        }
        if (state.phase == EpisodePhase.Running) {
            val fraction = state.progressFraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.toFloat() },
                    color = Brand.Cyan,
                    trackColor = Brand.Line,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(color = Brand.Cyan, trackColor = Brand.Line, modifier = Modifier.fillMaxWidth())
            }
            state.progressLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (state.badges.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.badges.forEach { Badge(it) } }
        }
        state.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (state.canWatch) PrimaryButton(state.watchLabel, onWatch)
        if (state.canPrepare) {
            if (state.canWatch) {
                GhostButton(state.prepareLabel, { if (!state.working) send(EpisodeIntent.Prepare) })
            } else {
                PrimaryButton(state.prepareLabel, { send(EpisodeIntent.Prepare) }, enabled = !state.working)
            }
        }
        if (state.canReread) GhostButton(state.rereadLabel, { if (!state.working) send(EpisodeIntent.Reread) })
        state.costLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.showsKeyNotice) KeyNotice(state)

        if (state.exportShown) VideoSection(state, send)

        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Paper)) }
        state.error?.let { ErrorText(it) }
        if (state.phase == EpisodePhase.Unavailable) {
            GhostButton(I18n.text("common.tryAgain"), { send(EpisodeIntent.Refresh) })
        }
    }
}

/** The episode's language: the original, or another one (dubbed when ElevenLabs speaks it, else subtitled). */
@Composable
private fun LanguageButton(
    state: EpisodeUiState,
    send: (EpisodeIntent) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(8.dp))
            .clickable(enabled = !state.working) { open = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(state.languageLabel, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Paper))
        state.languageMode?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall.copy(color = Brand.Cyan)) }
        Spacer(Modifier.weight(1f))
        Text("▾", style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
    }
    if (open) {
        EpisodeLanguageSheet(
            state.languageOptions,
            state.languageQuery,
            onQuery = { send(EpisodeIntent.LanguageQuery(it)) },
            onChoose = { send(EpisodeIntent.ChooseLanguage(it)) },
        ) {
            open = false
            send(EpisodeIntent.LanguageQuery(""))
        }
    }
}

/** An episode's languages, searchable (the card's picker and the player's): a pick closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeLanguageSheet(
    options: List<EpisodeLanguageOption>,
    query: String,
    onQuery: (String) -> Unit,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                I18n.text("episode.languageTitle"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            BrandField(
                label = I18n.text("common.search"),
                value = query,
                onValueChange = onQuery,
                placeholder = I18n.text("common.searchLanguage"),
            )
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(options, key = { it.code ?: "" }) { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (option.selected) Brand.Line else Color.Transparent)
                            .clickable {
                                onChoose(option.code)
                                onDismiss()
                            }.padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge.copy(color = Brand.Paper))
                            Text(option.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        if (option.selected) Text("✓", style = MaterialTheme.typography.titleMedium.copy(color = Brand.Red))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyNotice(state: EpisodeUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Brand.Line, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.keyLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Paper)) }
        state.keyWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(state.costNote, style = MaterialTheme.typography.bodySmall)
    }
}

/** The MP4: make it (CPU only, no key), follow its progress, then save it. */
@Composable
private fun VideoSection(
    state: EpisodeUiState,
    send: (EpisodeIntent) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Line))
        Spacer(Modifier.height(4.dp))
        Text(I18n.text("episode.video"), style = MaterialTheme.typography.labelSmall.copy(color = Brand.Muted))
        state.exportLabel?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        state.exportDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.exportProgress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.toFloat() },
                color = Brand.Cyan,
                trackColor = Brand.Line,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.canRequestExport) {
            GhostButton(state.exportActionLabel, { if (!state.exportWorking) send(EpisodeIntent.RequestExport) })
        }
        if (state.canSaveVideo) GhostButton(state.saveVideoLabel, { send(EpisodeIntent.SaveVideo) })
    }
}

/** Pipeline chip: done (paper outline, ✓), active (cyan: work in progress), waiting (muted). */
@Composable
private fun StageChipView(chip: StageChip) {
    val (fill, text, border) =
        when (chip.state) {
            StageState.Done -> Triple(Color.Transparent, Brand.Paper, Brand.Paper)
            StageState.Active -> Triple(Brand.Cyan, Brand.Ink, Brand.Cyan)
            StageState.Waiting -> Triple(Color.Transparent, Brand.Muted, Brand.Line)
        }
    Text(
        if (chip.state == StageState.Done) "✓ ${chip.label}" else chip.label,
        style = MaterialTheme.typography.labelSmall.copy(color = text),
        modifier =
            Modifier
                .background(fill, RoundedCornerShape(4.dp))
                .border(1.dp, border, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Badge(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink),
        modifier = Modifier.background(Brand.Yellow, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun LiveDot() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(Brand.Cyan, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(I18n.text("episode.live"), style = MaterialTheme.typography.labelSmall.copy(color = Brand.Cyan))
    }
}
