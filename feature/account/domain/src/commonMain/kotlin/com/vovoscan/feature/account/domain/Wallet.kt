package com.vovoscan.feature.account.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * The credits the platform's keys spend for this account. Runs on the user's own keys cost no credit; without keys,
 * the platform's keys run and the wallet pays, and a run is refused at zero. Only the server tops a wallet up.
 */
data class Wallet(
    val balanceCredits: Long,
    /** The account has its own keys: its runs use them and spend no credit. */
    val usesOwnKeys: Boolean,
    /** The platform's daily limit is reached: runs on its keys wait until tomorrow. */
    val dailyCapReached: Boolean,
)

/** What a run would cost before it starts (`POST /quotes`). */
enum class QuoteKind(
    val wire: String,
) {
    Episode("episode"),
    Translation("translation"),
    Export("export"),
}

data class Quote(
    val credits: Long,
    /** The account's own keys pay: no credit is spent. */
    val coveredByOwnKeys: Boolean,
    /** A shared result already exists: watching or reading it costs nothing and nothing is generated. */
    val alreadyAvailable: Boolean,
    /** The worst case the wallet must hold to start. */
    val maxCredits: Long = credits,
    /** Every paid step can run (own keys or credits); false: a step is skipped, or the wallet is short. */
    val fundable: Boolean = true,
    /** Providers paid in credits; null when the server doesn't list them. */
    val metered: Set<String>? = null,
    /** Providers nobody pays for (the step is skipped); null when the server doesn't list them. */
    val unfunded: Set<String>? = null,
)

interface WalletRepository {
    /** The last wallet read this session; null until read, or when the server has no wallet. */
    fun observeWallet(): Flow<Wallet?>

    /** `Ok(null)`: this server has no credits wallet (nothing to show). Needs the network; never kept on disk. */
    suspend fun refreshWallet(): AppResult<Wallet?>

    /** `Ok(null)`: this server has no credits (nothing to say about the cost). Needs the network. */
    suspend fun quote(
        kind: QuoteKind,
        chapterId: String,
        lang: String? = null,
    ): AppResult<Quote?>

    fun clear()
}
