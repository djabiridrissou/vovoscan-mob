package com.vovoscan.app.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.settings.Chip
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.LanguageRow
import com.vovoscan.core.common.i18n.RichRun
import com.vovoscan.feature.auth.presentation.LanguageChoiceUi

/** A bordered block of the account and preference screens (iOS `Card`). */
@Composable
internal fun Card(
    title: String? = null,
    highlight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, if (highlight) Brand.Red else Brand.Line, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        content()
    }
}

@Composable
internal fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Cyan))
}

/** A label, the value chosen, and a tap that opens its picker. */
@Composable
internal fun PickerRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brand.InputBg, RoundedCornerShape(8.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Text("▾", style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
    }
}

/** `<b>…</b>` runs from the catalogue, drawn bold. */
internal fun rich(runs: List<RichRun>): AnnotatedString =
    buildAnnotatedString {
        runs.forEach { run ->
            if (run.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run.text) } else append(run.text)
        }
    }

/**
 * The language-and-region block of the tutorial and Settings › Preferences: the app's languages (each in its own
 * words), the region in a searchable sheet, a live sample, and the time zone.
 */
@Composable
internal fun LanguageChoiceCard(
    ui: LanguageChoiceUi,
    onLocale: (String) -> Unit,
    onRegionQuery: (String) -> Unit,
    onRegion: (String?) -> Unit,
) {
    var regionsOpen by rememberSaveable { mutableStateOf(false) }
    Card {
        Text(ui.interfaceLabel, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.locales.forEach { option -> Chip(option.label, option.selected) { if (!option.selected) onLocale(option.code) } }
        }
        Text(ui.interfaceHint, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(1.dp))
        PickerRow(ui.regionTitle, ui.regionLabel) { regionsOpen = true }
        Text(ui.regionHint, style = MaterialTheme.typography.bodySmall)
        Text(ui.preview, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Yellow))
        Text("${ui.timeZoneTitle} ✦ ${ui.timeZone}", style = MaterialTheme.typography.bodySmall)
    }
    if (regionsOpen) {
        SearchSheet(
            title = ui.regionTitle,
            query = ui.regionQuery,
            onQuery = onRegionQuery,
            onDismiss = {
                regionsOpen = false
                onRegionQuery("")
            },
        ) {
            items(ui.regions, key = { it.code ?: "" }) { row ->
                CheckLine(row.label, null, row.selected) {
                    regionsOpen = false
                    onRegion(row.code)
                }
            }
        }
    }
}

/** The content-language picker: own name first, English name under it, searchable by either or the code. */
@Composable
internal fun ContentLanguageSheet(
    title: String,
    query: String,
    rows: List<LanguageRow>,
    onQuery: (String) -> Unit,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SearchSheet(title, query, onQuery, onDismiss) {
        items(rows, key = { it.code }) { row -> CheckLine(row.title, row.detail, row.selected) { onChoose(row.code) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    title: String,
    query: String,
    onQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            BrandField(I18n.text("common.search"), query, onQuery)
            LazyColumn(Modifier.weight(1f), content = rows)
        }
    }
}

@Composable
private fun CheckLine(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(color = if (selected) Brand.Red else Brand.Paper))
            if (detail != null && detail != title) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Text("✓", style = MaterialTheme.typography.titleMedium.copy(color = Brand.Red))
    }
}
