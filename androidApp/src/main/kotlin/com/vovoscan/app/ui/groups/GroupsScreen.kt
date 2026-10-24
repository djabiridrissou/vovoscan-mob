package com.vovoscan.app.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.AppIcons
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.app.ui.together.CircleInk
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.groups.domain.Entry
import com.vovoscan.feature.groups.presentation.CreateForm
import com.vovoscan.feature.groups.presentation.GroupRow
import com.vovoscan.feature.groups.presentation.GroupsIntent
import com.vovoscan.feature.groups.presentation.GroupsViewModel
import com.vovoscan.feature.notifications.presentation.NotificationsViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/*
 * Groups (shared `GroupsViewModel`): the caller's groups, search or a pasted link, Discover, and "New group". The
 * group itself, its games and its settings open from the root (`MainActivity`), the same as iOS (`GroupsViews.swift`).
 */

/** The Groups tab. */
@Composable
fun GroupsScreen(
    onOpenGroup: (ref: String) -> Unit,
    onOpenNotifications: () -> Unit,
    viewModel: GroupsViewModel = koinViewModel(),
    notifications: NotificationsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inbox by notifications.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onIntent(GroupsIntent.Start) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(GroupsIntent.Stop) }
    state.open?.let { open ->
        LaunchedEffect(open.seq) {
            onOpenGroup(open.groupId)
            viewModel.onIntent(GroupsIntent.OpenHandled(open.seq))
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kicker(I18n.text("groups.title"))
                    }
                    Bell(inbox.badge, inbox.unread, onOpenNotifications)
                }
                BrandField(
                    label = I18n.text("common.search"),
                    value = state.query,
                    onValueChange = { viewModel.onIntent(GroupsIntent.Query(it)) },
                    placeholder = I18n.text("groups.searchPlaceholder"),
                )
                state.notice?.let { OfflineNotice(it) { viewModel.onIntent(GroupsIntent.Refresh) } }
                state.error?.let { ErrorText(it) }
                PrimaryButton(I18n.text("groups.create.open"), { viewModel.onIntent(GroupsIntent.ShowCreate) })
            }
        }
        if (state.discovering) {
            item { SectionTitle(I18n.text("groups.mine")) }
            if (!state.loading && state.mine.isEmpty()) {
                item { Text(state.emptyMine, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted)) }
            }
            items(state.mine, key = { "mine/" + it.id }) { row -> GroupCard(row) { onOpenGroup(row.id) } }
        }
        item { SectionTitle(state.resultsTitle) }
        items(state.results, key = { "found/" + it.id }) { row -> GroupCard(row) { onOpenGroup(row.id) } }
        state.emptyResults?.let { line -> item { Text(line, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted)) } }
        if (state.searching) {
            item {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
        } else if (!state.resultsEnd && state.results.isNotEmpty()) {
            item { LaunchedEffect(state.results.size) { viewModel.onIntent(GroupsIntent.LoadMore) } }
        }
    }
    state.create?.let { form ->
        CreateSheet(
            form = form,
            onEdit = { viewModel.onIntent(GroupsIntent.EditCreate(it)) },
            onSubmit = { viewModel.onIntent(GroupsIntent.SubmitCreate) },
            onDismiss = { viewModel.onIntent(GroupsIntent.CancelCreate) },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Muted, letterSpacing = 1.2.sp),
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** The inbox's bell, with its count in red. */
@Composable
fun Bell(
    badge: String?,
    unread: Int,
    onClick: () -> Unit,
) {
    val label = if (unread > 0) t("notifications.unreadA11y", "count" to unread) else I18n.text("notifications.title")
    Box(
        Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Bell, contentDescription = null, tint = Brand.Paper, modifier = Modifier.size(24.dp))
        badge?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 10.sp),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .background(Brand.Red, RoundedCornerShape(999.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** A group's emblem: its first letter on its colour, a hard offset like the brand's buttons. */
@Composable
fun GroupEmblem(
    name: String,
    accent: Int,
    size: Int = 48,
) {
    Box(Modifier.size(size.dp)) {
        Box(Modifier.matchParentSizeOffset(3).background(Brand.Paper, RoundedCornerShape(10.dp)))
        Box(
            Modifier
                .size((size - 3).dp)
                .background(CircleInk.of(accent), RoundedCornerShape(10.dp))
                .border(2.dp, Brand.Ink, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(color = Brand.Ink, fontWeight = FontWeight.Black),
            )
        }
    }
}

private fun Modifier.matchParentSizeOffset(offset: Int): Modifier = padding(start = offset.dp, top = offset.dp).fillMaxSize()

/** The blue badge: a verified group. */
@Composable
fun VerifiedMark(label: String) {
    Box(
        Modifier
            .size(16.dp)
            .background(Color(0xFF3B82F6), CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", style = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp))
    }
}

@Composable
private fun GroupCard(
    row: GroupRow,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, if (row.unread != null) Brand.Red else Brand.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = row.a11y },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupEmblem(row.name, row.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium.copy(color = Brand.Paper),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.verified) VerifiedMark(I18n.text("groups.verified"))
            }
            Text(
                row.meta,
                style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.line.isNotEmpty()) {
                Text(
                    row.line,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Brand.TextSoft),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.unread?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(Brand.Red, RoundedCornerShape(999.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            row.pending?.let {
                Text(it, style = MaterialTheme.typography.labelSmall.copy(color = Brand.Yellow))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSheet(
    form: CreateForm,
    onEdit: (CreateForm) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(I18n.text("groups.create.title"), style = MaterialTheme.typography.titleLarge)
            BrandField(I18n.text("groups.create.name"), form.name, { onEdit(form.copy(name = it)) })
            BrandField(I18n.text("groups.create.handle"), form.handle, { onEdit(form.copy(handle = it)) }, help = form.handleHint)
            BrandField(I18n.text("groups.create.about"), form.about, { onEdit(form.copy(about = it)) })
            EntryChoice(form.entry) { onEdit(form.copy(entry = it)) }
            SwitchRow(
                I18n.text("groups.create.listed"),
                I18n.text("groups.create.listedHint"),
                form.listed,
            ) { onEdit(form.copy(listed = it)) }
            form.error?.let { ErrorText(it) }
            PrimaryButton(I18n.text("groups.create.submit"), onSubmit, enabled = form.canSubmit)
            GhostButton(I18n.text("common.cancel"), onDismiss)
            Spacer(Modifier.size(8.dp))
        }
    }
}

/** Open to all, or on approval. */
@Composable
fun EntryChoice(
    entry: Entry,
    onChoose: (Entry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(I18n.text("groups.create.entry"), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Entry.Open to "groups.entry.open", Entry.Approval to "groups.entry.approval").forEach { (value, key) ->
                Choice(I18n.text(key), entry == value, Modifier.weight(1f)) { onChoose(value) }
            }
        }
    }
}

/** A chip in a row of choices: red border when chosen. */
@Composable
fun Choice(
    label: String,
    chosen: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(if (chosen) Brand.Red else Brand.Ink, RoundedCornerShape(8.dp))
            .border(1.dp, if (chosen) Brand.Ink else Brand.Line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    color =
                        if (chosen) {
                            Brand.Ink
                        } else if (enabled) {
                            Brand.Paper
                        } else {
                            Brand.Muted
                        },
                ),
            maxLines = 1,
        )
    }
}

@Composable
fun SwitchRow(
    label: String,
    help: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
        )
    }
}
