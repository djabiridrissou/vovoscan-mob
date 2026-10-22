package com.vovoscan.feature.sources.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.sources.domain.PluginCandidate
import com.vovoscan.feature.sources.domain.PluginsRepository
import com.vovoscan.feature.sources.domain.SettingField
import com.vovoscan.feature.sources.domain.SettingsRules
import com.vovoscan.feature.sources.domain.SourceItem
import com.vovoscan.feature.sources.domain.SourcesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A newer version found for a third-party source. */
data class SourceUpdateUi(
    /** "Version 1.1.0". */
    val versionLabel: String,
    val changes: String?,
    /** "This version also connects to: …"; null when it reaches no new host. */
    val newHostsLabel: String?,
    val actionLabel: String = SourceLabels.UPDATE,
)

data class SourceDetailUiState(
    val key: String = "",
    val loading: Boolean = true,
    /** The source is no longer in the list (removed here or elsewhere): close the screen. */
    val gone: Boolean = false,
    val info: SourceInfoUi? = null,
    val enabled: Boolean = false,
    /** "Hide" or "Show". */
    val visibilityLabel: String = t("sources.hide"),
    val update: SourceUpdateUi? = null,
    /** Step 2 of an update reaching new hosts: the question to confirm (ConfirmHosts / CancelHosts). */
    val hostsConfirmation: String? = null,
    val settings: List<SettingFieldUi> = emptyList(),
    /** Third-party only; an official source's settings are shown read-only. */
    val settingsEditable: Boolean = false,
    /** "Fill in the required settings to use this source." */
    val settingsNotice: String? = null,
    /** Edited and not saved yet (or an update waits for its new settings). */
    val settingsDirty: Boolean = false,
    val canRemove: Boolean = false,
    /** Removal asked: show the confirmation (ConfirmRemove / CancelRemove). */
    val confirmingRemoval: Boolean = false,
    /** "Remove Project Gutenberg?" */
    val removalTitle: String = "",
    val removalMessage: String = SourceLabels.REMOVE_MESSAGE,
    val working: Boolean = false,
    val pendingSync: Boolean = false,
    val error: String? = null,
    /** "Settings saved.", "Project Gutenberg updated." */
    val notice: String? = null,
) {
    val canSaveSettings: Boolean get() = settingsEditable && settingsDirty && !working
}

sealed interface SourceDetailIntent {
    data object ToggleVisibility : SourceDetailIntent

    /** Step 1 of an update (a second step asks to confirm new hosts, then new required settings if any). */
    data object Update : SourceDetailIntent

    data object ConfirmHosts : SourceDetailIntent

    data object CancelHosts : SourceDetailIntent

    data class SettingChanged(
        val name: String,
        val value: String,
    ) : SourceDetailIntent

    /** Saves the settings (or, during an update needing new settings, applies the update with them). */
    data object SaveSettings : SourceDetailIntent

    data object Remove : SourceDetailIntent

    data object ConfirmRemove : SourceDetailIntent

    data object CancelRemove : SourceDetailIntent

    data object DismissMessage : SourceDetailIntent
}

/**
 * Detail of a source of the list, official or third-party: what it is, where it connects, its update, its settings
 * (editable for a third-party source), show/hide and, for a third-party source, removal.
 */
class SourceDetailViewModel(
    private val key: String,
    private val sources: SourcesRepository,
    private val plugins: PluginsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourceDetailUiState(key = key))
    val state: StateFlow<SourceDetailUiState> = mutableState.asStateFlow()

    private var items: List<SourceItem> = emptyList()
    private var item: SourceItem? = null
    private var edits: Map<String, String> = emptyMap()
    private var errors: Map<String, String> = emptyMap()

    /** Update being applied: waiting for the hosts confirmation or for new required settings. */
    private var updating: PluginCandidate? = null
    private var hostsConfirmed = false

    init {
        sources
            .observe()
            .onEach { list ->
                items = list
                item = list.firstOrNull { it.key == key }
                render()
            }.launchIn(viewModelScope)
    }

    fun onIntent(intent: SourceDetailIntent) {
        when (intent) {
            SourceDetailIntent.ToggleVisibility -> toggleVisibility()
            SourceDetailIntent.Update -> startUpdate()
            SourceDetailIntent.ConfirmHosts -> {
                hostsConfirmed = true
                updating?.let(::continueUpdate)
            }
            SourceDetailIntent.CancelHosts -> cancelUpdate()
            is SourceDetailIntent.SettingChanged -> {
                edits = edits + (intent.name to intent.value)
                errors = errors - intent.name
                render()
            }
            SourceDetailIntent.SaveSettings -> saveSettings()
            SourceDetailIntent.Remove -> if (item?.thirdParty == true) mutableState.update { it.copy(confirmingRemoval = true) }
            SourceDetailIntent.ConfirmRemove -> remove()
            SourceDetailIntent.CancelRemove -> mutableState.update { it.copy(confirmingRemoval = false) }
            SourceDetailIntent.DismissMessage -> mutableState.update { it.copy(error = null, notice = null) }
        }
    }

    private fun toggleVisibility() {
        val current = item ?: return
        if (current.enabled && items.count { it.enabled } == 1) {
            mutableState.update { it.copy(error = SourceLabels.KEEP_ONE_VISIBLE) }
            return
        }
        viewModelScope.launch {
            val result = sources.save(items.map { it.key to if (it.key == key) !it.enabled else it.enabled })
            if (result is AppResult.Err &&
                result.error != AppError.Network
            ) {
                mutableState.update { it.copy(error = result.error.userMessage()) }
            }
        }
    }

    private fun startUpdate() {
        if (item?.update == null || state.value.working) return
        work {
            when (val result = plugins.updateCandidate(key)) {
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.userMessage()) }
                is AppResult.Ok -> {
                    updating = result.value
                    hostsConfirmed = false
                    continueUpdate(result.value)
                }
            }
        }
    }

    private fun continueUpdate(candidate: PluginCandidate) {
        if (candidate.newHosts.isNotEmpty() && !hostsConfirmed) {
            mutableState.update { it.copy(hostsConfirmation = SourceLabels.newHosts(candidate.newHosts)) }
            return
        }
        mutableState.update { it.copy(hostsConfirmation = null) }
        val values = SettingsRules.merge(candidate.info.settings, item?.settings.orEmpty(), edits)
        if (SettingsRules.missing(candidate.info.settings, values).isNotEmpty()) {
            // New required settings: the form shows them; SaveSettings applies the update.
            render()
            return
        }
        applyUpdate(candidate)
    }

    private fun applyUpdate(candidate: PluginCandidate) {
        work {
            when (val result = plugins.install(candidate, edits)) {
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.userMessage()) }
                is AppResult.Ok -> {
                    updating = null
                    edits = emptyMap()
                    mutableState.update { it.copy(notice = t("sources.updatedNotice", "name" to candidate.info.name)) }
                }
            }
            render()
        }
    }

    private fun cancelUpdate() {
        updating = null
        hostsConfirmed = false
        mutableState.update { it.copy(hostsConfirmation = null) }
        render()
    }

    private fun saveSettings() {
        val current = item ?: return
        if (!current.thirdParty || state.value.working) return
        val spec = spec()
        errors = SettingsRules.validate(spec, SettingsRules.merge(spec, current.settings, edits))
        if (errors.isNotEmpty()) {
            render()
            return
        }
        updating?.let {
            applyUpdate(it)
            return
        }
        work {
            when (val result = plugins.saveSettings(key, edits)) {
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.userMessage()) }
                is AppResult.Ok -> {
                    edits = emptyMap()
                    mutableState.update { it.copy(notice = t("sources.settingsSaved")) }
                }
            }
            render()
        }
    }

    private fun remove() {
        mutableState.update { it.copy(confirmingRemoval = false) }
        work {
            when (val result = plugins.remove(key)) {
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.userMessage()) }
                is AppResult.Ok -> mutableState.update { it.copy(gone = true) }
            }
        }
    }

    private fun work(block: suspend () -> Unit) {
        mutableState.update { it.copy(working = true, error = null, notice = null) }
        viewModelScope.launch {
            block()
            mutableState.update { it.copy(working = false) }
        }
    }

    /** Settings shown: the update's during an update (new ones included), otherwise the installed source's. */
    private fun spec(): List<SettingField> = updating?.info?.settings ?: item?.settingsSpec.orEmpty()

    private fun render() {
        val current = item
        if (current == null) {
            mutableState.update { it.copy(loading = false, gone = true) }
            return
        }
        val spec = spec()
        val values = current.settings + edits
        val missing = SettingsRules.missing(spec, SettingsRules.merge(spec, current.settings, edits))
        mutableState.update {
            it.copy(
                loading = false,
                gone = false,
                info = current.toInfo(),
                enabled = current.enabled,
                visibilityLabel = t(if (current.enabled) "sources.hide" else "sources.show"),
                update =
                    current.update?.let { u ->
                        SourceUpdateUi(SourceLabels.version(u.version), u.changes, SourceLabels.newHosts(u.newHosts))
                    },
                settings = spec.map { f -> f.toUi(values[f.name].orEmpty(), errors[f.name]) },
                settingsEditable = current.thirdParty,
                settingsNotice =
                    when {
                        updating != null && missing.isNotEmpty() -> t("sources.fillNewSettings")
                        missing.isNotEmpty() -> SourceLabels.SETTINGS_NEEDED
                        else -> null
                    },
                settingsDirty = edits.isNotEmpty() || (updating != null && missing.isNotEmpty()),
                canRemove = current.thirdParty,
                removalTitle = t("sources.removeTitle", "name" to current.name),
                pendingSync = current.pendingSync,
            )
        }
    }
}
