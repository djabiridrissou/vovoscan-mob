package com.vovoscan.app.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.sources.presentation.SourceDetailIntent
import com.vovoscan.feature.sources.presentation.SourceDetailViewModel
import com.vovoscan.feature.sources.presentation.SourceLabels
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Detail of a source: what it is, where it connects, update, settings, show/hide, remove. No logic here. */
@Composable
fun SourceDetailScreen(
    sourceKey: String,
    onBack: () -> Unit,
    viewModel: SourceDetailViewModel = koinViewModel(key = "source:$sourceKey") { parametersOf(sourceKey) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onIntent
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GhostButton("← Back", onBack)
        if (state.pendingSync) OfflineNotice(SourceLabels.PENDING)
        state.error?.let { MessageBanner(it, error = true) { send(SourceDetailIntent.DismissMessage) } }
        state.notice?.let { MessageBanner(it, error = false) { send(SourceDetailIntent.DismissMessage) } }
        val info = state.info ?: return@Column
        SourceInfoBlock(info)

        state.update?.let { update ->
            SourceCard {
                Text("Update available", style = MaterialTheme.typography.titleMedium)
                Text(update.versionLabel, style = MaterialTheme.typography.bodyMedium)
                update.changes?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.TextSoft)) }
                update.newHostsLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Yellow)) }
                PrimaryButton(
                    if (state.working) "Updating…" else "${update.actionLabel} →",
                    { send(SourceDetailIntent.Update) },
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                    enabled = !state.working,
                )
            }
        }

        if (state.settings.isNotEmpty()) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            state.settingsNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Yellow)) }
            if (state.settingsEditable) {
                state.settings.forEach { field ->
                    SettingInput(field) { send(SourceDetailIntent.SettingChanged(field.name, it)) }
                }
                PrimaryButton("Save →", { send(SourceDetailIntent.SaveSettings) }, enabled = state.canSaveSettings)
            } else {
                state.settings.forEach { SettingValue(it) }
            }
        }

        GhostButton(state.visibilityLabel, { send(SourceDetailIntent.ToggleVisibility) })
        if (state.canRemove) GhostButton("Remove", { send(SourceDetailIntent.Remove) })
    }

    state.hostsConfirmation?.let { message ->
        BrandAlert(
            title = "New connections",
            message = message,
            confirmLabel = "Continue",
            onConfirm = { send(SourceDetailIntent.ConfirmHosts) },
            onCancel = { send(SourceDetailIntent.CancelHosts) },
        )
    }
    if (state.confirmingRemoval) {
        BrandAlert(
            title = state.removalTitle,
            message = state.removalMessage,
            confirmLabel = "Remove",
            onConfirm = { send(SourceDetailIntent.ConfirmRemove) },
            onCancel = { send(SourceDetailIntent.CancelRemove) },
        )
    }
}
