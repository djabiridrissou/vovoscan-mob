package com.vovoscan.feature.groups.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.groups.domain.Entry
import com.vovoscan.feature.groups.domain.Group
import com.vovoscan.feature.groups.domain.GroupDraft
import com.vovoscan.feature.groups.domain.GroupLinks
import com.vovoscan.feature.groups.domain.GroupRules
import com.vovoscan.feature.groups.domain.GroupsAccess
import com.vovoscan.feature.groups.domain.GroupsRepository
import com.vovoscan.feature.groups.domain.MemberState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A group in a list: the caller's, or a search result. */
data class GroupRow(
    val id: String,
    val name: String,
    /** "@ink-lanterns-club". */
    val handle: String,
    val accent: Int,
    val verified: Boolean,
    /** "128 members ✦ Approval". */
    val meta: String,
    /** The last line ("Aya: see you at chapter 40"), or the about text in search results. */
    val line: String,
    /** "12", "99+"; null when nothing is unread. */
    val unread: String?,
    /** Admins: "3 waiting". */
    val pending: String?,
    /** "Request sent" while the caller waits for an admin. */
    val requested: Boolean,
    val member: Boolean,
    val a11y: String,
)

data class CreateForm(
    val name: String = "",
    val handle: String = "",
    val about: String = "",
    val entry: Entry = Entry.Open,
    val listed: Boolean = true,
    val working: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() =
        !working &&
            GroupRules.nameOk(name) &&
            (handle.isBlank() || GroupRules.handleOk(GroupRules.normalizeHandle(handle)))

    /** "Share name: vovoscan.app/g/ink-lanterns" or the reason it can't be used. */
    val handleHint: String
        get() {
            val h = GroupRules.normalizeHandle(handle)
            return when {
                handle.isBlank() -> t("groups.create.handleAuto")
                !GroupRules.handleOk(h) -> t("groups.create.handleRule")
                else -> t("groups.create.handlePreview", "handle" to h)
            }
        }
}

/** Something the screen opens once: a group (by id), answered with [GroupsIntent.OpenHandled]. */
data class OpenGroup(
    val seq: Int,
    val groupId: String,
)

data class GroupsUiState(
    val visible: Boolean = false,
    val loading: Boolean = true,
    val mine: List<GroupRow> = emptyList(),
    val query: String = "",
    val results: List<GroupRow> = emptyList(),
    val searching: Boolean = false,
    val resultsEnd: Boolean = false,
    /** Offline: the saved list stays on screen with this line. */
    val notice: String? = null,
    val error: String? = null,
    val create: CreateForm? = null,
    val open: OpenGroup? = null,
    /** Unread messages across the caller's groups (the tab's badge). */
    val unreadTotal: Int = 0,
) {
    /** No search typed: Discover (verified and liveliest first). */
    val discovering: Boolean get() = query.trim().length < 2 && GroupLinks.handleOf(query) == null
    val resultsTitle: String get() = if (discovering) t("groups.discover") else t("groups.results")
    val emptyMine: String get() = t("groups.emptyMine")
    val emptyResults: String? get() = if (!searching && results.isEmpty() && !discovering) t("groups.noResults") else null
    val badge: String? get() = unreadTotal.takeIf { it > 0 }?.let { if (it > 99) "99+" else it.toString() }
}

sealed interface GroupsIntent {
    data object Start : GroupsIntent

    data object Stop : GroupsIntent

    data object Refresh : GroupsIntent

    /** Search by name or share name; a pasted link or `@handle` opens that group. */
    data class Query(
        val text: String,
    ) : GroupsIntent

    data object LoadMore : GroupsIntent

    /** A link opened from outside (`vovoscan://g/<handle>`, the website's `/g/<handle>`, a scanned QR code). */
    data class OpenLink(
        val link: String,
    ) : GroupsIntent

    data class OpenHandled(
        val seq: Int,
    ) : GroupsIntent

    data object ShowCreate : GroupsIntent

    data object CancelCreate : GroupsIntent

    data class EditCreate(
        val form: CreateForm,
    ) : GroupsIntent

    data object SubmitCreate : GroupsIntent
}

/**
 * The Groups tab: the caller's groups (liveliest first, unread counts, requests waiting), search by name or share name
 * (Discover without a query), a link or a typed `@handle` opening its group, and "Create a group".
 */
class GroupsViewModel(
    private val repository: GroupsRepository,
    private val access: GroupsAccess = GroupsAccess.Off,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GroupsUiState())
    val state: StateFlow<GroupsUiState> = mutableState.asStateFlow()

    private var searchJob: Job? = null
    private var pollJob: Job? = null
    private var page = 1
    private var seq = 0
    private var on = false

    init {
        access
            .enabled()
            .distinctUntilChanged()
            .onEach { enabled ->
                on = enabled
                mutableState.update { it.copy(visible = enabled) }
                if (enabled) refresh()
            }.launchIn(viewModelScope)
        repository
            .observeMine()
            .onEach { groups ->
                when {
                    groups != null -> show(groups)
                    // Erased (signed out): nothing of the account stays on screen.
                    state.value.mine.isNotEmpty() -> show(emptyList())
                }
            }.launchIn(viewModelScope)
    }

    fun onIntent(intent: GroupsIntent) {
        when (intent) {
            GroupsIntent.Start -> start()
            GroupsIntent.Stop -> {
                pollJob?.cancel()
                pollJob = null
            }
            GroupsIntent.Refresh -> {
                refresh()
                search(state.value.query, reset = true)
            }
            is GroupsIntent.Query -> query(intent.text)
            GroupsIntent.LoadMore -> if (!state.value.resultsEnd && !state.value.searching) search(state.value.query, reset = false)
            is GroupsIntent.OpenLink -> openLink(intent.link)
            is GroupsIntent.OpenHandled -> mutableState.update { if (it.open?.seq == intent.seq) it.copy(open = null) else it }
            GroupsIntent.ShowCreate -> mutableState.update { it.copy(create = CreateForm()) }
            GroupsIntent.CancelCreate -> mutableState.update { it.copy(create = null) }
            is GroupsIntent.EditCreate -> mutableState.update { it.copy(create = intent.form.copy(error = null)) }
            GroupsIntent.SubmitCreate -> create()
        }
    }

    private fun start() {
        if (!on) return
        refresh()
        if (state.value.results.isEmpty()) search(state.value.query, reset = true)
        pollJob?.cancel()
        // Unread counts move while the tab is open (chat messages are not notifications).
        pollJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(POLL_MS)
                    refresh()
                }
            }
    }

    private fun refresh() {
        viewModelScope.launch {
            when (val result = repository.mine()) {
                is AppResult.Ok -> {
                    show(result.value)
                    mutableState.update { it.copy(notice = null, error = null) }
                }
                is AppResult.Err ->
                    mutableState.update {
                        it.copy(
                            loading = false,
                            notice = if (result.error == AppError.Network) t("groups.offline") else null,
                            error = if (result.error == AppError.Network) null else groupError(result.error),
                        )
                    }
            }
        }
    }

    private fun show(groups: List<Group>) {
        val mine = groups.filter { it.me?.state == MemberState.Member || it.me?.state == MemberState.Requested }
        mutableState.update {
            it.copy(
                loading = false,
                mine = mine.map(::row),
                unreadTotal = mine.filter { g -> g.me?.isMember == true }.sumOf { g -> g.unread },
            )
        }
    }

    private fun query(text: String) {
        mutableState.update { it.copy(query = text) }
        // A link or `@handle` pasted into search goes straight to its group.
        if (GroupLinks.handleOf(text) != null && (text.contains("/g/") || text.startsWith("vovoscan:"))) {
            openLink(text)
            return
        }
        search(text, reset = true, debounce = true)
    }

    private fun search(
        text: String,
        reset: Boolean,
        debounce: Boolean = false,
    ) {
        if (!on) return
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                if (debounce) delay(DEBOUNCE_MS)
                if (reset) page = 1
                mutableState.update { it.copy(searching = true) }
                val q = GroupLinks.handleOf(text) ?: text.trim()
                when (val result = repository.search(if (q.length < 2) "" else q, page)) {
                    is AppResult.Ok -> {
                        val rows = result.value.map(::row)
                        mutableState.update {
                            it.copy(
                                searching = false,
                                results = if (reset) rows else (it.results + rows).distinctBy { r -> r.id },
                                resultsEnd = result.value.size < SEARCH_PAGE,
                            )
                        }
                        page += 1
                    }
                    is AppResult.Err ->
                        mutableState.update {
                            it.copy(searching = false, error = if (result.error == AppError.Network) null else groupError(result.error))
                        }
                }
            }
    }

    private fun openLink(link: String) {
        val handle = GroupLinks.handleOf(link) ?: GroupRules.normalizeHandle(link).takeIf(GroupRules::handleOk)
        if (handle == null) {
            mutableState.update { it.copy(error = t("groups.linkInvalid")) }
            return
        }
        viewModelScope.launch {
            when (val result = repository.byHandle(handle)) {
                is AppResult.Ok -> open(result.value.id)
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun create() {
        val form = state.value.create ?: return
        if (!form.canSubmit) return
        mutableState.update { it.copy(create = form.copy(working = true, error = null)) }
        viewModelScope.launch {
            val draft =
                GroupDraft(
                    name = GroupRules.clean(form.name, lines = false),
                    handle = GroupRules.normalizeHandle(form.handle).ifBlank { null },
                    about = GroupRules.clean(form.about, lines = true).ifBlank { null },
                    entry = form.entry,
                    listed = form.listed,
                )
            when (val result = repository.create(draft)) {
                is AppResult.Ok -> {
                    mutableState.update { it.copy(create = null) }
                    open(result.value.id)
                    refresh()
                }
                is AppResult.Err ->
                    mutableState.update { it.copy(create = it.create?.copy(working = false, error = groupError(result.error))) }
            }
        }
    }

    private fun open(groupId: String) {
        seq += 1
        mutableState.update { it.copy(open = OpenGroup(seq, groupId), error = null) }
    }

    private fun row(g: Group): GroupRow {
        val me = g.me
        val member = me?.isMember == true
        val line =
            if (member) {
                g.last?.let { MessageLines.preview(it) } ?: g.about
            } else {
                g.about
            }
        val unread = g.unread.takeIf { member && it > 0 }?.let { if (it > 99) "99+" else it.toString() }
        val meta =
            listOfNotNull(
                t("groups.members", "count" to g.memberCount),
                if (g.entry == Entry.Approval) t("groups.entry.approval") else null,
                if (!g.listed) t("groups.unlisted") else null,
            ).joinToString(" ✦ ")
        return GroupRow(
            id = g.id,
            name = g.name,
            handle = "@${g.handle}",
            accent = g.accent,
            verified = g.verified,
            meta = meta,
            line = line,
            unread = unread,
            pending = g.pending.takeIf { me?.isAdmin == true && it > 0 }?.let { t("groups.pending", "count" to it) },
            requested = me?.state == MemberState.Requested,
            member = member,
            a11y =
                listOfNotNull(
                    g.name,
                    if (g.verified) t("groups.verified") else null,
                    meta,
                    unread?.let { t("groups.unreadA11y", "count" to g.unread) },
                ).joinToString(", "),
        )
    }

    private companion object {
        const val POLL_MS = 20_000L
        const val DEBOUNCE_MS = 250L
        const val SEARCH_PAGE = 20
    }
}
