package com.vovoscan.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.auth.presentation.AccountIntent
import com.vovoscan.feature.auth.presentation.AccountUiState
import com.vovoscan.feature.auth.presentation.AccountViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings › Account: profile, email, password, signed-in devices, deletion. Passwords live in this screen's fields
 * only, go out in the intent that sends them, and the fields empty whenever `secretFieldResets` changes.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onIntent
    var confirmingOthers by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { send(AccountIntent.DismissMessage) } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(I18n.text("common.backArrow"), onBack)
                Kicker(I18n.text("settings.tabs.account"))
                Text(state.name.ifBlank { state.email }, style = MaterialTheme.typography.headlineSmall)
                Text(state.lead, style = MaterialTheme.typography.bodySmall)
                state.deletionScheduled?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Yellow)) }
                state.notice?.let { Notice(it) }
                state.error?.let { ErrorText(it) }
            }
        }
        item { Profile(state, send) }
        item { Email(state, send) }
        item { Password(state, send) }
        item {
            Card(state.devicesTitle) {
                Text(state.devicesLead, style = MaterialTheme.typography.bodySmall)
                if (state.devicesLoading &&
                    state.devices.isEmpty()
                ) {
                    Text(I18n.text("common.loading"), style = MaterialTheme.typography.bodySmall)
                }
                state.devicesError?.let {
                    ErrorText(it)
                    GhostButton(I18n.text("common.tryAgain"), { send(AccountIntent.RefreshDevices) })
                }
            }
        }
        items(state.devices, key = { it.id }) { device ->
            Card(highlight = device.current) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.title, style = MaterialTheme.typography.bodyMedium)
                        Text(device.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!device.current) {
                        TextButton({ send(AccountIntent.SignOutDevice(device.id)) }, enabled = !device.signingOut) {
                            Text(state.signOutDeviceLabel, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red))
                        }
                    }
                }
            }
        }
        if (state.hasOtherDevices) {
            item { GhostButton(state.signOutOthersLabel, { if (!state.signingOutOthers) confirmingOthers = true }) }
        }
        item { Deletion(state, send) }
    }
    if (confirmingOthers) {
        AlertDialog(
            onDismissRequest = { confirmingOthers = false },
            containerColor = Brand.Ink2,
            title = { Text(state.signOutOthersLabel, style = MaterialTheme.typography.titleMedium) },
            confirmButton = {
                TextButton({
                    confirmingOthers = false
                    send(AccountIntent.SignOutOthers)
                }) { Text(state.signOutOthersLabel, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Danger)) }
            },
            dismissButton = {
                TextButton({ confirmingOthers = false }) { Text(I18n.text("common.cancel"), style = MaterialTheme.typography.labelLarge) }
            },
        )
    }
}

@Composable
private fun Profile(
    state: AccountUiState,
    send: (AccountIntent) -> Unit,
) {
    Card(state.profileTitle) {
        BrandField(state.nameLabel, state.name, { send(AccountIntent.Name(it)) }, help = state.nameHint)
        PrimaryButton(state.saveLabel, { send(AccountIntent.SaveName) }, enabled = state.canSaveName)
    }
}

@Composable
private fun Email(
    state: AccountUiState,
    send: (AccountIntent) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    LaunchedEffect(state.secretFieldResets) { password = "" }
    Card(state.emailTitle) {
        Text(state.email, style = MaterialTheme.typography.bodyMedium)
        Text(
            state.emailStatus,
            style = MaterialTheme.typography.bodySmall.copy(color = if (state.emailVerified) Brand.Cyan else Brand.Yellow),
        )
        state.emailSent?.let { Notice(it) }
        if (state.emailFormOpen) {
            BrandField(state.emailFieldLabel, state.emailDraft, { send(AccountIntent.NewEmail(it)) }, keyboard = KeyboardType.Email)
            BrandField(state.passwordLabel, password, {
                password = it
            }, keyboard = KeyboardType.Password, secret = true, help = state.emailPasswordHint)
            PrimaryButton(state.changeEmailLabel, { send(AccountIntent.SubmitEmail(password)) }, enabled = !state.emailSending)
            GhostButton(I18n.text("common.cancel"), { send(AccountIntent.CloseEmailForm) })
        } else {
            GhostButton(state.changeEmailLabel, { send(AccountIntent.OpenEmailForm) })
        }
    }
}

@Composable
private fun Password(
    state: AccountUiState,
    send: (AccountIntent) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    LaunchedEffect(state.secretFieldResets) {
        current = ""
        replacement = ""
    }
    Card(state.passwordTitle) {
        state.noPasswordNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.hasPassword) {
            BrandField(state.currentPasswordLabel, current, { current = it }, keyboard = KeyboardType.Password, secret = true)
        }
        BrandField(
            state.replacementPasswordLabel,
            replacement,
            { replacement = it },
            keyboard = KeyboardType.Password,
            secret = true,
            help = state.passwordHint,
        )
        state.passwordDone?.let { Notice(it) }
        PrimaryButton(
            state.changePasswordLabel,
            { send(AccountIntent.SubmitPassword(current, replacement)) },
            enabled = !state.passwordSaving,
        )
    }
}

@Composable
private fun Deletion(
    state: AccountUiState,
    send: (AccountIntent) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    LaunchedEffect(state.secretFieldResets) { password = "" }
    Card(state.deleteTitle) {
        when {
            state.ownerNote != null -> Text(state.ownerNote.orEmpty(), style = MaterialTheme.typography.bodySmall)
            state.deletionScheduled != null -> {
                Text(state.deletionScheduled.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(state.keepLabel, { send(AccountIntent.KeepAccount) }, enabled = !state.deleting)
            }
            else -> {
                Text(state.deleteLead, style = MaterialTheme.typography.bodySmall)
                BrandField(state.deleteConfirm, password, { password = it }, keyboard = KeyboardType.Password, secret = true)
                Column(Modifier.fillMaxWidth()) {
                    TextButton({ send(AccountIntent.Delete(password)) }, enabled = !state.deleting) {
                        Text(state.deleteLabel, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Danger))
                    }
                }
            }
        }
    }
}
