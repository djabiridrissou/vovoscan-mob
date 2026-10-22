package com.vovoscan.feature.account.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.account.domain.Wallet
import com.vovoscan.feature.account.domain.WalletRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WalletUiState(
    /** False on a server without credits, and until the wallet was read once. */
    val shown: Boolean = false,
    /** "1,240". */
    val balanceLabel: String = "",
    /** "credits" / "credit". */
    val unitLabel: String = "",
    val explainer: String = WalletText.EXPLAINER,
    /** The daily limit or an empty wallet (without own keys); null when a run can go. */
    val warning: String? = null,
    val empty: Boolean = false,
    val loading: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
)

sealed interface WalletIntent {
    data object Refresh : WalletIntent
}

/** The credits card in Settings: the balance, what spends it, and why a run on the platform's keys would wait. */
class WalletViewModel(
    private val repository: WalletRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        repository
            .observeWallet()
            .onEach { wallet -> mutableState.update { if (wallet == null) it.copy(shown = false) else render(it, wallet) } }
            .launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: WalletIntent) {
        when (intent) {
            WalletIntent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        if (job?.isActive == true) return
        mutableState.update { it.copy(loading = true, error = null) }
        job =
            viewModelScope.launch {
                val result = repository.refreshWallet()
                mutableState.update {
                    when (result) {
                        is AppResult.Ok -> it.copy(loading = false, offline = false)
                        is AppResult.Err ->
                            it.copy(
                                loading = false,
                                offline = result.error == AppError.Network,
                                error = if (result.error == AppError.Network || !it.shown) null else WalletText.UNREADABLE,
                            )
                    }
                }
            }
    }

    private fun render(
        state: WalletUiState,
        wallet: Wallet,
    ) = state.copy(
        shown = true,
        balanceLabel = AccountText.grouped(wallet.balanceCredits.toDouble(), 0),
        unitLabel = WalletText.unit(wallet.balanceCredits),
        empty = wallet.balanceCredits <= 0 && !wallet.usesOwnKeys,
        explainer = if (wallet.usesOwnKeys) WalletText.OWN_KEYS else WalletText.EXPLAINER,
        warning =
            when {
                wallet.usesOwnKeys -> null
                wallet.dailyCapReached -> WalletText.DAILY_CAP
                wallet.balanceCredits <= 0 -> WalletText.EMPTY
                else -> null
            },
    )
}

object WalletText {
    val EXPLAINER: String get() = t("wallet.explainer")
    val EMPTY: String get() = t("wallet.empty")
    val OWN_KEYS: String get() = t("wallet.ownKeys")
    val DAILY_CAP: String get() = t("wallet.dailyCap")
    val UNREADABLE: String get() = t("wallet.unreadable")

    /** "credit" / "credits" after the balance (French puts 0 and 1 in the singular). */
    fun unit(balance: Long): String = t("wallet.unit", "count" to balance)
}
