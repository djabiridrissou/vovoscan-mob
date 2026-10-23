package com.vovoscan.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.reader.presentation.NextActionKind
import com.vovoscan.feature.reader.presentation.NextAutoSwitch
import com.vovoscan.feature.reader.presentation.NextBubble
import com.vovoscan.feature.reader.presentation.NextButton
import com.vovoscan.feature.reader.presentation.NextEndCard
import com.vovoscan.feature.reader.presentation.NextToast

/** The end card's way on while reading in a language (`NextChapterUiState.endCard`), and what its buttons do. */
class NextChapterUi(
    val end: NextEndCard,
    val auto: NextAutoSwitch?,
    val onButton: (NextButton) -> Unit,
    val onAuto: (Boolean) -> Unit,
)

val LocalNextChapter = compositionLocalOf<NextChapterUi?> { null }

/** Under "Finished: Chapter 3", in place of the card's own "Next chapter": always a way on. */
@Composable
internal fun NextEndContent(ui: NextChapterUi) {
    val end = ui.end
    end.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Paper)) }
    end.progress?.let {
        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Brand.Cyan, trackColor = Brand.Line)
    }
    end.primary?.let { NextButtonView(it, ui.onButton, primary = true) }
    end.secondary?.let { NextButtonView(it, ui.onButton, primary = false) }
    end.error?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Yellow)) }
    ui.auto?.let { AutoRow(it, ui.onAuto) }
}

@Composable
private fun NextButtonView(
    button: NextButton,
    onButton: (NextButton) -> Unit,
    primary: Boolean,
) {
    // Without a key, the way on is the keys: a secondary action, never a red spend.
    if (primary && button.kind != NextActionKind.AddKey) {
        PrimaryButton(button.label, { onButton(button) }, enabled = button.enabled)
    } else {
        GhostButton(button.label, { if (button.enabled) onButton(button) })
    }
}

@Composable
internal fun AutoRow(
    auto: NextAutoSwitch,
    onAuto: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(auto.label, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted), modifier = Modifier.weight(1f))
        Switch(
            checked = auto.on,
            onCheckedChange = onAuto,
            enabled = auto.enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
        )
    }
}

/** The balloon past 40 %: what is being prepared (Cancel), then the next offer at its price. Taps stay on it. */
@Composable
internal fun NextBubbleView(
    bubble: NextBubble,
    onButton: (NextButton) -> Unit,
    onCancel: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 360.dp)
            .background(Brand.Ink2, shape)
            .border(1.5.dp, if (bubble.busyLine != null) Brand.Cyan else Brand.Yellow, shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bubble.busyLine?.let { line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Brand.Cyan, strokeWidth = 2.dp)
                Text(line, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Paper), modifier = Modifier.weight(1f))
                bubble.cancelLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
                        modifier = Modifier.clickable(onClick = onCancel).padding(4.dp),
                    )
                }
            }
            bubble.progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Brand.Cyan, trackColor = Brand.Line)
            }
        }
        bubble.offerLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Paper)) }
        bubble.offer?.let { NextButtonView(it, onButton, primary = true) }
        bubble.error?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Yellow)) }
        Text(
            bubble.hideLabel,
            style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
            modifier = Modifier.align(Alignment.End).clickable(onClick = onHide).padding(4.dp),
        )
    }
}

/** "Chapter 4 is ready in Français." with "Read it →"; the view model takes it away after 8 s. */
@Composable
internal fun NextToastView(
    toast: NextToast,
    onButton: (NextButton) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .background(Brand.Ink2, shape)
            .border(1.5.dp, Brand.Cyan, shape)
            .clickable(onClick = onDismiss)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(toast.line, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Paper), modifier = Modifier.weight(1f))
        Text(
            toast.button.label,
            style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red),
            modifier = Modifier.clickable { onButton(toast.button) }.padding(4.dp),
        )
    }
}
