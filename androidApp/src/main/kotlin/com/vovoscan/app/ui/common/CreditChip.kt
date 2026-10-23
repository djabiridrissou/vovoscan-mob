package com.vovoscan.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.account.presentation.WalletViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The account's credit balance as a small chip ("✦ 1,240 credits"), on the reading screens: the title page, the episode
 * card, the language pickers and docks. It reads the shared wallet, so a quote or a run anywhere updates every chip.
 * Hidden on a server without credits; ink on red when the wallet is empty.
 */
@Composable
fun CreditChip(
    modifier: Modifier = Modifier,
    wallet: WalletViewModel = koinViewModel(),
) {
    val state by wallet.state.collectAsStateWithLifecycle()
    if (!state.shown || state.badge.isEmpty()) return
    Text(
        "✦ ${state.badge}",
        style = MaterialTheme.typography.labelMedium.copy(color = if (state.empty) Brand.Ink else Brand.Yellow),
        modifier =
            modifier
                .background(if (state.empty) Brand.Red else Brand.Ink2, CircleShape)
                .border(1.dp, if (state.empty) Brand.Red else Brand.Line, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clearAndSetSemantics { contentDescription = state.badgeA11y },
    )
}
