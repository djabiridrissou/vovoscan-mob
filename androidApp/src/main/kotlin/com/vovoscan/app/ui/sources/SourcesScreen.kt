package com.vovoscan.app.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.sources.presentation.SourceChip
import com.vovoscan.feature.sources.presentation.SourcesIntent
import com.vovoscan.feature.sources.presentation.SourcesViewModel
import org.koin.compose.viewmodel.koinViewModel

/** "Sources" screen: show or hide each source, and reorder it. No logic here (SourcesViewModel). */
@Composable
fun SourcesScreen(viewModel: SourcesViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Kicker("Sources")
                Text("Choose what you read.", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Sources read on your device are fetched by your phone, never through vovoscan.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.pendingNotice?.let { OfflineNotice(it) }
                state.error?.let { ErrorText(it) }
            }
        }
        items(state.rows, key = { it.key }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brand.Ink2, RoundedCornerShape(12.dp))
                    .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${row.kindsLabel} ✦ ${row.engineLabel}", style = MaterialTheme.typography.bodySmall)
                }
                Arrow("↑", row.canMoveUp) { viewModel.onIntent(SourcesIntent.MoveUp(row.key)) }
                Arrow("↓", row.canMoveDown) { viewModel.onIntent(SourcesIntent.MoveDown(row.key)) }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = row.enabled,
                    onCheckedChange = { viewModel.onIntent(SourcesIntent.Toggle(row.key)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
                )
            }
        }
    }
}

@Composable
private fun Arrow(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        glyph,
        style = MaterialTheme.typography.titleMedium.copy(color = if (enabled) Brand.Paper else Brand.Line),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Source picker at the top of the catalog: the visible sources, in the chosen order. */
@Composable
fun SourcePicker(
    chips: List<SourceChip>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    if (chips.size < 2) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips, key = { it.key }) { chip ->
            val active = chip.key == selected
            Text(
                chip.name,
                style = MaterialTheme.typography.labelLarge.copy(color = if (active) Brand.Ink else Brand.Paper),
                modifier =
                    Modifier
                        .background(if (active) Brand.Red else Brand.Ink2, RoundedCornerShape(999.dp))
                        .border(1.dp, if (active) Brand.Red else Brand.Line, RoundedCornerShape(999.dp))
                        .clickable { onSelect(chip.key) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
