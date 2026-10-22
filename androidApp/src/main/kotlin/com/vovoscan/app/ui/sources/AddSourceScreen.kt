package com.vovoscan.app.ui.sources

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.sources.presentation.AddSourceIntent
import com.vovoscan.feature.sources.presentation.AddSourceStep
import com.vovoscan.feature.sources.presentation.AddSourceUiState
import com.vovoscan.feature.sources.presentation.AddSourceViewModel
import com.vovoscan.feature.sources.presentation.OfficialOfferUi
import com.vovoscan.feature.sources.presentation.RepositoryEntryRow
import com.vovoscan.feature.sources.presentation.SourcePreviewUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel

/**
 * "Add a source": a link (single source or repository) or a `.vovo` file, then a preview or the repository's
 * sources. With [repositoryId], browses a saved repository. No logic here (AddSourceViewModel).
 */
@Composable
fun AddSourceScreen(
    repositoryId: String?,
    onBack: () -> Unit,
    onOpenSource: (key: String) -> Unit,
    viewModel: AddSourceViewModel = koinViewModel(key = "add-source"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onIntent
    // The ViewModel outlives the screen (activity scope): start fresh once per opening, not on recreation.
    var started by rememberSaveable(repositoryId) { mutableStateOf(false) }
    LaunchedEffect(repositoryId) {
        if (!started) {
            started = true
            send(AddSourceIntent.Reset)
            repositoryId?.let { send(AddSourceIntent.Browse(it)) }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { runCatching { readFile(context, uri) }.getOrNull() }
                    if (file != null) send(AddSourceIntent.ImportFile(file.second, file.first))
                }
            }
        }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(I18n.text("common.backArrow"), onBack)
                Kicker(I18n.text("nav.sources"))
                Text(I18n.text("sources.addHeadline"), style = MaterialTheme.typography.headlineSmall)
                state.officialOffers.forEach { OfficialCard(it) { send(AddSourceIntent.EnableOfficial(it.key)) } }
                Warning(state.warning)
                state.error?.let { MessageBanner(it, error = true) { send(AddSourceIntent.DismissMessage) } }
                state.notice?.let { MessageBanner(it, error = false) { send(AddSourceIntent.DismissMessage) } }
            }
        }
        when (state.step) {
            AddSourceStep.Input, AddSourceStep.Loading ->
                item {
                    LinkInput(state, send) { picker.launch(arrayOf("*/*")) }
                }
            AddSourceStep.Preview ->
                state.preview?.let { preview ->
                    item {
                        PreviewCard(preview, state, send, onOpenSource)
                    }
                }
            AddSourceStep.Repository ->
                state.repository?.let { repo ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(repo.name, style = MaterialTheme.typography.titleMedium)
                            Text(repo.url, style = MaterialTheme.typography.bodySmall)
                            repo.homepage?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.TextSoft)) }
                            Text(repo.countLabel, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(repo.entries, key = { it.key }) { entry ->
                        EntryRow(entry, enabled = !state.installing) { send(AddSourceIntent.InstallEntry(entry.key)) }
                    }
                }
        }
        if (state.step == AddSourceStep.Preview || state.step == AddSourceStep.Repository) {
            item { GhostButton(I18n.text("sources.anotherLink"), { send(AddSourceIntent.Reset) }) }
        }
    }

    state.hostsConfirmation?.let { message ->
        BrandAlert(
            title = I18n.text("sources.newConnections"),
            message = message,
            confirmLabel = I18n.text("common.continue"),
            onConfirm = { send(AddSourceIntent.ConfirmHosts) },
            onCancel = { send(AddSourceIntent.CancelHosts) },
        )
    }
    if (state.showsSettingsForm) SettingsSheet(state, send)
}

@Composable
private fun Warning(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(color = Brand.TextSoft),
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, Brand.Yellow, RoundedCornerShape(12.dp))
                .padding(14.dp),
    )
}

@Composable
private fun LinkInput(
    state: AddSourceUiState,
    send: (AddSourceIntent) -> Unit,
    onPickFile: () -> Unit,
) {
    val loading = state.step == AddSourceStep.Loading
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandField(
            I18n.text("sources.linkLabel"),
            state.link,
            { send(AddSourceIntent.LinkChanged(it)) },
            keyboard = KeyboardType.Uri,
            placeholder = "https://",
        )
        PrimaryButton(if (loading) I18n.text("common.checking") else I18n.text("sources.addArrow"), {
            send(AddSourceIntent.SubmitLink)
        }, enabled = state.canSubmit)
        GhostButton(I18n.text("sources.chooseFile"), { if (!loading) onPickFile() })
    }
}

@Composable
private fun PreviewCard(
    preview: SourcePreviewUi,
    state: AddSourceUiState,
    send: (AddSourceIntent) -> Unit,
    onOpenSource: (String) -> Unit,
) {
    SourceCard {
        SourceInfoBlock(preview.info)
        preview.newHostsLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Yellow)) }
        preview.statusLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        preview.actionLabel?.let { label ->
            PrimaryButton(
                if (state.installing) I18n.text("sources.installing") else "$label →",
                { send(AddSourceIntent.Install) },
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                enabled = !state.installing,
            )
        }
        if (state.installedKey == preview.info.key) GhostButton(I18n.text("sources.open"), { onOpenSource(preview.info.key) })
    }
}

@Composable
private fun EntryRow(
    entry: RepositoryEntryRow,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SourceIcon(entry.iconUrl, entry.initial)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium)
            val meta = listOf(entry.versionLabel, entry.kindsLabel, entry.langsLabel).filter { it.isNotBlank() }
            Text(meta.joinToString(" ✦ "), style = MaterialTheme.typography.bodySmall)
            entry.rightsLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            entry.changes?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.TextSoft)) }
            entry.statusLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Paper)) }
        }
        val action = entry.actionLabel
        when {
            entry.busy -> Text("…", style = MaterialTheme.typography.titleMedium.copy(color = Brand.Cyan))
            action != null -> SmallAction(action, enabled = enabled, onClick = onInstall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    state: AddSourceUiState,
    send: (AddSourceIntent) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { send(AddSourceIntent.CancelSettings) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.settingsTitle ?: I18n.text("nav.settings"), style = MaterialTheme.typography.titleMedium)
            state.settingsForm.forEach { field ->
                SettingInput(field) { send(AddSourceIntent.SettingChanged(field.name, it)) }
            }
            PrimaryButton(
                if (state.installing) I18n.text("sources.installing") else I18n.text("common.confirmArrow"),
                { send(AddSourceIntent.ConfirmSettings) },
                enabled = !state.installing,
            )
            GhostButton(I18n.text("common.cancel"), { send(AddSourceIntent.CancelSettings) })
        }
    }
}

/** Name and bytes of a picked file (small: a manifest or a `.vovo` package). */
private fun readFile(
    context: Context,
    uri: Uri,
): Pair<String, ByteArray>? {
    val resolver = context.contentResolver
    val name =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "source.vovo"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return name to bytes
}

/** vovoscan's own catalog (and any official source left off), first: one tap turns it on. */
@Composable
private fun OfficialCard(
    offer: OfficialOfferUi,
    onTurnOn: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(14.dp))
            .border(1.5.dp, Brand.Red, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(offer.iconUrl, offer.initial)
            Column {
                Text(offer.name, style = MaterialTheme.typography.titleMedium.copy(color = Brand.Paper))
                Text(offer.kindsLabel, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted))
            }
        }
        if (offer.summary.isNotEmpty()) Text(offer.summary, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Paper))
        PrimaryButton(offer.actionLabel, onTurnOn)
    }
}
