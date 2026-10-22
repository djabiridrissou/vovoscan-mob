package com.vovoscan.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.auth.domain.AccountRepository
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Credentials
import com.vovoscan.feature.auth.domain.DeviceSession
import com.vovoscan.feature.auth.domain.ProfileChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A place the account is signed in: "Mac · Chrome", "Browser ✦ Network 203.0.113.0 ✦ Active 21 Oct 2026 at 14:03". */
data class DeviceRow(
    val id: String,
    val title: String,
    val detail: String,
    val current: Boolean,
    val signingOut: Boolean,
)

data class AccountUiState(
    val name: String = "",
    val savedName: String = "",
    val savingName: Boolean = false,
    val email: String = "",
    val emailVerified: Boolean = true,
    val emailFormOpen: Boolean = false,
    val emailDraft: String = "",
    val emailSending: Boolean = false,
    /** "Check {email}: open the link we sent to confirm." */
    val emailSent: String? = null,
    val hasPassword: Boolean = true,
    val passwordSaving: Boolean = false,
    val passwordDone: String? = null,
    val devices: List<DeviceRow> = emptyList(),
    val devicesLoading: Boolean = true,
    val devicesError: String? = null,
    val signingOutOthers: Boolean = false,
    /** "Your account will be deleted on 21 November 2026." while it is scheduled. */
    val deletionScheduled: String? = null,
    val deleting: Boolean = false,
    /** The owner account can't be deleted. */
    val canDelete: Boolean = true,
    val notice: String? = null,
    val error: String? = null,
    /** Bumped whenever the password fields must be emptied (sent, whatever the answer). */
    val secretFieldResets: Int = 0,
) {
    val lead: String get() = t("account.lead")
    val profileTitle: String get() = t("account.profile.title")
    val nameLabel: String get() = t("account.profile.name")
    val nameHint: String get() = t("account.profile.nameHint")
    val canSaveName: Boolean get() = !savingName && name.trim().isNotEmpty() && name.trim() != savedName
    val saveLabel: String get() = if (savingName) t("common.saving") else t("common.save")

    val emailTitle: String get() = t("account.email.title")
    val emailStatus: String get() = t(if (emailVerified) "account.email.verified" else "account.email.unverified")
    val changeEmailLabel: String get() = t("account.email.change")
    val emailFieldLabel: String get() = t("account.email.new")
    val emailPasswordHint: String get() = t("account.email.passwordHint")

    val passwordTitle: String get() = t("account.password.title")
    val currentPasswordLabel: String get() = t("account.password.current")
    val replacementPasswordLabel: String get() = t("account.password.new")
    val passwordHint: String get() = t("account.password.hint")
    val changePasswordLabel: String get() = if (passwordSaving) t("common.saving") else t("account.password.change")
    val noPasswordNote: String? get() = if (hasPassword) null else t("account.password.none")

    val devicesTitle: String get() = t("account.devices.title")
    val devicesLead: String get() = t("account.devices.lead")
    val signOutDeviceLabel: String get() = t("account.devices.signOut")
    val signOutOthersLabel: String get() = t("account.devices.signOutOthers")
    val hasOtherDevices: Boolean get() = devices.any { !it.current }

    val deleteTitle: String get() = t("account.delete.title")
    val deleteLead: String get() = t("account.delete.lead", "days" to DELETION_GRACE_DAYS)
    val deleteConfirm: String get() = t("account.delete.confirm")
    val deleteLabel: String get() = t("account.delete.action")
    val ownerNote: String? get() = if (canDelete) null else t("account.delete.owner")
    val keepLabel: String get() = t("deletion.keep")
    val passwordLabel: String get() = t("common.password")

    companion object {
        const val DELETION_GRACE_DAYS = 30
    }
}

sealed interface AccountIntent {
    data class Name(
        val value: String,
    ) : AccountIntent

    data object SaveName : AccountIntent

    data object OpenEmailForm : AccountIntent

    data object CloseEmailForm : AccountIntent

    data class NewEmail(
        val value: String,
    ) : AccountIntent

    /** The password confirming the change; never kept in the state. */
    data class SubmitEmail(
        val password: String,
    ) : AccountIntent

    data class SubmitPassword(
        val current: String,
        // Not `new`: Objective-C reserves the `new` method family.
        val replacement: String,
    ) : AccountIntent

    data object RefreshDevices : AccountIntent

    data class SignOutDevice(
        val id: String,
    ) : AccountIntent

    data object SignOutOthers : AccountIntent

    data class Delete(
        val password: String,
    ) : AccountIntent

    data object KeepAccount : AccountIntent

    data object DismissMessage : AccountIntent
}

/**
 * Settings › Account: profile, email, password, devices and deletion (i18n/SPEC.md §3). Every write needs the
 * network; passwords travel in the intent that sends them and are never kept in the state.
 */
class AccountViewModel(
    private val auth: AuthRepository,
    private val account: AccountRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = mutableState.asStateFlow()

    init {
        auth.session
            .onEach { session ->
                if (session == null) return@onEach
                mutableState.update {
                    it.copy(
                        // A name being edited stays as typed.
                        name = if (it.name == it.savedName) session.displayName else it.name,
                        savedName = session.displayName,
                        email = session.email,
                        emailVerified = session.emailVerified,
                        hasPassword = session.hasPassword,
                        canDelete = !session.isSuperAdmin,
                        deletionScheduled = session.deletionScheduledAtMillis?.let { at -> t("deletion.banner", "date" to at) },
                    )
                }
            }.launchIn(viewModelScope)
        refreshDevices()
    }

    fun onIntent(intent: AccountIntent) {
        when (intent) {
            is AccountIntent.Name -> mutableState.update { it.copy(name = intent.value, notice = null, error = null) }
            AccountIntent.SaveName -> saveName()
            AccountIntent.OpenEmailForm -> mutableState.update { it.copy(emailFormOpen = true, emailSent = null, error = null) }
            AccountIntent.CloseEmailForm -> mutableState.update { it.copy(emailFormOpen = false, emailDraft = "") }
            is AccountIntent.NewEmail -> mutableState.update { it.copy(emailDraft = intent.value, error = null) }
            is AccountIntent.SubmitEmail -> submitEmail(intent.password)
            is AccountIntent.SubmitPassword -> submitPassword(intent.current, intent.replacement)
            AccountIntent.RefreshDevices -> refreshDevices()
            is AccountIntent.SignOutDevice -> signOutDevice(intent.id)
            AccountIntent.SignOutOthers -> signOutOthers()
            is AccountIntent.Delete -> delete(intent.password)
            AccountIntent.KeepAccount -> keep()
            AccountIntent.DismissMessage -> mutableState.update { it.copy(notice = null, error = null) }
        }
    }

    private fun saveName() {
        val current = state.value
        if (!current.canSaveName) return
        mutableState.update { it.copy(savingName = true, notice = null, error = null) }
        viewModelScope.launch {
            val result = auth.updateProfile(ProfileChange(displayName = current.name.trim()))
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(savingName = false, name = result.value.displayName, notice = t("common.saved"))
                    is AppResult.Err -> it.copy(savingName = false, error = message(result.error))
                }
            }
        }
    }

    private fun submitEmail(password: String) {
        val current = state.value
        if (current.emailSending) return
        val address = current.emailDraft.trim()
        val invalid = Credentials.emailError(address) ?: if (password.isEmpty()) t("account.email.passwordHint") else null
        if (invalid != null) {
            mutableState.update { it.copy(error = invalid, secretFieldResets = it.secretFieldResets + 1) }
            return
        }
        mutableState.update { it.copy(emailSending = true, error = null, secretFieldResets = it.secretFieldResets + 1) }
        viewModelScope.launch {
            val result = account.changeEmail(password, address)
            mutableState.update {
                when (result) {
                    is AppResult.Ok ->
                        it.copy(
                            emailSending = false,
                            emailFormOpen = false,
                            emailDraft = "",
                            emailSent =
                                t(
                                    "account.email.sent",
                                    "email" to address,
                                ),
                        )
                    is AppResult.Err -> it.copy(emailSending = false, error = message(result.error))
                }
            }
        }
    }

    private fun submitPassword(
        currentPassword: String,
        newPassword: String,
    ) {
        if (state.value.passwordSaving) return
        val invalid =
            when {
                currentPassword.isEmpty() -> t("account.password.current")
                else -> Credentials.passwordError(newPassword)
            }
        if (invalid != null) {
            mutableState.update { it.copy(error = invalid, passwordDone = null, secretFieldResets = it.secretFieldResets + 1) }
            return
        }
        mutableState.update {
            it.copy(
                passwordSaving = true,
                passwordDone = null,
                error = null,
                secretFieldResets = it.secretFieldResets + 1,
            )
        }
        viewModelScope.launch {
            val result = account.changePassword(currentPassword, newPassword)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(passwordSaving = false, passwordDone = t("account.password.done"))
                    is AppResult.Err -> it.copy(passwordSaving = false, error = message(result.error))
                }
            }
            if (result is AppResult.Ok) refreshDevices()
        }
    }

    private fun refreshDevices() {
        mutableState.update { it.copy(devicesLoading = true, devicesError = null) }
        viewModelScope.launch {
            val result = account.sessions()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(devicesLoading = false, devices = result.value.map(::row))
                    is AppResult.Err -> it.copy(devicesLoading = false, devicesError = message(result.error))
                }
            }
        }
    }

    private fun signOutDevice(id: String) {
        val target = state.value.devices.firstOrNull { it.id == id } ?: return
        if (target.current || target.signingOut) return
        mutableState.update { it.copy(devices = it.devices.map { row -> if (row.id == id) row.copy(signingOut = true) else row }) }
        viewModelScope.launch {
            val result = account.revokeSession(id)
            val gone =
                result is AppResult.Ok ||
                    (result as? AppResult.Err)?.error.let { it is AppError.Rejected && it.code == "SESSION_NOT_FOUND" }
            mutableState.update {
                if (gone) {
                    it.copy(devices = it.devices.filterNot { row -> row.id == id })
                } else {
                    it.copy(
                        devices = it.devices.map { row -> if (row.id == id) row.copy(signingOut = false) else row },
                        error = message((result as AppResult.Err).error),
                    )
                }
            }
        }
    }

    private fun signOutOthers() {
        if (state.value.signingOutOthers) return
        mutableState.update { it.copy(signingOutOthers = true, notice = null, error = null) }
        viewModelScope.launch {
            val result = account.revokeOtherSessions()
            mutableState.update {
                when (result) {
                    is AppResult.Ok ->
                        it.copy(
                            signingOutOthers = false,
                            devices = it.devices.filter { row -> row.current },
                            notice = t("account.devices.revoked", "count" to result.value),
                        )
                    is AppResult.Err -> it.copy(signingOutOthers = false, error = message(result.error))
                }
            }
        }
    }

    private fun delete(password: String) {
        val current = state.value
        if (current.deleting || !current.canDelete) return
        if (password.isEmpty()) {
            mutableState.update { it.copy(error = t("account.delete.confirm"), secretFieldResets = it.secretFieldResets + 1) }
            return
        }
        mutableState.update { it.copy(deleting = true, error = null, secretFieldResets = it.secretFieldResets + 1) }
        viewModelScope.launch {
            val result = account.scheduleDeletion(password)
            mutableState.update {
                when (result) {
                    // The session's deletion date fills `deletionScheduled`.
                    is AppResult.Ok -> it.copy(deleting = false)
                    is AppResult.Err -> it.copy(deleting = false, error = message(result.error))
                }
            }
            if (result is AppResult.Ok) refreshDevices()
        }
    }

    private fun keep() {
        if (state.value.deleting) return
        mutableState.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            val result = account.cancelDeletion()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(deleting = false)
                    is AppResult.Err -> it.copy(deleting = false, error = message(result.error))
                }
            }
        }
    }

    private fun row(session: DeviceSession): DeviceRow {
        val client = t(if (session.client == "mobile") "account.devices.app" else "account.devices.browser")
        val seen = session.lastUsedAtMillis ?: session.createdAtMillis
        val detail =
            listOfNotNull(
                if (session.current) t("account.devices.current") else client,
                session.ip?.let { t("account.devices.network", "ip" to it) },
                t("account.devices.seen", "when" to seen),
            ).joinToString(" ✦ ")
        return DeviceRow(session.id, session.device.ifBlank { client }, detail, session.current, signingOut = false)
    }

    /** Every account call needs the network: offline says so in the brand's words; codes read from the catalogue. */
    private fun message(error: AppError): String = if (error == AppError.Network) t("account.offline") else error.userMessage()
}
