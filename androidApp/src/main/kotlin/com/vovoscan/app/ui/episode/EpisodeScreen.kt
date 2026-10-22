package com.vovoscan.app.ui.episode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.episodes.presentation.EpisodeIntent
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
    onWatch: () -> Unit,
    viewModel: EpisodeViewModel = koinViewModel(key = "episode:$chapterId") { parametersOf(chapterId) },
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
                GhostButton("← Back", onBack)
                Kicker("Episode")
                if (titleName.isNotEmpty()) Text(titleName, style = MaterialTheme.typography.bodySmall)
                if (chapterLabel.isNotEmpty()) Text(chapterLabel, style = MaterialTheme.typography.headlineSmall)
                state.offlineNotice?.let { OfflineNotice(it) }
            }
        }
        if (state.visible) item { EpisodeCard(state, viewModel::onIntent, onWatch) }
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
            GhostButton("Try again", { send(EpisodeIntent.Refresh) })
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
        Text("Video", style = MaterialTheme.typography.labelSmall.copy(color = Brand.Muted))
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
        Text("LIVE", style = MaterialTheme.typography.labelSmall.copy(color = Brand.Cyan))
    }
}
