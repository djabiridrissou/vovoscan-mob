package com.vovoscan.app.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.LogoMark
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.settings.Chip
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.auth.presentation.SignInIntent
import com.vovoscan.feature.auth.presentation.SignInMode
import com.vovoscan.feature.auth.presentation.SignInViewModel
import org.koin.compose.viewmodel.koinViewModel

/** Sign-in or invite. No logic here: everything comes from `SignInViewModel` (shared with iOS). */
@Composable
fun SignInScreen(viewModel: SignInViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark()
            Spacer(Modifier.width(12.dp))
            Text("vovoscan", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
        Text(state.title, style = MaterialTheme.typography.displaySmall)
        Text(I18n.text("auth.tagline"), style = MaterialTheme.typography.bodySmall)
        // The app's own language, before any account exists: kept on the device and sent at sign-up.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.locales.forEach { option ->
                Chip(option.label, option.selected) { if (!option.selected) viewModel.onIntent(SignInIntent.ChooseLocale(option.code)) }
            }
        }
        if (state.mode == SignInMode.Invite) {
            BrandField(
                I18n.text("auth.inviteCode"),
                state.inviteCode,
                { viewModel.onIntent(SignInIntent.InviteCode(it)) },
                state.inviteError,
            )
            BrandField(I18n.text("auth.displayName"), state.displayName, { viewModel.onIntent(SignInIntent.DisplayName(it)) })
        }
        BrandField(
            I18n.text("auth.email"),
            state.email,
            { viewModel.onIntent(SignInIntent.Email(it)) },
            state.emailError,
            KeyboardType.Email,
        )
        BrandField(
            I18n.text("auth.password"),
            state.password,
            { viewModel.onIntent(SignInIntent.Password(it)) },
            state.passwordError,
            KeyboardType.Password,
            secret = true,
        )
        if (state.mode == SignInMode.Invite) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = state.ageConfirmed,
                        role = Role.Checkbox,
                        onValueChange = { viewModel.onIntent(SignInIntent.AgeConfirmed(it)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.ageConfirmed,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = Brand.Red, uncheckedColor = Brand.Muted, checkmarkColor = Brand.Ink),
                )
                Spacer(Modifier.width(10.dp))
                Text(state.ageLabel, style = MaterialTheme.typography.bodyMedium)
            }
            state.ageError?.let { ErrorText(it) }
        }
        state.error?.let { ErrorText(it) }
        PrimaryButton(state.submitLabel, { viewModel.onIntent(SignInIntent.Submit) }, enabled = !state.submitting)
        GhostButton(state.switchLabel, { viewModel.onIntent(SignInIntent.SwitchMode) })
    }
}
