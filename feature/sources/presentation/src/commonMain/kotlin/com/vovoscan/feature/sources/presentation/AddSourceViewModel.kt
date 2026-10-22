package com.vovoscan.feature.sources.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.sources.domain.EntryStatus
import com.vovoscan.feature.sources.domain.PluginCandidate
import com.vovoscan.feature.sources.domain.PluginLookup
import com.vovoscan.feature.sources.domain.PluginsRepository
import com.vovoscan.feature.sources.domain.RepositoryEntry
import com.vovoscan.feature.sources.domain.RepositoryListing
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

enum class AddSourceStep {
    /** Link field and "Choose a .vovo file". */
    Input,
    Loading,

    /** A single source: [AddSourceUiState.preview]. */
    Preview,

    /** A repository: [AddSourceUiState.repository]. */
    Repository,
}

/** A single source before install: its details, and what can be done with it. */
data class SourcePreviewUi(
    val info: SourceInfoUi,
    /** "Install" or "Update"; null when nothing can be done (installed, blocked). */
    val actionLabel: String?,
    /** "Installed", "Already available as an official source.", "A newer version is already installed."… */
    val statusLabel: String?,
    /** For an update: "This version also connects to: …". */
    val newHostsLabel: String?,
)

data class RepositoryEntryRow(
    val key: String,
    val name: String,
    val initial: String,
    val iconUrl: String?,
    /** "v1.2.0". */
    val versionLabel: String,
    val kindsLabel: String,
    val langsLabel: String,
    val rightsLabel: String?,
    val changes: String?,
    val status: EntryStatus,
    /** "Install" or "Update"; null for Installed / Unavailable. */
    val actionLabel: String?,
    /** "Installed", or why it is unavailable. */
    val statusLabel: String?,
    /** This entry is being fetched or installed. */
    val busy: Boolean,
)

data class RepositoryListingUi(
    val id: String,
    val name: String,
    val url: String,
    val homepage: String?,
    /** "12 sources". */
    val countLabel: String,
    val entries: List<RepositoryEntryRow>,
)

/** An official source the account has not turned on (vovoscan's own catalog first): one tap turns it on. */
data class OfficialOfferUi(
    val key: String,
    val name: String,
    val initial: String,
    val iconUrl: String?,
    /** "Manga · Books". */
    val kindsLabel: String,
    val summary: String,
    val actionLabel: String,
)

data class AddSourceUiState(
    /** Shown above the link field, before any third-party source. */
    val officialOffers: List<OfficialOfferUi> = emptyList(),
    val step: AddSourceStep = AddSourceStep.Input,
    val link: String = "",
    val error: String? = null,
    /** Shown before anything is installed. */
    val warning: String = SourceLabels.THIRD_PARTY_WARNING,
    val preview: SourcePreviewUi? = null,
    val repository: RepositoryListingUi? = null,
    /** Step 1 of an update that reaches new hosts: "This version also connects to: …" (ConfirmHosts / CancelHosts). */
    val hostsConfirmation: String? = null,
    /** Settings to fill before the source is enabled (ConfirmSettings / CancelSettings); empty: no form. */
    val settingsForm: List<SettingFieldUi> = emptyList(),
    /** "Settings for Project Gutenberg". */
    val settingsTitle: String? = null,
    val installing: Boolean = false,
    /** "Project Gutenberg installed." */
    val notice: String? = null,
    /** Last source installed (a single preview can close, or open its detail). */
    val installedKey: String? = null,
) {
    val canSubmit: Boolean get() = link.isNotBlank() && step != AddSourceStep.Loading && !installing
    val showsSettingsForm: Boolean get() = settingsForm.isNotEmpty()
}

sealed interface AddSourceIntent {
    data class LinkChanged(
        val value: String,
    ) : AddSourceIntent

    data object SubmitLink : AddSourceIntent

    /** A `.vovo` file (or a `source.json`) picked by the user. */
    data class ImportFile(
        val bytes: ByteArray,
        val name: String,
    ) : AddSourceIntent {
        override fun equals(other: Any?): Boolean = other is ImportFile && other.name == name && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
    }

    /** Browse a saved repository (tap in the Sources screen's "Repositories" section). */
    data class Browse(
        val repositoryId: String,
    ) : AddSourceIntent

    /** Turns on an official source of the list (first in the reading order). */
    data class EnableOfficial(
        val key: String,
    ) : AddSourceIntent

    /** Install or update the previewed source. */
    data object Install : AddSourceIntent

    /** Install or update a repository entry. */
    data class InstallEntry(
        val key: String,
    ) : AddSourceIntent

    data object ConfirmHosts : AddSourceIntent

    data object CancelHosts : AddSourceIntent

    data class SettingChanged(
        val name: String,
        val value: String,
    ) : AddSourceIntent

    data object ConfirmSettings : AddSourceIntent

    data object CancelSettings : AddSourceIntent

    /** Back to the link field. */
    data object Reset : AddSourceIntent

    data object DismissMessage : AddSourceIntent
}

/**
 * "Add a source": from a link (single source or repository) or a `.vovo` file. Preview with the third-party warning,
 * required settings filled before the source is enabled, new hosts confirmed before an update.
 */
class AddSourceViewModel(
    private val sources: SourcesRepository,
    private val plugins: PluginsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddSourceUiState())
    val state: StateFlow<AddSourceUiState> = mutableState.asStateFlow()

    private var installed: Map<String, SourceItem> = emptyMap()
    private var order: List<SourceItem> = emptyList()
    private var repositoryNames: Map<String, String> = emptyMap()
    private var previewed: PluginCandidate? = null
    private var listing: RepositoryListing? = null
    private var busyKey: String? = null

    /** Candidate waiting for the hosts confirmation or the settings form. */
    private var pending: PluginCandidate? = null
    private var edits: Map<String, String> = emptyMap()

    init {
        sources
            .observe()
            .onEach { list ->
                installed = list.associateBy { it.key }
                order = list
                mutableState.update { it.copy(officialOffers = offers(list)) }
                refreshListing()
            }.launchIn(viewModelScope)
        plugins
            .observeRepositories()
            .onEach { list -> repositoryNames = list.associate { it.id to it.name } }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: AddSourceIntent) {
        when (intent) {
            is AddSourceIntent.LinkChanged -> mutableState.update { it.copy(link = intent.value, error = null) }
            AddSourceIntent.SubmitLink -> submitLink()
            is AddSourceIntent.ImportFile -> load { plugins.openFile(intent.bytes, intent.name).toLookup() }
            is AddSourceIntent.Browse -> load { plugins.browse(intent.repositoryId).toRepositoryLookup() }
            AddSourceIntent.Install ->
                previewed?.takeIf { state.value.preview?.actionLabel != null && !state.value.installing }?.let(
                    ::start,
                )
            is AddSourceIntent.InstallEntry -> installEntry(intent.key)
            is AddSourceIntent.EnableOfficial -> enableOfficial(intent.key)
            AddSourceIntent.ConfirmHosts -> {
                val candidate = pending ?: return
                mutableState.update { it.copy(hostsConfirmation = null) }
                askSettingsOrInstall(candidate)
            }
            AddSourceIntent.CancelHosts -> cancelPending()
            is AddSourceIntent.SettingChanged -> {
                edits = edits + (intent.name to intent.value)
                showSettings(pending ?: return, validate = false)
            }
            AddSourceIntent.ConfirmSettings -> confirmSettings()
            AddSourceIntent.CancelSettings -> cancelPending()
            AddSourceIntent.Reset -> {
                previewed = null
                listing = null
                cancelPending()
                mutableState.value = AddSourceUiState(link = state.value.link)
            }
            AddSourceIntent.DismissMessage -> mutableState.update { it.copy(error = null, notice = null) }
        }
    }

    private fun offers(list: List<SourceItem>): List<OfficialOfferUi> =
        list
            .filter { !it.enabled && !it.thirdParty }
            .sortedBy { if (it.key == INTERNAL_KEY) 0 else 1 }
            .map {
                OfficialOfferUi(
                    key = it.key,
                    name = it.name,
                    initial = SourceLabels.initial(it.name),
                    iconUrl = it.iconUrl,
                    kindsLabel = SourceLabels.kinds(it.kinds),
                    summary = it.summary.ifBlank { if (it.key == INTERNAL_KEY) SourceLabels.INTERNAL_SUMMARY else "" },
                    actionLabel = SourceLabels.TURN_ON,
                )
            }

    private fun enableOfficial(key: String) {
        val target = order.firstOrNull { it.key == key && !it.thirdParty } ?: return
        if (target.enabled) return
        // Turned on and read first: the catalog opens on it.
        val choices = listOf(key to true) + order.filter { it.key != key }.map { it.key to it.enabled }
        viewModelScope.launch {
            when (val result = sources.save(choices)) {
                is AppResult.Ok -> mutableState.update { it.copy(notice = SourceLabels.turnedOn(target.name)) }
                is AppResult.Err ->
                    // Offline: the choice is kept and sent later (Sources screen says so); anything else is shown.
                    if (result.error != AppError.Network) mutableState.update { it.copy(error = result.error.userMessage()) }
            }
        }
    }

    private fun submitLink() {
        val link = state.value.link
        if (link.isBlank() || state.value.step == AddSourceStep.Loading) return
        load { plugins.open(link) }
    }

    private fun load(fetch: suspend () -> AppResult<PluginLookup>) {
        val previous = state.value.step.takeIf { it != AddSourceStep.Loading } ?: AddSourceStep.Input
        mutableState.update { it.copy(step = AddSourceStep.Loading, error = null, notice = null) }
        viewModelScope.launch {
            when (val result = fetch()) {
                is AppResult.Err -> mutableState.update { it.copy(step = previous, error = result.error.userMessage()) }
                is AppResult.Ok ->
                    when (val lookup = result.value) {
                        is PluginLookup.Single -> {
                            previewed = lookup.candidate
                            listing = null
                            mutableState.update {
                                it.copy(
                                    step = AddSourceStep.Preview,
                                    preview = preview(lookup.candidate),
                                    repository = null,
                                    installedKey = null,
                                )
                            }
                        }
                        is PluginLookup.Repository -> {
                            previewed = null
                            listing = lookup.listing
                            mutableState.update { it.copy(step = AddSourceStep.Repository, preview = null, installedKey = null) }
                            refreshListing()
                        }
                    }
            }
        }
    }

    private fun installEntry(key: String) {
        val current = listing ?: return
        val entry = current.entries.firstOrNull { it.key == key } ?: return
        if (busyKey != null || entry.status == EntryStatus.Installed || entry.status == EntryStatus.Unavailable) return
        busyKey = key
        refreshListing()
        mutableState.update { it.copy(error = null, notice = null) }
        viewModelScope.launch {
            when (val result = plugins.candidate(current.id, key)) {
                is AppResult.Err -> {
                    busyKey = null
                    refreshListing()
                    mutableState.update { it.copy(error = result.error.userMessage()) }
                }
                is AppResult.Ok -> start(result.value)
            }
        }
    }

    /** First step of an install: blockers, then the new hosts of an update, then the settings form. */
    private fun start(candidate: PluginCandidate) {
        candidate.blocker?.let { blocker ->
            finishBusy()
            mutableState.update { it.copy(error = blocker) }
            return
        }
        pending = candidate
        edits = emptyMap()
        if (candidate.isUpdate && candidate.newHosts.isNotEmpty()) {
            mutableState.update { it.copy(hostsConfirmation = SourceLabels.newHosts(candidate.newHosts)) }
        } else {
            askSettingsOrInstall(candidate)
        }
    }

    private fun askSettingsOrInstall(candidate: PluginCandidate) {
        val current = installed[candidate.info.key]?.settings.orEmpty()
        val values = SettingsRules.merge(candidate.info.settings, current, edits)
        if (SettingsRules.missing(candidate.info.settings, values).isEmpty()) {
            install(candidate, edits)
        } else {
            showSettings(candidate, validate = false)
        }
    }

    private fun showSettings(
        candidate: PluginCandidate,
        validate: Boolean,
    ) {
        val current = installed[candidate.info.key]?.settings.orEmpty()
        val values = current + edits
        val errors =
            if (validate) {
                SettingsRules.validate(
                    candidate.info.settings,
                    SettingsRules.merge(candidate.info.settings, current, edits),
                )
            } else {
                emptyMap()
            }
        mutableState.update {
            it.copy(
                settingsForm = candidate.info.settings.map { field -> field.toUi(values[field.name].orEmpty(), errors[field.name]) },
                settingsTitle = "Settings for ${candidate.info.name}",
            )
        }
    }

    private fun confirmSettings() {
        val candidate = pending ?: return
        val current = installed[candidate.info.key]?.settings.orEmpty()
        val errors = SettingsRules.validate(candidate.info.settings, SettingsRules.merge(candidate.info.settings, current, edits))
        if (errors.isNotEmpty()) {
            showSettings(candidate, validate = true)
            return
        }
        install(candidate, edits)
    }

    private fun install(
        candidate: PluginCandidate,
        settings: Map<String, String>,
    ) {
        mutableState.update { it.copy(installing = true, error = null) }
        viewModelScope.launch {
            val result = plugins.install(candidate, settings)
            pending = null
            edits = emptyMap()
            finishBusy()
            mutableState.update { state ->
                val base = state.copy(installing = false, settingsForm = emptyList(), settingsTitle = null, hostsConfirmation = null)
                when (result) {
                    is AppResult.Err -> base.copy(error = result.error.userMessage())
                    is AppResult.Ok -> {
                        val name = candidate.info.name
                        base.copy(
                            notice = if (candidate.isUpdate) "$name updated." else "$name installed.",
                            installedKey = candidate.info.key,
                            preview = if (candidate == previewed) preview(candidate, done = true) else state.preview,
                        )
                    }
                }
            }
        }
    }

    private fun cancelPending() {
        pending = null
        edits = emptyMap()
        finishBusy()
        mutableState.update { it.copy(hostsConfirmation = null, settingsForm = emptyList(), settingsTitle = null) }
    }

    private fun finishBusy() {
        if (busyKey == null) return
        busyKey = null
        refreshListing()
    }

    private fun preview(
        candidate: PluginCandidate,
        done: Boolean = false,
    ): SourcePreviewUi {
        val (action, status) =
            when {
                done -> null to SourceLabels.INSTALLED
                candidate.blocker != null -> null to candidate.blocker
                candidate.isUpdate -> SourceLabels.UPDATE to "${SourceLabels.shortVersion(candidate.installedVersion.orEmpty())} installed"
                candidate.installedVersion != null -> null to SourceLabels.INSTALLED
                else -> SourceLabels.INSTALL to null
            }
        return SourcePreviewUi(
            info = candidate.toInfo(candidate.repositoryId?.let { repositoryNames[it] }),
            actionLabel = action,
            statusLabel = status,
            newHostsLabel = if (candidate.isUpdate && !done) SourceLabels.newHosts(candidate.newHosts) else null,
        )
    }

    /** Statuses follow what is installed on the device (an install from the listing shows at once). */
    private fun refreshListing() {
        val current = listing ?: return
        mutableState.update {
            it.copy(
                repository =
                    RepositoryListingUi(
                        id = current.id,
                        name = current.name,
                        url = current.url,
                        homepage = current.homepage,
                        countLabel = SourceLabels.sourceCount(current.entries.size).orEmpty(),
                        entries = current.entries.map(::entryRow),
                    ),
            )
        }
    }

    private fun entryRow(entry: RepositoryEntry): RepositoryEntryRow {
        val mine = installed[entry.key]
        val status =
            when {
                entry.status == EntryStatus.Unavailable -> EntryStatus.Unavailable
                mine == null -> EntryStatus.Install
                !mine.thirdParty -> EntryStatus.Unavailable
                mine.version == entry.version -> EntryStatus.Installed
                // Unchanged since the index was read: the repository's verdict stands.
                mine.version == entry.installedVersion -> entry.status
                else -> EntryStatus.Update
            }
        return RepositoryEntryRow(
            key = entry.key,
            name = entry.name,
            initial = SourceLabels.initial(entry.name),
            iconUrl = entry.iconUrl,
            versionLabel = SourceLabels.shortVersion(entry.version),
            kindsLabel = SourceLabels.kinds(entry.kinds),
            langsLabel = SourceLabels.langs(entry.langs),
            rightsLabel = SourceLabels.rights(entry.rightsBasis),
            changes = entry.changes,
            status = status,
            actionLabel =
                when (status) {
                    EntryStatus.Install -> SourceLabels.INSTALL
                    EntryStatus.Update -> SourceLabels.UPDATE
                    else -> null
                },
            statusLabel =
                when (status) {
                    EntryStatus.Installed -> SourceLabels.INSTALLED
                    EntryStatus.Unavailable -> entry.problem ?: "Already available as an official source."
                    else -> null
                },
            busy = entry.key == busyKey,
        )
    }

    private fun AppResult<PluginCandidate>.toLookup(): AppResult<PluginLookup> =
        when (this) {
            is AppResult.Ok -> AppResult.Ok(PluginLookup.Single(value))
            is AppResult.Err -> this
        }

    private fun AppResult<RepositoryListing>.toRepositoryLookup(): AppResult<PluginLookup> =
        when (this) {
            is AppResult.Ok -> AppResult.Ok(PluginLookup.Repository(value))
            is AppResult.Err -> this
        }

    private companion object {
        /** vovoscan's own catalog (`SourceEngine.Internal`): offered first. */
        const val INTERNAL_KEY = "vovoscan"
    }
}
