package com.vovoscan.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Credentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SignInMode { SignIn, Invite }

data class SignInUiState(
    val mode: SignInMode = SignInMode.SignIn,
    val email: String = "",
    val password: String = "",
    val inviteCode: String = "",
    val displayName: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val inviteError: String? = null,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val title: String get() = if (mode == SignInMode.SignIn) "Sign in." else "Redeem your invite."
    val submitLabel: String get() = if (mode == SignInMode.SignIn) "Sign in →" else "Create my account →"
    val switchLabel: String get() = if (mode == SignInMode.SignIn) "I have an invite" else "I already have an account"
}

sealed interface SignInIntent {
    data class Email(
        val value: String,
    ) : SignInIntent

    data class Password(
        val value: String,
    ) : SignInIntent

    data class InviteCode(
        val value: String,
    ) : SignInIntent

    data class DisplayName(
        val value: String,
    ) : SignInIntent

    data object SwitchMode : SignInIntent

    data object Submit : SignInIntent
}

/** Sign-in or sign-up with an invite. All screen logic lives here; Compose and SwiftUI only display it. */
class SignInViewModel(
    private val auth: AuthRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = mutableState.asStateFlow()

    fun onIntent(intent: SignInIntent) {
        when (intent) {
            is SignInIntent.Email -> mutableState.update { it.copy(email = intent.value, emailError = null, error = null) }
            is SignInIntent.Password -> mutableState.update { it.copy(password = intent.value, passwordError = null, error = null) }
            is SignInIntent.InviteCode -> mutableState.update { it.copy(inviteCode = intent.value, inviteError = null, error = null) }
            is SignInIntent.DisplayName -> mutableState.update { it.copy(displayName = intent.value, error = null) }
            SignInIntent.SwitchMode ->
                mutableState.update {
                    SignInUiState(mode = if (it.mode == SignInMode.SignIn) SignInMode.Invite else SignInMode.SignIn, email = it.email)
                }
            SignInIntent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = state.value
        if (current.submitting) return
        val invite = current.mode == SignInMode.Invite
        val checked =
            current.copy(
                emailError = Credentials.emailError(current.email),
                // No length rule on sign-in: an older password must still be accepted.
                passwordError = if (invite) Credentials.passwordError(current.password) else null,
                inviteError = if (invite) Credentials.inviteError(current.inviteCode) else null,
            )
        if (checked.emailError != null || checked.passwordError != null || checked.inviteError != null) {
            mutableState.value = checked
            return
        }
        mutableState.value = checked.copy(submitting = true, error = null)
        viewModelScope.launch {
            val result =
                if (invite) {
                    auth.redeemInvite(Credentials.normalizeInvite(current.inviteCode), current.email, current.password, current.displayName)
                } else {
                    auth.signIn(current.email, current.password)
                }
            mutableState.update {
                when (result) {
                    // Navigation follows `AuthRepository.session`; the password does not stay in memory.
                    is AppResult.Ok -> it.copy(submitting = false, password = "")
                    is AppResult.Err -> it.copy(submitting = false, error = result.error.userMessage())
                }
            }
        }
    }
}
