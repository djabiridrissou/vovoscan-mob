package com.vovoscan.feature.groups.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.DeviceTimeZone
import com.vovoscan.core.common.i18n.LocalFormat
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.groups.domain.BadgeStatus
import com.vovoscan.feature.groups.domain.Entry
import com.vovoscan.feature.groups.domain.Group
import com.vovoscan.feature.groups.domain.GroupDraft
import com.vovoscan.feature.groups.domain.GroupRules
import com.vovoscan.feature.groups.domain.GroupsRepository
import com.vovoscan.feature.groups.domain.GroupsUser
import com.vovoscan.feature.groups.domain.Member
import com.vovoscan.feature.groups.domain.MemberState
import com.vovoscan.feature.groups.domain.MessageStatus
import com.vovoscan.feature.groups.domain.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AdminTab { Settings, Members, Requests, Banned, Reports, Badge }

data class SettingsForm(
    val name: String = "",
    val handle: String = "",
    val about: String = "",
    val entry: Entry = Entry.Open,
    val listed: Boolean = true,
    val accent: Int = 0,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = !saving && GroupRules.nameOk(name) && GroupRules.handleOk(GroupRules.normalizeHandle(handle))
}

enum class MemberAction { Approve, Decline, MakeAdmin, MakeMember, HandOver, Remove, Ban, Unban }

data class MemberActionUi(
    val action: MemberAction,
    val label: String,
    /** Asked first: "Hand Night Readers over to Aya? You become an admin." */
    val confirm: String?,
    val destructive: Boolean,
)

data class MemberRowUi(
    val userId: String,
    val name: String,
    val slot: Int,
    /** "Owner", "Admin", or the request's note. */
    val detail: String?,
    /** "Joined Oct 21, 2026". */
    val since: String,
    val actions: List<MemberActionUi>,
)

data class ReportRowUi(
    val messageId: String,
    val excerpt: String,
    /** "Spam ✦ reported by 3". */
    val detail: String,
    val held: Boolean,
)

data class BadgeCheckUi(
    val label: String,
    /** "12 / 14 days". */
    val value: String,
    val passed: Boolean,
)

data class BadgeUi(
    val checks: List<BadgeCheckUi> = emptyList(),
    val eligible: Boolean = false,
    /** "Pending review", "Denied: …", "Verified". */
    val latest: String? = null,
    val pending: Boolean = false,
    val reason: String = "",
    val links: String = "",
    val sending: Boolean = false,
) {
    val canAsk: Boolean
        get() = eligible && !pending && !sending && reason.trim().length in GroupRules.BADGE_REASON_MIN..GroupRules.BADGE_REASON_MAX
    val reasonHint: String get() = t("groups.badge.reasonHint", "min" to GroupRules.BADGE_REASON_MIN)
}

data class GroupAdminUiState(
    val loading: Boolean = true,
    val tab: AdminTab = AdminTab.Settings,
    val tabs: List<Pair<AdminTab, String>> = emptyList(),
    val owner: Boolean = false,
    val settings: SettingsForm = SettingsForm(),
    val accents: List<Int> = (0 until GroupRules.ACCENTS).toList(),
    val search: String = "",
    val members: List<MemberRowUi> = emptyList(),
    val membersTotal: Int = 0,
    val hasMore: Boolean = false,
    val reports: List<ReportRowUi> = emptyList(),
    val badge: BadgeUi = BadgeUi(),
    val deleteConfirm: Boolean = false,
    /** The group was deleted or handed over: the screen closes (the group's own screen reloads). */
    val closed: Boolean = false,
    val error: String? = null,
    val toast: String? = null,
)

sealed interface GroupAdminIntent {
    data class SelectTab(
        val tab: AdminTab,
    ) : GroupAdminIntent

    data class EditSettings(
        val form: SettingsForm,
    ) : GroupAdminIntent

    data object SaveSettings : GroupAdminIntent

    data class Search(
        val text: String,
    ) : GroupAdminIntent

    data object LoadMore : GroupAdminIntent

    data class Act(
        val userId: String,
        val action: MemberAction,
    ) : GroupAdminIntent

    data class RemoveReported(
        val messageId: String,
    ) : GroupAdminIntent

    data class RestoreReported(
        val messageId: String,
    ) : GroupAdminIntent

    data class BadgeReason(
        val text: String,
    ) : GroupAdminIntent

    data class BadgeLinks(
        val text: String,
    ) : GroupAdminIntent

    data object AskBadge : GroupAdminIntent

    data object WithdrawBadge : GroupAdminIntent

    data object AskDelete : GroupAdminIntent

    data object CancelDelete : GroupAdminIntent

    data object ConfirmDelete : GroupAdminIntent

    data object ToastShown : GroupAdminIntent
}

/**
 * A group's settings for its owner and admins: name, about, entry, listing, colour (the share name: owner only; renaming
 * drops the blue badge), members (roles, hand over, remove, ban), requests, bans, reported messages, the blue badge's
 * checklist and request, and deleting the group (owner).
 */
class GroupAdminViewModel(
    private val groupId: String,
    private val repository: GroupsRepository,
    private val user: GroupsUser = GroupsUser.None,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GroupAdminUiState())
    val state: StateFlow<GroupAdminUiState> = mutableState.asStateFlow()

    private var group: Group? = null
    private var page = 1
    private var membersJob: Job? = null

    init {
        load()
    }

    fun onIntent(intent: GroupAdminIntent) {
        when (intent) {
            is GroupAdminIntent.SelectTab -> {
                mutableState.update { it.copy(tab = intent.tab, search = "", members = emptyList(), error = null) }
                loadTab(intent.tab)
            }
            is GroupAdminIntent.EditSettings -> mutableState.update { it.copy(settings = intent.form.copy(saved = false, error = null)) }
            GroupAdminIntent.SaveSettings -> save()
            is GroupAdminIntent.Search -> {
                mutableState.update { it.copy(search = intent.text) }
                members(reset = true, debounce = true)
            }
            GroupAdminIntent.LoadMore -> if (state.value.hasMore) members(reset = false)
            is GroupAdminIntent.Act -> act(intent.userId, intent.action)
            is GroupAdminIntent.RemoveReported -> reported(intent.messageId, remove = true)
            is GroupAdminIntent.RestoreReported -> reported(intent.messageId, remove = false)
            is GroupAdminIntent.BadgeReason ->
                mutableState.update {
                    it.copy(badge = it.badge.copy(reason = intent.text.take(GroupRules.BADGE_REASON_MAX)))
                }
            is GroupAdminIntent.BadgeLinks -> mutableState.update { it.copy(badge = it.badge.copy(links = intent.text)) }
            GroupAdminIntent.AskBadge -> askBadge()
            GroupAdminIntent.WithdrawBadge -> withdrawBadge()
            GroupAdminIntent.AskDelete -> mutableState.update { it.copy(deleteConfirm = true) }
            GroupAdminIntent.CancelDelete -> mutableState.update { it.copy(deleteConfirm = false) }
            GroupAdminIntent.ConfirmDelete -> delete()
            GroupAdminIntent.ToastShown -> mutableState.update { it.copy(toast = null) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            when (val result = repository.group(groupId)) {
                is AppResult.Ok -> show(result.value)
                is AppResult.Err -> mutableState.update { it.copy(loading = false, error = groupError(result.error)) }
            }
        }
    }

    private fun show(g: Group) {
        group = g
        val owner = g.me?.isOwner == true
        val tabs =
            listOf(
                AdminTab.Settings to t("groups.admin.settings"),
                AdminTab.Members to t("groups.admin.members"),
                AdminTab.Requests to
                    (if (g.pending > 0) t("groups.admin.requestsCount", "count" to g.pending) else t("groups.admin.requests")),
                AdminTab.Banned to t("groups.admin.banned"),
                AdminTab.Reports to t("groups.admin.reports"),
                AdminTab.Badge to t("groups.admin.badge"),
            )
        mutableState.update {
            it.copy(
                loading = false,
                owner = owner,
                tabs = tabs,
                settings = SettingsForm(g.name, g.handle, g.about, g.entry, g.listed, g.accent),
            )
        }
    }

    private fun loadTab(tab: AdminTab) {
        when (tab) {
            AdminTab.Settings -> Unit
            AdminTab.Members, AdminTab.Requests, AdminTab.Banned -> members(reset = true)
            AdminTab.Reports -> reports()
            AdminTab.Badge -> badge()
        }
    }

    private fun save() {
        val g = group ?: return
        val form = state.value.settings
        if (!form.canSave) return
        val handle = GroupRules.normalizeHandle(form.handle)
        mutableState.update { it.copy(settings = form.copy(saving = true)) }
        viewModelScope.launch {
            val draft =
                GroupDraft(
                    name = GroupRules.clean(form.name, lines = false).takeIf { it != g.name },
                    handle = handle.takeIf { state.value.owner && it != g.handle },
                    about = GroupRules.clean(form.about, lines = true).takeIf { it != g.about },
                    entry = form.entry.takeIf { it != g.entry },
                    listed = form.listed.takeIf { it != g.listed },
                    accent = form.accent.takeIf { it != g.accent },
                )
            when (val result = repository.update(g.id, draft)) {
                is AppResult.Ok -> {
                    show(result.value)
                    mutableState.update { it.copy(settings = it.settings.copy(saved = true), toast = t("groups.admin.saved")) }
                }
                is AppResult.Err -> mutableState.update { it.copy(settings = form.copy(saving = false, error = groupError(result.error))) }
            }
        }
    }

    private fun stateOf(tab: AdminTab): MemberState =
        when (tab) {
            AdminTab.Requests -> MemberState.Requested
            AdminTab.Banned -> MemberState.Banned
            else -> MemberState.Member
        }

    private fun members(
        reset: Boolean,
        debounce: Boolean = false,
    ) {
        val tab = state.value.tab
        membersJob?.cancel()
        membersJob =
            viewModelScope.launch {
                if (debounce) delay(250)
                if (reset) page = 1
                when (val result = repository.members(groupId, stateOf(tab), state.value.search, page)) {
                    is AppResult.Ok -> {
                        val rows = result.value.members.map { row(it, tab) }
                        mutableState.update {
                            it.copy(
                                members = if (reset) rows else (it.members + rows).distinctBy { r -> r.userId },
                                membersTotal = result.value.total,
                                hasMore = result.value.hasMore,
                            )
                        }
                        page += 1
                    }
                    is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
                }
            }
    }

    private fun row(
        m: Member,
        tab: AdminTab,
    ): MemberRowUi {
        val myRank = group?.me?.role?.rank ?: 0
        val owner = state.value.owner
        val name = group?.name.orEmpty()
        val canAct = m.role.rank < myRank && m.userId != user.id()
        val actions =
            when (tab) {
                AdminTab.Requests ->
                    listOf(
                        MemberActionUi(MemberAction.Approve, t("groups.admin.approve"), null, false),
                        MemberActionUi(MemberAction.Decline, t("groups.admin.decline"), null, true),
                    )
                AdminTab.Banned -> listOf(MemberActionUi(MemberAction.Unban, t("groups.admin.unban"), null, false))
                else ->
                    if (!canAct) {
                        emptyList()
                    } else {
                        listOfNotNull(
                            if (owner &&
                                m.role == Role.Member
                            ) {
                                MemberActionUi(MemberAction.MakeAdmin, t("groups.admin.makeAdmin"), null, false)
                            } else {
                                null
                            },
                            if (owner &&
                                m.role == Role.Admin
                            ) {
                                MemberActionUi(MemberAction.MakeMember, t("groups.admin.makeMember"), null, false)
                            } else {
                                null
                            },
                            if (owner) {
                                MemberActionUi(
                                    MemberAction.HandOver,
                                    t("groups.admin.handOver"),
                                    t("groups.admin.handOverConfirm", "group" to name, "name" to m.name),
                                    true,
                                )
                            } else {
                                null
                            },
                            MemberActionUi(
                                MemberAction.Remove,
                                t("groups.admin.remove"),
                                t("groups.admin.removeConfirm", "name" to m.name),
                                true,
                            ),
                            MemberActionUi(MemberAction.Ban, t("groups.admin.ban"), t("groups.admin.banConfirm", "name" to m.name), true),
                        )
                    }
            }
        return MemberRowUi(
            userId = m.userId,
            name = m.name,
            slot = slotOf(m.userId),
            detail =
                when {
                    tab == AdminTab.Requests -> m.note.ifBlank { null }
                    m.role == Role.Owner -> t("groups.role.owner")
                    m.role == Role.Admin -> t("groups.role.admin")
                    else -> null
                },
            since = t("groups.admin.since", "date" to LocalFormat.date(m.joinedAtMillis + DeviceTimeZone.offsetMillis(m.joinedAtMillis))),
            actions = actions,
        )
    }

    private fun act(
        userId: String,
        action: MemberAction,
    ) {
        viewModelScope.launch {
            val result =
                when (action) {
                    MemberAction.Approve -> repository.decide(groupId, userId, approve = true)
                    MemberAction.Decline -> repository.decide(groupId, userId, approve = false)
                    MemberAction.MakeAdmin -> repository.setRole(groupId, userId, Role.Admin)
                    MemberAction.MakeMember -> repository.setRole(groupId, userId, Role.Member)
                    MemberAction.HandOver -> repository.setRole(groupId, userId, Role.Owner)
                    MemberAction.Remove -> repository.remove(groupId, userId, ban = false)
                    MemberAction.Ban -> repository.remove(groupId, userId, ban = true)
                    MemberAction.Unban -> repository.unban(groupId, userId)
                }
            when (result) {
                is AppResult.Ok -> {
                    if (action == MemberAction.HandOver) {
                        mutableState.update { it.copy(closed = true) }
                        return@launch
                    }
                    mutableState.update { it.copy(toast = t("groups.admin.done")) }
                    load()
                    members(reset = true)
                }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun reports() {
        viewModelScope.launch {
            when (val result = repository.reports(groupId)) {
                is AppResult.Ok ->
                    mutableState.update {
                        it.copy(
                            reports =
                                result.value.map { r ->
                                    ReportRowUi(
                                        messageId = r.message.id,
                                        excerpt = MessageLines.preview(r.message),
                                        detail =
                                            t(
                                                "groups.admin.reportDetail",
                                                "reason" to t("groups.report.${r.reason}"),
                                                "count" to r.reports,
                                            ),
                                        held = r.message.status == MessageStatus.Held,
                                    )
                                },
                        )
                    }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun reported(
        messageId: String,
        remove: Boolean,
    ) {
        viewModelScope.launch {
            val result = if (remove) repository.deleteMessage(messageId) else repository.restore(messageId)
            when (result) {
                is AppResult.Ok -> {
                    mutableState.update { it.copy(reports = it.reports.filterNot { r -> r.messageId == messageId }) }
                    reports()
                }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun badge() {
        viewModelScope.launch {
            when (val result = repository.badge(groupId)) {
                is AppResult.Ok -> mutableState.update { it.copy(badge = badgeUi(result.value, it.badge)) }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun badgeUi(
        b: BadgeStatus,
        kept: BadgeUi,
    ): BadgeUi {
        val latest = b.latest
        val verified = group?.verified == true
        return kept.copy(
            checks = b.checks.map { c -> BadgeCheckUi(t("groups.badge.check.${c.key}"), checkValue(c.key, c.value, c.need), c.passed) },
            eligible = b.eligible,
            pending = latest?.status == "pending",
            latest =
                when {
                    verified -> t("groups.badge.verified")
                    latest == null -> null
                    latest.status == "pending" -> t("groups.badge.pending")
                    latest.status == "denied" -> t("groups.badge.denied", "note" to latest.note)
                    latest.status == "revoked" -> t("groups.badge.revoked", "note" to latest.note)
                    else -> null
                },
            sending = false,
        )
    }

    private fun checkValue(
        key: String,
        value: Double,
        need: Double,
    ): String =
        when (key) {
            "ownerVerified" -> if (value >= 1.0) t("groups.badge.yes") else t("groups.badge.no")
            "realMemberShare", "suspectShare" -> "${(value * 100).toInt()} % / ${(need * 100).toInt()} %"
            else -> "${value.toInt()} / ${need.toInt()}"
        }

    private fun askBadge() {
        val b = state.value.badge
        if (!b.canAsk) return
        val links =
            b.links
                .split(Regex("[\\s,]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(GroupRules.BADGE_LINKS_MAX)
        mutableState.update { it.copy(badge = b.copy(sending = true)) }
        viewModelScope.launch {
            when (val result = repository.askBadge(groupId, b.reason.trim(), links)) {
                is AppResult.Ok -> {
                    mutableState.update { it.copy(toast = t("groups.badge.sent"), badge = it.badge.copy(reason = "", links = "")) }
                    badge()
                }
                is AppResult.Err ->
                    mutableState.update {
                        it.copy(
                            badge = it.badge.copy(sending = false),
                            error = groupError(result.error),
                        )
                    }
            }
        }
    }

    private fun withdrawBadge() {
        viewModelScope.launch {
            when (val result = repository.withdrawBadge(groupId)) {
                is AppResult.Ok -> badge()
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun delete() {
        mutableState.update { it.copy(deleteConfirm = false) }
        viewModelScope.launch {
            when (val result = repository.delete(groupId)) {
                is AppResult.Ok -> mutableState.update { it.copy(closed = true) }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }
}
