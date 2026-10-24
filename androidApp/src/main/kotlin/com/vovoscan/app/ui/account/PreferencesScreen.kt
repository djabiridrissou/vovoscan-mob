package com.vovoscan.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.account.presentation.ReadingSettingsIntent
import com.vovoscan.feature.account.presentation.ReadingSettingsViewModel
import com.vovoscan.feature.auth.presentation.PreferencesIntent
import com.vovoscan.feature.auth.presentation.PreferencesViewModel
import org.koin.compose.viewmodel.koinViewModel

/** Settings › Preferences: the app's language and region, the reading language, and the tour again. */
@Composable
fun PreferencesScreen(
    onBack: () -> Unit,
    onReplayTutorial: () -> Unit,
    viewModel: PreferencesViewModel = koinViewModel(),
    reading: ReadingSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readingState by reading.state.collectAsStateWithLifecycle()
    var readingOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GhostButton(I18n.text("common.backArrow"), onBack)
        Kicker(I18n.text("settings.tabs.preferences"))
        Text(state.language.title, style = MaterialTheme.typography.headlineSmall)
        Text(state.lead, style = MaterialTheme.typography.bodySmall)
        state.error?.let { ErrorText(it) }
        LanguageChoiceCard(
            state.language,
            onLocale = { viewModel.onIntent(PreferencesIntent.ChooseLocale(it)) },
            onRegionQuery = { viewModel.onIntent(PreferencesIntent.RegionQuery(it)) },
            onRegion = { viewModel.onIntent(PreferencesIntent.ChooseRegion(it)) },
        )
        Card(state.readingTitle) {
            Text(readingState.languageHint, style = MaterialTheme.typography.bodySmall)
            PickerRow(I18n.text("onboarding.reading.label"), readingState.languageLabel) { readingOpen = true }
            readingState.notice?.let { Notice(it) }
            readingState.error?.let { ErrorText(it) }
        }
        Card(state.safeModeTitle) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.safeModeHelp, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = state.safeMode,
                    onCheckedChange = { viewModel.onIntent(PreferencesIntent.SetSafeMode(it)) },
                    enabled = !state.savingSafeMode,
                    colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
                )
            }
        }
        Card(state.tourTitle) {
            Text(state.tourLead, style = MaterialTheme.typography.bodySmall)
            GhostButton(state.tourAction, onReplayTutorial)
        }
    }
    if (state.confirmingAdult) {
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(PreferencesIntent.CancelAdult) },
            containerColor = Brand.Ink2,
            title = { Text(state.adultTitle, style = MaterialTheme.typography.titleMedium) },
            text = { Text(state.adultBody, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton({ viewModel.onIntent(PreferencesIntent.ConfirmAdult) }) {
                    Text(state.adultAction, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Danger))
                }
            },
            dismissButton = {
                TextButton({ viewModel.onIntent(PreferencesIntent.CancelAdult) }) {
                    Text(I18n.text("common.cancel"), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
    if (readingOpen) {
        ContentLanguageSheet(
            title = I18n.text("onboarding.reading.label"),
            query = readingState.languageQuery,
            rows = readingState.languageRows,
            onQuery = { reading.onIntent(ReadingSettingsIntent.LanguageQuery(it)) },
            onChoose = {
                readingOpen = false
                reading.onIntent(ReadingSettingsIntent.ChooseLanguage(it))
            },
            onDismiss = {
                readingOpen = false
                reading.onIntent(ReadingSettingsIntent.LanguageQuery(""))
            },
        )
    }
}
