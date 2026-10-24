package com.vovoscan.app.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.sources.BrandAlert
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.app.ui.together.CircleInk
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.groups.presentation.AdminTab
import com.vovoscan.feature.groups.presentation.GroupAdminIntent
import com.vovoscan.feature.groups.presentation.GroupAdminUiState
import com.vovoscan.feature.groups.presentation.GroupAdminViewModel
import com.vovoscan.feature.groups.presentation.MemberActionUi
import com.vovoscan.feature.groups.presentation.MemberRowUi
import com.vovoscan.feature.groups.presentation.SettingsForm
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/*
 * A group's settings for its owner and admins (shared `GroupAdminViewModel`): settings, members, requests, bans,
 * reported messages, the blue badge, deleting the group. iOS: `GroupAdminView` in `GroupsViews.swift`.
 */

@Composable
fun GroupAdminScreen(
    groupId: String,
    onBack: () -> Unit,
    onClosed: () -> Unit,
    viewModel: GroupAdminViewModel = koinViewModel(key = "group-admin:$groupId") { parametersOf(groupId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onIntent
    var confirm by remember { mutableStateOf<Pair<String, MemberActionUi>?>(null) }
    LaunchedEffect(state.closed) { if (state.closed) onClosed() }
    state.toast?.let { toast ->
        LaunchedEffect(toast) {
            delay(2_500)
            send(GroupAdminIntent.ToastShown)
        }
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            BackBar(I18n.text("groups.admin.title"), onBack)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.tabs.forEach { (tab, label) ->
                    Choice(
                        label,
                        state.tab == tab,
                        Modifier.semantics { selected = state.tab == tab },
                    ) { send(GroupAdminIntent.SelectTab(tab)) }
                }
            }
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Cyan) }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { ErrorText(it) }
                    when (state.tab) {
                        AdminTab.Settings -> Settings(state, send)
                        AdminTab.Members, AdminTab.Requests, AdminTab.Banned ->
                            Members(
                                state,
                                send,
                            ) { id, action -> confirm = id to action }
                        AdminTab.Reports -> Reports(state, send)
                        AdminTab.Badge -> Badge(state, send)
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
        state.toast?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .background(Brand.Paper, RoundedCornerShape(999.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
    confirm?.let { (userId, action) ->
        BrandAlert(
            title = action.label,
            message = action.confirm.orEmpty(),
            confirmLabel = action.label,
            onConfirm = {
                confirm = null
                send(GroupAdminIntent.Act(userId, action.action))
            },
            onCancel = { confirm = null },
        )
    }
    if (state.deleteConfirm) {
        BrandAlert(
            title = I18n.text("groups.admin.deleteGroup"),
            message = I18n.text("groups.admin.deleteConfirm"),
            confirmLabel = I18n.text("common.delete"),
            onConfirm = { send(GroupAdminIntent.ConfirmDelete) },
            onCancel = { send(GroupAdminIntent.CancelDelete) },
        )
    }
}

@Composable
private fun ColumnScope.Settings(
    state: GroupAdminUiState,
    send: (GroupAdminIntent) -> Unit,
) {
    val form = state.settings
    val edit = { f: SettingsForm -> send(GroupAdminIntent.EditSettings(f)) }
    BrandField(I18n.text("groups.create.name"), form.name, { edit(form.copy(name = it)) })
    if (state.owner) {
        BrandField(
            I18n.text("groups.create.handle"),
            form.handle,
            { edit(form.copy(handle = it)) },
            help = I18n.text("groups.create.handleRule"),
        )
    }
    BrandField(I18n.text("groups.create.about"), form.about, { edit(form.copy(about = it)) })
    EntryChoice(form.entry) { edit(form.copy(entry = it)) }
    SwitchRow(I18n.text("groups.create.listed"), I18n.text("groups.create.listedHint"), form.listed) { edit(form.copy(listed = it)) }
    Text(I18n.text("groups.admin.accent"), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        state.accents.forEach { accent ->
            Box(
                Modifier
                    .size(34.dp)
                    .background(CircleInk.of(accent), CircleShape)
                    .border(if (form.accent == accent) 3.dp else 1.dp, if (form.accent == accent) Brand.Paper else Brand.Ink, CircleShape)
                    .clickable { edit(form.copy(accent = accent)) }
                    .semantics { selected = form.accent == accent },
            )
        }
    }
    form.error?.let { ErrorText(it) }
    PrimaryButton(I18n.text("common.save"), { send(GroupAdminIntent.SaveSettings) }, enabled = form.canSave)
    if (state.owner) {
        Spacer(Modifier.size(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Brand.Danger, RoundedCornerShape(8.dp))
                .clickable { send(GroupAdminIntent.AskDelete) }
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(I18n.text("groups.admin.deleteGroup"), style = MaterialTheme.typography.labelLarge.copy(color = Brand.Danger)) }
    }
}

@Composable
private fun ColumnScope.Members(
    state: GroupAdminUiState,
    send: (GroupAdminIntent) -> Unit,
    onConfirm: (String, MemberActionUi) -> Unit,
) {
    if (state.tab == AdminTab.Members) {
        BrandField(I18n.text("groups.admin.searchMembers"), state.search, { send(GroupAdminIntent.Search(it)) })
    }
    if (state.members.isEmpty()) {
        val empty =
            when (state.tab) {
                AdminTab.Requests -> "groups.admin.noRequests"
                AdminTab.Banned -> "groups.admin.noBanned"
                else -> "groups.admin.noMembers"
            }
        Text(I18n.text(empty), style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
    }
    state.members.forEach { m -> MemberRow(m, send, onConfirm) }
    if (state.hasMore) GhostButton(I18n.text("groups.chat.older"), { send(GroupAdminIntent.LoadMore) })
}

@Composable
private fun MemberRow(
    m: MemberRowUi,
    send: (GroupAdminIntent) -> Unit,
    onConfirm: (String, MemberActionUi) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(10.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(28.dp).background(CircleInk.of(m.slot), CircleShape).border(2.dp, Brand.Ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.name.take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink, fontWeight = FontWeight.Black),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    listOfNotNull(m.detail, m.since).joinToString(" ✦ "),
                    style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted),
                    maxLines = 2,
                )
            }
        }
        if (m.actions.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.actions.forEach { action ->
                    Box(
                        Modifier
                            .border(1.dp, if (action.destructive) Brand.Danger else Brand.Line, RoundedCornerShape(8.dp))
                            .clickable {
                                if (action.confirm !=
                                    null
                                ) {
                                    onConfirm(m.userId, action)
                                } else {
                                    send(GroupAdminIntent.Act(m.userId, action.action))
                                }
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelLarge.copy(color = if (action.destructive) Brand.Danger else Brand.Paper),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Reports(
    state: GroupAdminUiState,
    send: (GroupAdminIntent) -> Unit,
) {
    if (state.reports.isEmpty()) {
        Text(
            I18n.text("groups.admin.noReports"),
            style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted),
        )
    }
    state.reports.forEach { r ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brand.Ink2, RoundedCornerShape(10.dp))
                .border(1.dp, if (r.held) Brand.Yellow else Brand.Line, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(r.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            Text(
                if (r.held) "${r.detail} ✦ ${I18n.text("groups.admin.held")}" else r.detail,
                style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(I18n.text("groups.actions.remove"), false) { send(GroupAdminIntent.RemoveReported(r.messageId)) }
                Choice(I18n.text("groups.actions.restore"), false) { send(GroupAdminIntent.RestoreReported(r.messageId)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.Badge(
    state: GroupAdminUiState,
    send: (GroupAdminIntent) -> Unit,
) {
    val badge = state.badge
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VerifiedMark(I18n.text("groups.badge.title"))
        Text(I18n.text("groups.badge.title"), style = MaterialTheme.typography.titleMedium)
    }
    Text(I18n.text("groups.badge.lead"), style = MaterialTheme.typography.bodySmall)
    badge.latest?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Yellow)) }
    badge.checks.forEach { c ->
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${c.label}: ${c.value}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (c.passed) "✓" else "✕",
                style = MaterialTheme.typography.labelLarge.copy(color = if (c.passed) Brand.Cyan else Brand.Danger),
            )
            Text(c.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(c.value, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (badge.pending) {
        GhostButton(I18n.text("groups.badge.withdraw"), { send(GroupAdminIntent.WithdrawBadge) })
    } else if (badge.eligible) {
        BrandField(I18n.text("groups.badge.reason"), badge.reason, { send(GroupAdminIntent.BadgeReason(it)) }, help = badge.reasonHint)
        BrandField(I18n.text("groups.badge.links"), badge.links, { send(GroupAdminIntent.BadgeLinks(it)) })
        PrimaryButton(I18n.text("groups.badge.ask"), { send(GroupAdminIntent.AskBadge) }, enabled = badge.canAsk)
    }
}
