package com.vovoscan.feature.sources.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.sources.domain.PluginsRepository
import com.vovoscan.feature.sources.domain.SourceEngine
import com.vovoscan.feature.sources.domain.SourceItem
import com.vovoscan.feature.sources.domain.SourceRepositoryItem
import com.vovoscan.feature.sources.domain.SourcesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SourceRow(
    val key: String,
    val name: String,
    /** "Manga · Books". */
    val kindsLabel: String,
    /** "vovoscan" or "Read on your device". */
    val engineLabel: String,
    val enabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val version: String = "",
    /** "v1.0.0". */
    val versionLabel: String = "",
    /** Chip: "Official" or "Third-party". */
    val originLabel: String = SourceLabels.OFFICIAL,
    val thirdParty: Boolean = false,
    /** https or `data:` URL; null: show [initial]. */
    val iconUrl: String? = null,
    val initial: String = "",
    /** "Update" badge. */
    val updateAvailable: Boolean = false,
    /** Required settings missing: the source cannot be read until they are filled (detail screen). */
    val needsSettings: Boolean = false,
    val pendingSync: Boolean = false,
)

/** A source in the catalog picker. */
data class SourceChip(
    val key: String,
    val name: String,
)

/** A saved plugin repository: tap to browse it (AddSourceViewModel, `Browse`), swipe or menu to remove it. */
data class RepositoryRow(
    val id: String,
    val name: String,
    val url: String,
    /** "12 sources"; null until its index was read on this device. */
    val sourceCountLabel: String?,
    val pendingSync: Boolean,
)

data class SourcesUiState(
    val rows: List<SourceRow> = emptyList(),
    val repositories: List<RepositoryRow> = emptyList(),
    val freshness: Freshness = Freshness.Refreshing,
    /** Looking for newer versions of third-party sources. */
    val checkingUpdates: Boolean = false,
    val pendingNotice: String? = null,
    val error: String? = null,
    /** Short confirmation ("Repository removed."). */
    val notice: String? = null,
) {
    /** Visible sources, in the chosen order: the catalog picker. */
    val chips: List<SourceChip> get() = rows.filter { it.enabled }.map { SourceChip(it.key, it.name) }

    /** Every source of the list, visible or not (library: titles of other sources show "Source removed"). */
    val sourceKeys: List<String> get() = rows.map { it.key }

    val updatesCount: Int get() = rows.count { it.updateAvailable }

    /** "1 update available" / "3 updates available"; null when none. */
    val updatesLabel: String?
        get() = updatesCount.takeIf { it > 0 }?.let { if (it == 1) "1 update available" else "$it updates available" }

    val empty: Boolean get() = rows.isEmpty() && freshness != Freshness.Refreshing

    /** Sources listed, every one hidden: the official source starts hidden for a new account. */
    val noneVisible: Boolean get() = rows.isNotEmpty() && rows.none { it.enabled }

    /** The catalog and the library invite to add a source, or turn one on, instead of waiting for titles. */
    val needsSource: Boolean get() = empty || noneVisible

    val firstSourceTitle: String get() = if (noneVisible) TURN_ON_TITLE else FIRST_SOURCE_TITLE
    val firstSourceMessage: String get() = if (noneVisible) TURN_ON_MESSAGE else FIRST_SOURCE_MESSAGE
    val firstSourceAction: String get() = if (noneVisible) TURN_ON_ACTION else FIRST_SOURCE_ACTION

    private companion object {
        const val FIRST_SOURCE_TITLE = "Start with a source."
        const val FIRST_SOURCE_MESSAGE =
            "Your shelf is empty until you add one: a repository, a link to a source or a .vovo file. " +
                "Its titles show here, read by your phone."
        const val FIRST_SOURCE_ACTION = "Add a source →"
        const val TURN_ON_TITLE = "Turn on a source."
        const val TURN_ON_MESSAGE = "Your sources are all switched off. Turn one on and its titles show here."
        const val TURN_ON_ACTION = "Choose a source →"
    }
}

sealed interface SourcesIntent {
    /** Pull to refresh: sync, then look for updates. */
    data object Refresh : SourcesIntent

    /** The Sources screen opened: look for updates. */
    data object CheckUpdates : SourcesIntent

    data class Toggle(
        val key: String,
    ) : SourcesIntent

    data class MoveUp(
        val key: String,
    ) : SourcesIntent

    data class MoveDown(
        val key: String,
    ) : SourcesIntent

    data class RemoveRepository(
        val id: String,
    ) : SourcesIntent

    data object DismissMessage : SourcesIntent
}

/**
 * "Sources" screen and catalog picker: show or hide each source, reorder it, see updates and repositories.
 * At least one source stays visible. At start, updates are looked for if the last check is more than 12 hours old.
 */
class SourcesViewModel(
    private val repository: SourcesRepository,
    private val plugins: PluginsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = mutableState.asStateFlow()
    private var items: List<SourceItem> = emptyList()
    private var checkJob: Job? = null

    init {
        repository
            .observe()
            .onEach { sources ->
                items = sources
                mutableState.update {
                    it.copy(
                        rows = sources.mapIndexed { i, s -> row(s, i, sources.size) },
                        pendingNotice = if (sources.any { s -> s.pendingSync }) SourceLabels.PENDING else null,
                    )
                }
            }.launchIn(viewModelScope)
        plugins
            .observeRepositories()
            .onEach { list -> mutableState.update { it.copy(repositories = list.map(::repositoryRow)) } }
            .launchIn(viewModelScope)
        refresh(forceCheck = false)
    }

    fun onIntent(intent: SourcesIntent) {
        when (intent) {
            SourcesIntent.Refresh -> refresh(forceCheck = true)
            SourcesIntent.CheckUpdates -> checkUpdates(force = true)
            is SourcesIntent.Toggle -> {
                val target = items.firstOrNull { it.key == intent.key } ?: return
                // The last visible source cannot be hidden: the catalog would be empty.
                if (target.enabled && items.count { it.enabled } == 1) {
                    mutableState.update { it.copy(error = SourceLabels.KEEP_ONE_VISIBLE) }
                    return
                }
                save(items.map { if (it.key == intent.key) it.copy(enabled = !it.enabled) else it })
            }
            is SourcesIntent.MoveUp -> move(intent.key, -1)
            is SourcesIntent.MoveDown -> move(intent.key, +1)
            is SourcesIntent.RemoveRepository -> removeRepository(intent.id)
            SourcesIntent.DismissMessage -> mutableState.update { it.copy(error = null, notice = null) }
        }
    }

    private fun move(
        key: String,
        delta: Int,
    ) {
        val from = items.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in items.indices) return
        save(items.toMutableList().apply { add(to, removeAt(from)) })
    }

    private fun save(ordered: List<SourceItem>) {
        mutableState.update { it.copy(error = null) }
        viewModelScope.launch {
            val result = repository.save(ordered.map { it.key to it.enabled })
            if (result is AppResult.Err && result.error != AppError.Network) {
                mutableState.update { it.copy(error = result.error.userMessage()) }
            }
        }
    }

    private fun removeRepository(id: String) {
        mutableState.update { it.copy(error = null, notice = null) }
        viewModelScope.launch {
            when (val result = plugins.removeRepository(id)) {
                is AppResult.Ok -> mutableState.update { it.copy(notice = "Repository removed.") }
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.userMessage()) }
            }
        }
    }

    private fun refresh(forceCheck: Boolean) {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.refresh()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh)
                    is AppResult.Err ->
                        it.copy(
                            freshness = Freshness.Offline,
                            error = if (result.error == AppError.Network) null else result.error.userMessage(),
                        )
                }
            }
            if (result is AppResult.Ok) checkUpdates(forceCheck)
        }
    }

    private fun checkUpdates(force: Boolean) {
        if (checkJob?.isActive == true) return
        checkJob =
            viewModelScope.launch {
                mutableState.update { it.copy(checkingUpdates = true) }
                // Offline or an unreachable repository: the last results stay, nothing to report.
                plugins.checkUpdates(force)
                mutableState.update { it.copy(checkingUpdates = false) }
            }
    }

    private fun row(
        source: SourceItem,
        index: Int,
        count: Int,
    ) = SourceRow(
        key = source.key,
        name = source.name,
        kindsLabel = SourceLabels.kinds(source.kinds),
        engineLabel = if (source.engine == SourceEngine.Internal) "vovoscan" else "Read on your device",
        enabled = source.enabled,
        canMoveUp = index > 0,
        canMoveDown = index < count - 1,
        version = source.version,
        versionLabel = SourceLabels.shortVersion(source.version),
        originLabel = SourceLabels.chip(source.thirdParty),
        thirdParty = source.thirdParty,
        iconUrl = source.iconUrl,
        initial = SourceLabels.initial(source.name),
        updateAvailable = source.update != null,
        needsSettings = source.missingSettings.isNotEmpty(),
        pendingSync = source.pendingSync,
    )

    private fun repositoryRow(item: SourceRepositoryItem) =
        RepositoryRow(item.id, item.name, item.url, SourceLabels.sourceCount(item.sourceCount), item.pendingSync)
}
