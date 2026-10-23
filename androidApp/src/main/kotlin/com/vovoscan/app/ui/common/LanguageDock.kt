package com.vovoscan.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.episodes.presentation.PlayerLanguageDock
import com.vovoscan.feature.reader.presentation.TranslationDock

/** What a language dock shows, from the reader's `TranslationDock` or the player's `PlayerLanguageDock`. */
data class LanguageDockContent(
    val title: String,
    val line: String?,
    val progress: Float?,
    val busy: Boolean,
    val confirmLabel: String?,
    val addKeyLabel: String?,
    val retryLabel: String?,
    val closeLabel: String,
    val failed: Boolean,
)

fun TranslationDock.content() = LanguageDockContent(title, line, progress, busy, confirmLabel, addKeyLabel, retryLabel, closeLabel, failed)

fun PlayerLanguageDock.content() = LanguageDockContent(title, line, progress, busy, startLabel, addKeyLabel, retryLabel, closeLabel, failed)

/**
 * The panel docked at the bottom while a language is on its way (reader SPEC §11): its cost, then a bar while the reader
 * (or the episode) goes on, or why it failed. What is under it keeps showing what it shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageDock(
    content: LanguageDockContent,
    onConfirm: () -> Unit,
    onAddKey: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .background(Brand.Ink2, shape)
            .border(1.5.dp, if (content.busy || content.progress != null) Brand.Cyan else Brand.Line, shape)
            // Taps on the panel stay on it: never a page turn or a pause underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (content.busy) CircularProgressIndicator(Modifier.size(16.dp), color = Brand.Cyan, strokeWidth = 2.dp)
            Text(content.title, style = MaterialTheme.typography.titleMedium.copy(color = Brand.Paper), modifier = Modifier.weight(1f))
            CreditChip()
        }
        content.line?.let {
            val warning = content.failed || (content.confirmLabel == null && content.addKeyLabel != null)
            Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = if (warning) Brand.Yellow else Brand.Paper))
        }
        content.progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Brand.Cyan, trackColor = Brand.Line)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content.confirmLabel?.let { PrimaryButton(it, onConfirm) }
            content.retryLabel?.let { PrimaryButton(it, onRetry) }
            content.addKeyLabel?.let { GhostButton(it, onAddKey) }
            Text(
                content.closeLabel,
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
                modifier = Modifier.align(Alignment.CenterVertically).clickable(onClick = onClose).padding(8.dp),
            )
        }
    }
}
