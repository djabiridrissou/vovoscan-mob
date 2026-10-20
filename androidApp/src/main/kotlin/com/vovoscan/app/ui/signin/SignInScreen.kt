package com.vovoscan.app.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.LogoMark
import com.vovoscan.app.ui.common.PrimaryButton
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
        Text("Your scans, fully voiced.", style = MaterialTheme.typography.bodySmall)
        if (state.mode == SignInMode.Invite) {
            BrandField("Invite code", state.inviteCode, { viewModel.onIntent(SignInIntent.InviteCode(it)) }, state.inviteError)
            BrandField("Display name", state.displayName, { viewModel.onIntent(SignInIntent.DisplayName(it)) })
        }
        BrandField("Email", state.email, { viewModel.onIntent(SignInIntent.Email(it)) }, state.emailError, KeyboardType.Email)
        BrandField(
            "Password",
            state.password,
            { viewModel.onIntent(SignInIntent.Password(it)) },
            state.passwordError,
            KeyboardType.Password,
            secret = true,
        )
        state.error?.let { ErrorText(it) }
        PrimaryButton(state.submitLabel, { viewModel.onIntent(SignInIntent.Submit) }, enabled = !state.submitting)
        GhostButton(state.switchLabel, { viewModel.onIntent(SignInIntent.SwitchMode) })
    }
}
