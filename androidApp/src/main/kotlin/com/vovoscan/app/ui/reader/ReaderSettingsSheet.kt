package com.vovoscan.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.settings.Chip
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.reader.domain.ComicFormat
import com.vovoscan.feature.reader.presentation.ReaderIntent
import com.vovoscan.feature.reader.presentation.ReaderSettingsSheet
import com.vovoscan.feature.reader.presentation.ReaderText

/** Reader settings (gear in the top bar): every choice comes ready from `ReaderUiState.settings`. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSettingsSheet(
    sheet: ReaderSettingsSheet,
    onIntent: (ReaderIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Brand.Ink2,
        contentColor = Brand.Paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(ReaderText.SETTINGS_TITLE, style = MaterialTheme.typography.titleMedium)

            Section(ReaderText.SERIES_MODE)
            Chips { sheet.seriesModes.forEach { Chip(it.label, it.selected) { onIntent(ReaderIntent.SetMode(it.mode)) } } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Help(sheet.seriesHint, Modifier.weight(1f))
                if (sheet.canResetSeries) Chip(ReaderText.RESET_SERIES, false) { onIntent(ReaderIntent.ResetSeriesMode) }
            }

            Section(ReaderText.DEFAULT_MODES)
            Label(ReaderText.MANGA)
            Chips {
                sheet.mangaModes.forEach {
                    Chip(
                        it.label,
                        it.selected,
                    ) { onIntent(ReaderIntent.SetDefaultMode(ComicFormat.Manga, it.mode)) }
                }
            }
            Label(ReaderText.WEBTOON)
            Chips {
                sheet.webtoonModes.forEach {
                    Chip(
                        it.label,
                        it.selected,
                    ) { onIntent(ReaderIntent.SetDefaultMode(ComicFormat.Webtoon, it.mode)) }
                }
            }

            Section(ReaderText.TAP_ZONES)
            Chips { sheet.layouts.forEach { Chip(it.label, it.selected) { onIntent(ReaderIntent.SetTapLayout(it.layout)) } } }
            Label(ReaderText.INVERT)
            Chips { sheet.inverts.forEach { Chip(it.label, it.selected) { onIntent(ReaderIntent.SetInvert(it.invert)) } } }
            Toggle(ReaderText.SHOW_TAP_ZONES, ReaderText.SHOW_TAP_ZONES_HELP, sheet.showTapZones) {
                onIntent(ReaderIntent.SetShowTapZones(it))
            }

            Section(ReaderText.SCROLL_AMOUNT)
            Chips { sheet.scrollAmounts.forEach { Chip(it.label, it.selected) { onIntent(ReaderIntent.SetScrollFraction(it.amount)) } } }
            Help(ReaderText.SCROLL_AMOUNT_HELP)

            Toggle(ReaderText.DOUBLE_TAP_ZOOM, ReaderText.DOUBLE_TAP_ZOOM_HELP, sheet.doubleTapZoom) {
                onIntent(ReaderIntent.SetDoubleTapZoom(it))
            }
            Toggle(ReaderText.DOUBLE_PAGES, ReaderText.DOUBLE_PAGES_HELP, sheet.doublePages) { onIntent(ReaderIntent.SetDoublePages(it)) }
            Toggle(ReaderText.KEEP_AWAKE, ReaderText.KEEP_AWAKE_HELP, sheet.keepAwake) { onIntent(ReaderIntent.SetKeepAwake(it)) }
            Toggle(ReaderText.VOLUME_KEYS, ReaderText.VOLUME_KEYS_HELP, sheet.volumeKeys) { onIntent(ReaderIntent.SetVolumeKeys(it)) }
            if (sheet.volumeKeys) {
                Toggle(ReaderText.VOLUME_KEYS_INVERTED, null, sheet.volumeKeysInverted) { onIntent(ReaderIntent.SetVolumeKeysInverted(it)) }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted))
}

@Composable
private fun Help(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted), modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Toggle(
    title: String,
    help: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            help?.let { Help(it) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
        )
    }
}
