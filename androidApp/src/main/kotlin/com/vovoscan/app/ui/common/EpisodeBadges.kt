package com.vovoscan.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.theme.Brand

/** A filter that is on or off ("▶ Episodes"): red when on (text on red: ink). */
@Composable
fun ToggleChip(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(color = if (on) Brand.Ink else Brand.Paper),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .semantics { selected = on }
                .background(if (on) Brand.Red else Brand.Ink2, RoundedCornerShape(999.dp))
                .border(1.dp, if (on) Brand.Red else Brand.Line, RoundedCornerShape(999.dp))
                .clickable(role = Role.Switch, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** "▶ 3" on a cover, "▶ Français" on a chapter row: red, ink text. */
@Composable
fun EpisodeBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink),
        maxLines = 1,
        modifier = modifier.background(Brand.Red).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
