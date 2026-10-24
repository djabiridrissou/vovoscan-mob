package com.vovoscan.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.AppIcons
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.groups.BackBar
import com.vovoscan.app.ui.groups.SwitchRow
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.notifications.presentation.NotificationRow
import com.vovoscan.feature.notifications.presentation.NotificationTarget
import com.vovoscan.feature.notifications.presentation.NotificationsIntent
import com.vovoscan.feature.notifications.presentation.NotificationsUiState
import com.vovoscan.feature.notifications.presentation.NotificationsViewModel
import org.koin.compose.koinInject

/*
 * The inbox (shared `NotificationsViewModel`, one for the app): newest first, a tap opens its group or game and marks it
 * read, "Mark all read", and the settings (pushes on this phone, kinds turned off). iOS: `NotificationsView.swift`.
 */

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpen: (NotificationTarget) -> Unit,
    viewModel: NotificationsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onIntent
    var settings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { send(NotificationsIntent.Refresh) }
    state.open?.let { open ->
        LaunchedEffect(open.seq) {
            onOpen(open.target)
            send(NotificationsIntent.OpenHandled(open.seq))
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(I18n.text("notifications.title"), onBack) {
            if (state.unread > 0) {
                Text(
                    I18n.text("notifications.readAll"),
                    style = MaterialTheme.typography.labelLarge.copy(color = Brand.Cyan),
                    modifier = Modifier.clickable { send(NotificationsIntent.ReadAll) }.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clickable {
                        send(NotificationsIntent.LoadPreferences)
                        settings = true
                    }.semantics { contentDescription = I18n.text("notifications.settings") },
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.Settings, contentDescription = null, tint = Brand.Paper, modifier = Modifier.size(22.dp)) }
        }
        state.notice?.let {
            Box(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) { OfflineNotice(it) { send(NotificationsIntent.Refresh) } }
        }
        state.error?.let { Box(Modifier.padding(horizontal = 16.dp)) { ErrorText(it) } }
        when {
            state.loading && state.rows.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Cyan) }
            state.rows.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(state.empty, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
                }
            else ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rows, key = { it.id }) { row -> NotificationItem(row) { send(NotificationsIntent.Open(row.id)) } }
                    if (state.hasMore) {
                        item(key = "more") {
                            LaunchedEffect(state.rows.size) { send(NotificationsIntent.LoadMore) }
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Brand.Cyan, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
        }
    }
    if (settings) Settings(state, send) { settings = false }
}

@Composable
private fun NotificationItem(
    row: NotificationRow,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (row.unread) Brand.Ink2 else Brand.Ink, RoundedCornerShape(12.dp))
            .border(1.dp, if (row.unread) Brand.Red else Brand.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(
                    when (row.icon) {
                        "game" -> Brand.Cyan
                        "badge" -> Brand.Yellow
                        else -> Brand.Paper
                    },
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (row.icon == "badge") {
                Text("✓", style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink, fontWeight = FontWeight.Black))
            } else {
                Icon(
                    if (row.icon ==
                        "game"
                    ) {
                        AppIcons.Catalog
                    } else {
                        AppIcons.Groups
                    },
                    contentDescription = null,
                    tint = Brand.Ink,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Text(row.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.age, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted))
            if (row.unread) Box(Modifier.size(8.dp).background(Brand.Red, CircleShape))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Settings(
    state: NotificationsUiState,
    send: (NotificationsIntent) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(I18n.text("notifications.settings"), style = MaterialTheme.typography.titleLarge)
            SwitchRow(I18n.text("notifications.push"), state.pushLine, state.prefs.push) { send(NotificationsIntent.SetPush(it)) }
            state.prefs.kinds.forEach { k -> SwitchRow(k.label, null, k.on) { send(NotificationsIntent.SetKind(k.kind, it)) } }
        }
    }
}
