package com.vovoscan.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.account.presentation.ProviderKeysIntent
import com.vovoscan.feature.account.presentation.ProviderKeysUiState
import com.vovoscan.feature.account.presentation.ProviderKeysViewModel
import com.vovoscan.feature.account.presentation.ProviderSlot
import com.vovoscan.feature.account.presentation.ReadingSettingsIntent
import com.vovoscan.feature.account.presentation.ReadingSettingsViewModel
import com.vovoscan.feature.account.presentation.UsageIntent
import com.vovoscan.feature.account.presentation.UsageViewModel
import org.koin.compose.viewmodel.koinViewModel

/** Settings tab: the user's own provider keys, the reading model, what the keys were charged, and signing out. */
@Composable
fun SettingsScreen(
    onSignOut: () -> Unit,
    keys: ProviderKeysViewModel = koinViewModel(),
    reading: ReadingSettingsViewModel = koinViewModel(),
    usage: UsageViewModel = koinViewModel(),
) {
    val keysState by keys.state.collectAsStateWithLifecycle()
    val readingState by reading.state.collectAsStateWithLifecycle()
    val usageState by usage.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Kicker("Settings")
                Text("Your keys, your episodes.", style = MaterialTheme.typography.headlineSmall)
            }
        }

        // Keys.
        item { SectionTitle("Keys") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                keysState.offlineNotice?.let { OfflineNotice(it) }
                Text(keysState.privacyNote, style = MaterialTheme.typography.bodySmall)
                keysState.disabledNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                keysState.notice?.let { Notice(it) }
                keysState.error?.let { ErrorText(it) }
            }
        }
        items(keysState.slots, key = { it.provider.name }) { slot -> KeySlot(slot, keysState, keys::onIntent) }
        if (keysState.formOpen) item { KeyForm(keysState, keys::onIntent) }

        // Reading model.
        item { SectionTitle("Reading model") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                readingState.offlineNotice?.let { OfflineNotice(it) }
                readingState.options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Brand.Ink2, RoundedCornerShape(12.dp))
                            .border(1.dp, if (option.selected) Brand.Red else Brand.Line, RoundedCornerShape(12.dp))
                            .clickable { reading.onIntent(ReadingSettingsIntent.Select(option.model)) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(18.dp)
                                .border(2.dp, if (option.selected) Brand.Red else Brand.Muted, CircleShape)
                                .padding(4.dp)
                                .background(if (option.selected) Brand.Red else Brand.Ink2, CircleShape),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(readingState.footnote, style = MaterialTheme.typography.bodySmall)
                readingState.notice?.let { Notice(it) }
                readingState.error?.let { ErrorText(it) }
            }
        }

        // Costs.
        item { SectionTitle("Costs") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    usageState.periods.forEach { chip ->
                        Chip(chip.label, chip.selected) { usage.onIntent(UsageIntent.SelectPeriod(chip.period)) }
                    }
                }
                when {
                    usageState.loading -> Text("Loading the costs…", style = MaterialTheme.typography.bodySmall)
                    usageState.error != null -> {
                        ErrorText(usageState.error.orEmpty())
                        GhostButton("Try again", { usage.onIntent(UsageIntent.Refresh) })
                    }
                    usageState.empty -> Text(usageState.emptyLabel, style = MaterialTheme.typography.bodyMedium)
                }
                usageState.totalCostLabel?.takeIf { !usageState.loading && usageState.error == null && !usageState.empty }?.let {
                    Text(it, style = MaterialTheme.typography.headlineSmall)
                }
                usageState.totals.forEach { total ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(total.providerLabel, style = MaterialTheme.typography.bodyMedium)
                            Text(total.unitsLabel, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(total.costLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (!usageState.loading && usageState.error == null) {
            items(usageState.entries, key = { it.id }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brand.Ink2, RoundedCornerShape(12.dp))
                        .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(entry.providerLabel, entry.modelLabel).joinToString(" ✦ "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("${entry.amountLabel} ✦ ${entry.dateLabel}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(entry.costLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item { Text(usageState.footnote, style = MaterialTheme.typography.bodySmall) }

        item { Spacer(Modifier.height(8.dp)) }
        item { GhostButton("Sign out", onSignOut) }
    }

    keysState.removal?.let { prompt ->
        AlertDialog(
            onDismissRequest = { keys.onIntent(ProviderKeysIntent.CancelRemove) },
            containerColor = Brand.Ink2,
            title = { Text(prompt.title, style = MaterialTheme.typography.titleMedium) },
            text = { Text(prompt.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton({ keys.onIntent(ProviderKeysIntent.ConfirmRemove) }, enabled = !keysState.removing) {
                    Text(prompt.confirmLabel, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Danger))
                }
            },
            dismissButton = {
                TextButton({ keys.onIntent(ProviderKeysIntent.CancelRemove) }) {
                    Text(prompt.cancelLabel, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
private fun KeySlot(
    slot: ProviderSlot,
    state: ProviderKeysUiState,
    send: (ProviderKeysIntent) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(slot.providerLabel, style = MaterialTheme.typography.titleMedium)
        Text(slot.purpose, style = MaterialTheme.typography.bodySmall)
        val key = slot.key
        if (key != null) {
            Text(listOfNotNull(key.hintLabel, key.label).joinToString(" ✦ "), style = MaterialTheme.typography.bodyMedium)
            Text("${key.addedLabel} ✦ ${key.lastUsedLabel}", style = MaterialTheme.typography.bodySmall)
        }
        if (state.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                GhostButton(slot.actionLabel, { send(ProviderKeysIntent.OpenForm(slot.provider)) }, Modifier.weight(1f))
                if (key != null) GhostButton("Remove", { send(ProviderKeysIntent.AskRemove(key.id)) }, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Add or replace a key. The secret stays in this field (never in the ViewModel's state): each keystroke goes out as
 * `SecretChanged`, and the field empties itself whenever `secretFieldResets` changes. A password-type field: the
 * keyboard does not learn it, and nothing offers to save it in a password manager.
 */
@Composable
private fun KeyForm(
    state: ProviderKeysUiState,
    send: (ProviderKeysIntent) -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var label by remember { mutableStateOf(state.formLabel) }
    LaunchedEffect(state.secretFieldResets) { secret = "" }
    LaunchedEffect(state.formLabel) { if (state.formLabel.isEmpty()) label = "" }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, Brand.Red, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.formTitle, style = MaterialTheme.typography.titleMedium)
        Column(Modifier.fillMaxWidth()) {
            Text("Key", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = secret,
                onValueChange = {
                    secret = it
                    send(ProviderKeysIntent.SecretChanged(it))
                },
                singleLine = true,
                placeholder = { Text(state.secretPlaceholder, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted)) },
                isError = state.secretError != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                shape = RoundedCornerShape(8.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Brand.InputBg,
                        unfocusedContainerColor = Brand.InputBg,
                        focusedBorderColor = Brand.Red,
                        unfocusedBorderColor = Brand.Line,
                        errorBorderColor = Brand.Danger,
                        cursorColor = Brand.Red,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            state.secretError?.let { ErrorText(it) }
        }
        BrandField(
            label = "Label",
            value = label,
            onValueChange = {
                label = it
                send(ProviderKeysIntent.LabelChanged(it))
            },
            error = state.labelError,
            placeholder = state.labelPlaceholder,
        )
        state.formError?.let { ErrorText(it) }
        PrimaryButton(state.submitLabel, { send(ProviderKeysIntent.Submit) }, enabled = state.canSubmit)
        GhostButton("Cancel", { send(ProviderKeysIntent.CloseForm) })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Paper))
}

@Composable
internal fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall.copy(color = if (selected) Brand.Ink else Brand.Paper),
        modifier =
            Modifier
                .background(if (selected) Brand.Paper else Brand.Ink2, RoundedCornerShape(999.dp))
                .border(1.dp, if (selected) Brand.Paper else Brand.Line, RoundedCornerShape(999.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
