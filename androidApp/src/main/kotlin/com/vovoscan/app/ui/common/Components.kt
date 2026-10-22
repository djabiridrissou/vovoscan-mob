package com.vovoscan.app.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n

/**
 * Brand primary button: red background, ink text, ink border, hard paper shadow with no blur.
 * When pressed, it sinks by 2 px and its shadow shrinks (150 ms), with haptic feedback.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shadow by animateDpAsState(if (pressed) 2.dp else 6.dp, tween(150), label = "shadow")
    val haptics = LocalHapticFeedback.current
    // Disabled: flat ink with a hairline and muted text, no offset shadow, so it never reads as a faded action.
    Box(modifier.fillMaxWidth()) {
        if (enabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(x = shadow, y = shadow)
                    .background(Brand.Paper, RoundedCornerShape(8.dp)),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset(x = 6.dp - shadow, y = 6.dp - shadow)
                .background(if (enabled) Brand.Red else Brand.Ink2, RoundedCornerShape(8.dp))
                .border(if (enabled) 2.dp else 1.dp, if (enabled) Brand.Ink else Brand.Line, RoundedCornerShape(8.dp))
                .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }.padding(vertical = 15.dp, horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) Brand.Ink else Brand.Muted)
        }
    }
}

/** Secondary button: transparent, paper text, thin border. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .border(1.dp, Brand.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Paper))
    }
}

/** App text field: `inputBg` background, `line` border, 13 px label above. */
@Composable
fun BrandField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    placeholder: String? = null,
    help: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted)) } },
            isError = error != null,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Brand.InputBg,
                    unfocusedContainerColor = Brand.InputBg,
                    focusedBorderColor = Brand.Red,
                    unfocusedBorderColor = Brand.Line,
                    errorBorderColor = Brand.Danger,
                    cursorColor = Brand.Red,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        if (error != null) ErrorText(error)
    }
}

@Composable
fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Danger), modifier = Modifier.padding(top = 6.dp))
}

/** Small offline banner: discreet, the local copy stays in focus. `onRetry`: a source that may answer again. */
@Composable
fun OfflineNotice(
    text: String,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(999.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Brand.Muted, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (onRetry != null) {
            Text(
                I18n.text("common.retry"),
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red),
                modifier = Modifier.clickable(onClick = onRetry).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/** "Kicker" badge: paper background, ink text, uppercase, tilted -2°. */
@Composable
fun Kicker(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink),
        modifier =
            Modifier
                .rotate(-2f)
                .background(Brand.Paper)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Logo: red square tilted -6°, paper border, hard paper shadow, "ヴ" in ink. */
@Composable
fun LogoMark(size: Int = 32) {
    Box(Modifier.size(size.dp).rotate(-6f)) {
        Box(Modifier.matchParentSize().offset(3.dp, 3.dp).background(Brand.Paper))
        Box(
            Modifier.matchParentSize().background(Brand.Red).border(2.dp, Brand.Paper),
            contentAlignment = Alignment.Center,
        ) {
            Text("ヴ", style = MaterialTheme.typography.titleMedium.copy(color = Brand.Ink))
        }
    }
}

/** An empty screen that leads somewhere: a title, one sentence, one action (iOS `EmptyStateCard`). */
@Composable
fun EmptyStateCard(
    title: String,
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
            .padding(20.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(color = Brand.Paper))
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(action, onAction)
        }
    }
}
