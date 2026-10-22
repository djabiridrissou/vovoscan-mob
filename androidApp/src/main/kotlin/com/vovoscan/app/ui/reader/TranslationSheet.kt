package com.vovoscan.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.settings.KeysScreen
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.reader.presentation.TranslationIntent
import com.vovoscan.feature.reader.presentation.TranslationOffer
import com.vovoscan.feature.reader.presentation.TranslationUiState

/** The reader's language button: the language in use, red while a translation shows, cyan while one is made. */
@Composable
fun LanguageButton(
    state: TranslationUiState,
    onClick: () -> Unit,
) {
    val label = I18n.textWith("translation.languageA11y", mapOf("language" to state.buttonLabel))
    Row(
        Modifier
            .padding(start = 12.dp)
            .background(if (state.translated) Brand.Red else Brand.Ink2, MaterialTheme.shapes.extraLarge)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { this.selected = state.translated }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.working || state.checking != null) {
            CircularProgressIndicator(Modifier.size(12.dp), color = Brand.Cyan, strokeWidth = 2.dp)
        } else {
            Text("文A", style = MaterialTheme.typography.labelSmall.copy(color = if (state.translated) Brand.Ink else Brand.Paper))
        }
        Text(
            state.buttonLabel,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge.copy(color = if (state.translated) Brand.Ink else Brand.Paper),
        )
    }
}

/**
 * Every language, those already made first ("Ready": shared, free, shown at once); one nobody made yet shows its cost
 * first (own key, credits, or "Add a key"), then its progress, and the page swaps its lettering when it is ready.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSheet(
    state: TranslationUiState,
    onIntent: (TranslationIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    var keysOpen by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = {
            onIntent(TranslationIntent.DismissMessage)
            onIntent(TranslationIntent.DismissOffer)
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
        contentColor = Brand.Paper,
    ) {
        if (keysOpen) {
            // "Add a key" from an offer: the keys here, the reader stays where it was.
            Column(Modifier.fillMaxHeight(0.9f)) { KeysScreen(onBack = { keysOpen = false }) }
            return@ModalBottomSheet
        }
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(I18n.text("translation.languageTitle"), style = MaterialTheme.typography.titleLarge)
                    BrandField(I18n.text("common.searchLanguage"), state.query, { onIntent(TranslationIntent.Query(it)) })
                    state.checking?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted)) }
                    state.offer?.let { OfferCard(it, onIntent) { keysOpen = true } }
                    state.progressLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Cyan)) }
                    state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    state.error?.let {
                        ErrorText(it)
                        GhostButton(I18n.text("common.tryAgain"), { onIntent(TranslationIntent.Retry) })
                    }
                }
            }
            items(state.options, key = { it.code ?: "original" }) { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (option.selected) Brand.Line else Brand.Ink2)
                        .clickable {
                            onIntent(TranslationIntent.Choose(option.code))
                            // A language already made shows at once; a new one keeps the sheet for its cost and progress.
                            if (option.ready) onDismiss()
                        }.semantics { selected = option.selected }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        if (option.englishName.isNotEmpty()) {
                            Text(option.englishName, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted))
                        }
                    }
                    option.statusLabel?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium.copy(color = if (option.ready) Brand.Yellow else Brand.Cyan))
                    }
                    if (option.selected) Text("  ✓", style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red))
                }
            }
        }
    }
}

/** "Translate into Yorùbá?": what it costs this account, then Translate / Add a key / Not now. Nothing is paid before. */
@Composable
private fun OfferCard(
    offer: TranslationOffer,
    onIntent: (TranslationIntent) -> Unit,
    onAddKey: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink, MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(offer.title, style = MaterialTheme.typography.titleMedium)
        Text(offer.line, style = MaterialTheme.typography.bodyMedium.copy(color = if (offer.canTranslate) Brand.Paper else Brand.Yellow))
        if (offer.canTranslate) PrimaryButton(offer.translateLabel, { onIntent(TranslationIntent.ConfirmOffer) })
        offer.addKeyLabel?.let { GhostButton(it, onAddKey) }
        Text(
            offer.cancelLabel,
            style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
            modifier = Modifier.clickable { onIntent(TranslationIntent.DismissOffer) }.padding(vertical = 4.dp),
        )
    }
}
