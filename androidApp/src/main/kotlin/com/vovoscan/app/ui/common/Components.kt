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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.theme.Brand

/**
 * Bouton principal de la marque : fond rouge, texte encre, bordure encre, ombre dure papier sans flou.
 * Appuyé, il s'enfonce de 2 px et son ombre rétrécit (150 ms), avec un retour haptique.
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
    Box(modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = shadow, y = shadow)
                .background(Brand.Paper, RoundedCornerShape(8.dp)),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .offset(x = 6.dp - shadow, y = 6.dp - shadow)
                .background(Brand.Red, RoundedCornerShape(8.dp))
                .border(2.dp, Brand.Ink, RoundedCornerShape(8.dp))
                .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }.padding(vertical = 15.dp, horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Brand.Ink)
        }
    }
}

/** Bouton secondaire : transparent, texte papier, bordure fine. */
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

/** Champ de l'application : fond `inputBg`, bordure `line`, libellé 13 px au-dessus. */
@Composable
fun BrandField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
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
        if (error != null) ErrorText(error)
    }
}

@Composable
fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Danger), modifier = Modifier.padding(top = 6.dp))
}

/** Petit bandeau hors ligne : discret, la copie locale reste au centre. */
@Composable
fun OfflineNotice(text: String) {
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
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Pastille « kicker » : fond papier, texte encre, majuscules, inclinée de -2°. */
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

/** Logo : carré rouge incliné de -6°, bordure papier, ombre dure papier, « ヴ » en encre. */
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
