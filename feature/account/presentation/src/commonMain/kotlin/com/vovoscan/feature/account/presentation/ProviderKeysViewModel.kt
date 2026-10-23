package com.vovoscan.feature.account.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.DeviceTimeZone
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.UtcOffset
import com.vovoscan.feature.account.domain.KeyRules
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.ProviderKey
import com.vovoscan.feature.account.domain.ProviderKeys
import com.vovoscan.feature.account.domain.ProviderKeysRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A stored key, as shown: never the secret. */
data class KeyRow(
    val id: String,
    val provider: Provider,
    val providerLabel: String,
    /** "•••• abcd". */
    val hintLabel: String,
    val label: String?,
    /** "Added Oct 20, 2026". */
    val addedLabel: String,
    /** "Last used Oct 21, 2026" or "Not used yet". */
    val lastUsedLabel: String,
)

/** One provider's place on the screen: its key if any, and the action ("Add a key" / "Replace"). */
data class ProviderSlot(
    val provider: Provider,
    val providerLabel: String,
    val purpose: String,
    val key: KeyRow?,
    val actionLabel: String,
)

/** The confirmation before removing a key. */
data class RemovalPrompt(
    val keyId: String,
    val title: String,
    val message: String,
    val confirmLabel: String = AccountText.REMOVE_KEY,
    val cancelLabel: String = AccountText.KEEP_KEY,
)

data class ProviderKeysUiState(
    /** False: the server stores no keys ([disabledNotice]); adding is not offered. */
    val enabled: Boolean = true,
    val disabledNotice: String? = null,
    val slots: List<ProviderSlot> = Provider.entries.map { emptySlot(it) },
    /** The list was read at least once (from the device or the server). */
    val loaded: Boolean = false,
    val freshness: Freshness = Freshness.Refreshing,
    // The add / replace form. The secret itself never appears in this state.
    val formOpen: Boolean = false,
    val formProvider: Provider = Provider.Anthropic,
    val formTitle: String = "",
    val formLabel: String = "",
    /** Something was typed in the secret field (the text stays in the field and in a private draft until sent). */
    val secretEntered: Boolean = false,
    /** Changes every time the secret field must be emptied (right after sending, or when the form closes). */
    val secretFieldResets: Int = 0,
    val secretError: String? = null,
    val labelError: String? = null,
    val submitting: Boolean = false,
    val formError: String? = null,
    val removal: RemovalPrompt? = null,
    val removing: Boolean = false,
    /** Short confirmation ("Key saved."). */
    val notice: String? = null,
    val error: String? = null,
) {
    val submitLabel: String get() = if (submitting) AccountText.CHECKING_KEY else AccountText.SAVE_KEY
    val canSubmit: Boolean get() = enabled && secretEntered && !submitting
    val secretPlaceholder: String get() = AccountText.SECRET_PLACEHOLDER
    val labelPlaceholder: String get() = AccountText.LABEL_PLACEHOLDER
    val privacyNote: String get() = AccountText.KEY_PRIVACY

    /** The discreet banner while the copy on the device is shown offline. */
    val offlineNotice: String? get() = if (freshness == Freshness.Offline) AccountText.OFFLINE_COPY else null
}

internal fun emptySlot(provider: Provider) =
    ProviderSlot(provider, provider.label, AccountText.purpose(provider), null, AccountText.ADD_KEY)

sealed interface ProviderKeysIntent {
    data object Refresh : ProviderKeysIntent

    /** "Add a key" or "Replace" on a provider: opens the form for it. */
    data class OpenForm(
        val provider: Provider,
    ) : ProviderKeysIntent

    data class ChooseProvider(
        val provider: Provider,
    ) : ProviderKeysIntent

    /** Each keystroke of the secret field. Kept in a private draft, never in the state, until it is sent. */
    data class SecretChanged(
        val value: String,
    ) : ProviderKeysIntent

    data class LabelChanged(
        val value: String,
    ) : ProviderKeysIntent

    data object Submit : ProviderKeysIntent

    data object CloseForm : ProviderKeysIntent

    data class AskRemove(
        val keyId: String,
    ) : ProviderKeysIntent

    data object ConfirmRemove : ProviderKeysIntent

    data object CancelRemove : ProviderKeysIntent

    data object DismissMessage : ProviderKeysIntent
}

/**
 * The user's own provider keys (Anthropic, ElevenLabs): list, add or replace, remove. The list is kept on the device;
 * the secret is typed once, sent once in the request body, then forgotten: it is dropped from the draft the moment it
 * is sent, whatever the answer, and never stored, logged or put in the state.
 */
class ProviderKeysViewModel(
    private val repository: ProviderKeysRepository,
    private val offset: UtcOffset = DeviceTimeZone,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderKeysUiState())
    val state: StateFlow<ProviderKeysUiState> = mutableState.asStateFlow()
    private var keys: ProviderKeys? = null

    /** The secret being typed. Private, outside the state, emptied as soon as it is sent. */
    private var secretDraft: String = ""

    init {
        repository.observeKeys().onEach { list -> if (list != null) show(list) }.launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: ProviderKeysIntent) {
        when (intent) {
            ProviderKeysIntent.Refresh -> refresh()
            is ProviderKeysIntent.OpenForm -> openForm(intent.provider)
            is ProviderKeysIntent.ChooseProvider -> openForm(intent.provider, keepLabel = true)
            is ProviderKeysIntent.SecretChanged -> {
                secretDraft = intent.value
                mutableState.update { it.copy(secretEntered = intent.value.isNotBlank(), secretError = null, formError = null) }
            }
            is ProviderKeysIntent.LabelChanged ->
                mutableState.update {
                    it.copy(
                        formLabel = intent.value,
                        labelError = null,
                        formError = null,
                    )
                }
            ProviderKeysIntent.Submit -> submit()
            ProviderKeysIntent.CloseForm -> {
                secretDraft = ""
                mutableState.update {
                    it.copy(formOpen = false, secretEntered = false, secretFieldResets = it.secretFieldResets + 1, formError = null)
                }
            }
            is ProviderKeysIntent.AskRemove -> {
                val key = keys?.keys?.firstOrNull { it.id == intent.keyId } ?: return
                mutableState.update {
                    it.copy(removal = RemovalPrompt(key.id, AccountText.removeTitle(key.provider), AccountText.removeMessage(key.provider)))
                }
            }
            ProviderKeysIntent.ConfirmRemove -> remove()
            ProviderKeysIntent.CancelRemove -> mutableState.update { it.copy(removal = null) }
            ProviderKeysIntent.DismissMessage -> mutableState.update { it.copy(notice = null, error = null) }
        }
    }

    private fun openForm(
        provider: Provider,
        keepLabel: Boolean = false,
    ) {
        if (!state.value.enabled) {
            mutableState.update { it.copy(error = AccountText.KEYS_DISABLED) }
            return
        }
        val replacing = keys?.keyFor(provider) != null
        mutableState.update {
            it.copy(
                formOpen = true,
                formProvider = provider,
                formTitle = AccountText.formTitle(provider, replacing),
                formLabel = if (keepLabel) it.formLabel else "",
                secretError = null,
                labelError = null,
                formError = null,
                notice = null,
            )
        }
    }

    private fun submit() {
        val current = state.value
        if (current.submitting || !current.formOpen) return
        if (!current.enabled) {
            mutableState.update { it.copy(formError = AccountText.KEYS_DISABLED) }
            return
        }
        val secretError =
            when {
                secretDraft.isBlank() -> AccountText.SECRET_MISSING
                !KeyRules.secretLooksValid(secretDraft) -> AccountText.SECRET_INVALID
                else -> null
            }
        val labelError = if (KeyRules.labelValid(current.formLabel)) null else AccountText.LABEL_TOO_LONG
        if (secretError != null || labelError != null) {
            mutableState.update { it.copy(secretError = secretError, labelError = labelError) }
            return
        }
        // The secret leaves the ViewModel here: the draft and the field are emptied before the request even starts.
        val secret = secretDraft
        secretDraft = ""
        val provider = current.formProvider
        val label = current.formLabel.trim().ifEmpty { null }
        mutableState.update {
            it.copy(submitting = true, secretEntered = false, secretFieldResets = it.secretFieldResets + 1, formError = null)
        }
        viewModelScope.launch {
            val result = repository.add(provider, secret, label)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(submitting = false, formOpen = false, formLabel = "", notice = AccountText.KEY_SAVED)
                    is AppResult.Err -> it.copy(submitting = false, formError = AccountText.keyError(result.error))
                }
            }
        }
    }

    private fun remove() {
        val prompt = state.value.removal ?: return
        if (state.value.removing) return
        mutableState.update { it.copy(removing = true, error = null) }
        viewModelScope.launch {
            val result = repository.remove(prompt.keyId)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(removing = false, removal = null, notice = AccountText.KEY_REMOVED)
                    is AppResult.Err -> it.copy(removing = false, removal = null, error = AccountText.keyError(result.error))
                }
            }
        }
    }

    private fun refresh() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.refreshKeys()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh)
                    // Offline: the list kept on the device stays, with the discreet banner.
                    is AppResult.Err ->
                        it.copy(
                            freshness = Freshness.Offline,
                            error = if (result.error == AppError.Network) null else AccountText.keyError(result.error),
                        )
                }
            }
        }
    }

    private fun show(list: ProviderKeys) {
        keys = list
        mutableState.update {
            it.copy(
                enabled = list.enabled,
                disabledNotice = if (list.enabled) null else AccountText.KEYS_DISABLED,
                slots = Provider.entries.map { provider -> slot(provider, list.keyFor(provider)) },
                loaded = true,
                formOpen = it.formOpen && list.enabled,
            )
        }
    }

    private fun slot(
        provider: Provider,
        key: ProviderKey?,
    ): ProviderSlot =
        if (key == null) {
            emptySlot(provider)
        } else {
            ProviderSlot(
                provider,
                provider.label,
                AccountText.purpose(provider),
                KeyRow(
                    id = key.id,
                    provider = provider,
                    providerLabel = provider.label,
                    hintLabel = AccountText.hint(key.hint),
                    label = key.label,
                    addedLabel = AccountText.added(AccountText.date(key.createdAtMillis, offset)),
                    lastUsedLabel =
                        key.lastUsedAtMillis?.let { AccountText.lastUsed(AccountText.date(it, offset)) } ?: AccountText.NEVER_USED,
                ),
                AccountText.REPLACE_KEY,
            )
        }

    override fun onCleared() {
        secretDraft = ""
        super.onCleared()
    }
}
