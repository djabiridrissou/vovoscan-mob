package com.vovoscan.app.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.vovoscan.app.ui.common.BrandField
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.sources.domain.SettingType
import com.vovoscan.feature.sources.presentation.SettingFieldUi
import com.vovoscan.feature.sources.presentation.SourceInfoUi
import com.vovoscan.feature.sources.presentation.SourceLabels

/** Source icon (https or `data:` URL), or its initial in a square when there is none or it fails to load. */
@Composable
fun SourceIcon(
    iconUrl: String?,
    initial: String,
    size: Int = 40,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(size.dp)
            .clip(shape)
            .background(Brand.Ink)
            .border(1.dp, Brand.Line, shape),
        contentAlignment = Alignment.Center,
    ) {
        val letter = @Composable { Text(initial, style = MaterialTheme.typography.titleMedium) }
        if (iconUrl == null) {
            letter()
        } else {
            SubcomposeAsyncImage(
                iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { letter() } },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { letter() } },
            )
        }
    }
}

/** "Official" / "Third-party" chip. */
@Composable
fun OriginChip(
    label: String,
    thirdParty: Boolean,
) {
    val color = if (thirdParty) Brand.Yellow else Brand.Muted
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(color = color),
        modifier =
            Modifier
                .border(1.dp, color, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Red "Update" badge, ink text. */
@Composable
fun UpdateBadge(label: String = I18n.text("sources.update")) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink),
        modifier =
            Modifier
                .background(Brand.Red, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Small red action (row buttons): ink text on red. */
@Composable
fun SmallAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink),
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) Brand.Red else Brand.Line)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Short confirmation or error, dismissed with ✕. */
@Composable
fun MessageBanner(
    text: String,
    error: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, if (error) Brand.Danger else Brand.Line, RoundedCornerShape(12.dp))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(color = if (error) Brand.Danger else Brand.Paper),
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        Text(
            "✕",
            style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted),
            modifier = Modifier.clickable(onClick = onDismiss).padding(14.dp),
        )
    }
}

/** Card: ink-2, 1 dp line border, radius 12. */
@Composable
fun SourceCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** What a source says about itself: preview before install and detail screen. */
@Composable
fun SourceInfoBlock(info: SourceInfoUi) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SourceIcon(info.iconUrl, info.initial, size = 56)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.versionLabel, style = MaterialTheme.typography.bodySmall)
                    OriginChip(SourceLabels.chip(info.thirdParty), info.thirdParty)
                }
            }
        }
        Column {
            Text(info.originLabel, style = MaterialTheme.typography.bodySmall)
            info.originUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.TextSoft)) }
        }
        if (info.summary.isNotBlank()) Text(info.summary, style = MaterialTheme.typography.bodyMedium)
        val meta = listOf(info.kindsLabel, info.langsLabel).filter { it.isNotBlank() }
        if (meta.isNotEmpty()) Text(meta.joinToString(" ✦ "), style = MaterialTheme.typography.bodySmall)
        if (info.rightsLabel != null || info.rightsNotes != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(I18n.text("sources.rights"), style = MaterialTheme.typography.labelLarge)
                info.rightsLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                info.rightsNotes?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.TextSoft)) }
                info.rightsDisclaimer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (info.hostsLabel != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(I18n.text("sources.connectsTo"), style = MaterialTheme.typography.labelLarge)
                info.hosts.forEach { Text("✦ $it", style = MaterialTheme.typography.bodySmall.copy(color = Brand.TextSoft)) }
            }
        }
    }
}

/** A setting field: keyboard by type, default value as a hint, help and error below. */
@Composable
fun SettingInput(
    field: SettingFieldUi,
    onChange: (String) -> Unit,
) {
    BrandField(
        label = if (field.required) "${field.label} *" else field.label,
        value = field.value,
        onValueChange = onChange,
        error = field.error,
        keyboard =
            when (field.type) {
                SettingType.Url -> KeyboardType.Uri
                SettingType.Integer -> KeyboardType.Number
                SettingType.Text -> KeyboardType.Text
            },
        placeholder = field.placeholder,
        help = field.help,
    )
}

/** A setting shown read-only (official source). */
@Composable
fun SettingValue(field: SettingFieldUi) {
    Column {
        Text(field.label, style = MaterialTheme.typography.bodySmall)
        Text(field.value.ifEmpty { field.placeholder ?: "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Brand alert: confirm in red, cancel in paper. */
@Composable
fun BrandAlert(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Brand.Ink2,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red)) }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
            ) { Text(I18n.text("common.cancel"), style = MaterialTheme.typography.labelLarge.copy(color = Brand.Paper)) }
        },
    )
}
