package com.vovoscan.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vovoscan.app.ui.settings.Chip
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.reader.domain.BookFont
import com.vovoscan.feature.reader.presentation.BookIntent
import com.vovoscan.feature.reader.presentation.BookSettingsSheet
import com.vovoscan.feature.reader.presentation.BookText
import com.vovoscan.feature.reader.presentation.ContentsRow
import com.vovoscan.feature.reader.presentation.ReaderIntent

/**
 * Book settings (gear in the top bar): text size, line spacing, margins, font, justification, the page's colours.
 * Every change is applied to the page behind the sheet at once; the choices come ready from `ReaderUiState.book`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BookSettingsSheet(
    sheet: BookSettingsSheet,
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
            Text(BookText.SETTINGS_TITLE, style = MaterialTheme.typography.titleMedium)

            SheetSection(BookText.TEXT_SIZE)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepButton("A−", 15, BookText.SMALLER, sheet.textSize.canShrink) { onIntent(BookIntent.TextSmaller) }
                Text(sheet.textSize.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                StepButton("A+", 19, BookText.BIGGER, sheet.textSize.canGrow) { onIntent(BookIntent.TextBigger) }
                if (sheet.textSize.canReset) Chip(BookText.RESET, false) { onIntent(BookIntent.ResetTextSize) }
            }
            SheetHelp(BookText.TEXT_SIZE_HELP)

            SheetSection(BookText.LINE_SPACING)
            FlowChips { sheet.lineSpacings.forEach { Chip(it.label, it.selected) { onIntent(BookIntent.SetLineSpacing(it.spacing)) } } }

            SheetSection(BookText.MARGINS)
            FlowChips { sheet.margins.forEach { Chip(it.label, it.selected) { onIntent(BookIntent.SetMargins(it.margins)) } } }

            SheetSection(BookText.FONT)
            FlowChips {
                sheet.fonts.forEach {
                    // Each font's name is written in that font.
                    FontChip(it.label, it.font, it.selected) { onIntent(BookIntent.SetFont(it.font)) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(BookText.JUSTIFY, style = MaterialTheme.typography.bodyMedium)
                    SheetHelp(BookText.JUSTIFY_HELP)
                }
                Switch(
                    checked = sheet.justify,
                    onCheckedChange = { onIntent(BookIntent.SetJustify(it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
                )
            }

            SheetSection(BookText.THEME)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                sheet.themes.forEach { option ->
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .background(rgb(option.background), shape)
                            .border(if (option.selected) 2.dp else 1.dp, if (option.selected) Brand.Red else Brand.Line, shape)
                            .clickable(role = Role.RadioButton) { onIntent(BookIntent.SetTheme(option.theme)) }
                            .semantics { selected = option.selected }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Aa", style = MaterialTheme.typography.titleMedium.copy(color = rgb(option.text)))
                        Text(option.label, style = MaterialTheme.typography.bodySmall.copy(color = rgb(option.text)))
                    }
                }
            }
            SheetHelp(BookText.THEME_HELP)
        }
    }
}

/**
 * The book's contents (button in the top bar): its parts when it has several, its table of contents (nested), the
 * rows of the section being read marked in red. A tap opens the row and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookContentsSheet(
    rows: List<ContentsRow>,
    onIntent: (ReaderIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    // Opens on the section being read.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (rows.indexOfFirst { it.current && !it.part } - 2).coerceAtLeast(0))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
        contentColor = Brand.Paper,
    ) {
        Text(BookText.CONTENTS, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = row.enabled, role = Role.Button) {
                            onIntent(BookIntent.ChooseContents(row.key))
                            onDismiss()
                        }.semantics { selected = row.current }
                        .padding(start = 20.dp + 16.dp * row.depth, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(22.dp).background(if (row.current && !row.part) Brand.Red else Color.Transparent))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        row.label,
                        style =
                            if (row.part) {
                                MaterialTheme.typography.titleMedium.copy(color = if (row.current) Brand.Red else Brand.Paper)
                            } else {
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = if (row.current) Brand.Paper else Brand.TextSoft,
                                    fontWeight = if (row.current) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepButton(
    text: String,
    size: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .size(44.dp)
            .background(Brand.Ink, shape)
            .border(1.dp, if (enabled) Brand.Paper else Brand.Line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) Brand.Paper else Brand.Line,
                    fontSize = size.sp,
                ),
        )
    }
}

@Composable
private fun FontChip(
    label: String,
    font: BookFont,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        label,
        style =
            MaterialTheme.typography.bodyMedium.copy(
                color = if (selected) Brand.Ink else Brand.Paper,
                fontFamily = if (font == BookFont.Serif) FontFamily.Serif else FontFamily.SansSerif,
            ),
        modifier =
            Modifier
                .background(if (selected) Brand.Paper else Brand.Ink2, shape)
                .border(1.dp, if (selected) Brand.Paper else Brand.Line, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun SheetSection(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun SheetHelp(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** `0xRRGGBB` from the shared state. */
internal fun rgb(value: Long): Color = Color(0xFF000000 or value)
