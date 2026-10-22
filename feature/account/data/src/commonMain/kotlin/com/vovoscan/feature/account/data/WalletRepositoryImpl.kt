package com.vovoscan.feature.account.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.feature.account.domain.Quote
import com.vovoscan.feature.account.domain.QuoteKind
import com.vovoscan.feature.account.domain.Wallet
import com.vovoscan.feature.account.domain.WalletRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class WalletDto(
    val balanceCredits: Long,
    val usesOwnKeys: Boolean = false,
    val dailyCapReached: Boolean = false,
)

@Serializable
internal data class QuoteBody(
    val kind: String,
    val chapterId: String,
    val lang: String? = null,
)

@Serializable
data class QuoteDto(
    val credits: Long,
    val coveredByOwnKeys: Boolean = false,
    val alreadyAvailable: Boolean = false,
    val maxCredits: Long? = null,
    val fundable: Boolean = true,
    val metered: List<String>? = null,
    val unfunded: List<String>? = null,
)

/** Remote source: the caller's own wallet (`api/v1/me/wallet`) and cost quotes (`api/v1/quotes`). */
interface WalletRemoteDataSource {
    suspend fun wallet(): AppResult<WalletDto>

    suspend fun quote(
        kind: String,
        chapterId: String,
        lang: String?,
    ): AppResult<QuoteDto>
}

class KtorWalletRemoteDataSource(
    private val http: HttpClient,
) : WalletRemoteDataSource {
    override suspend fun wallet(): AppResult<WalletDto> = http.apiCall<ApiData<WalletDto>> { get(WALLET_PATH) }.map { it.data }

    override suspend fun quote(
        kind: String,
        chapterId: String,
        lang: String?,
    ): AppResult<QuoteDto> =
        http
            .apiCall<ApiData<QuoteDto>> {
                post(QUOTES_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(QuoteBody(kind, chapterId, lang))
                }
            }.map { it.data }

    private companion object {
        const val WALLET_PATH = "api/v1/me/wallet"
        const val QUOTES_PATH = "api/v1/quotes"
    }
}

/**
 * The wallet, read each time the screen asks: a balance is only worth showing fresh, so it lives in memory only
 * (erased on sign-out with the rest).
 */
class WalletRepositoryImpl(
    private val remote: WalletRemoteDataSource,
) : WalletRepository {
    private val current = MutableStateFlow<Wallet?>(null)

    override fun observeWallet(): Flow<Wallet?> = current.asStateFlow()

    override suspend fun refreshWallet(): AppResult<Wallet?> {
        val result = remote.wallet()
        if (result is AppResult.Err && result.error.isNoWallet()) {
            current.value = null
            return AppResult.Ok(null)
        }
        return result.map { Wallet(it.balanceCredits.coerceAtLeast(0), it.usesOwnKeys, it.dailyCapReached) }.also {
            if (it is AppResult.Ok) current.value = it.value
        }
    }

    override suspend fun quote(
        kind: QuoteKind,
        chapterId: String,
        lang: String?,
    ): AppResult<Quote?> {
        val result = remote.quote(kind.wire, chapterId, lang)
        if (result is AppResult.Err && result.error.isNoWallet()) return AppResult.Ok(null)
        return result.map {
            val credits = it.credits.coerceAtLeast(0)
            Quote(
                credits = credits,
                coveredByOwnKeys = it.coveredByOwnKeys,
                alreadyAvailable = it.alreadyAvailable,
                maxCredits = it.maxCredits?.coerceAtLeast(0) ?: credits,
                fundable = it.fundable,
                metered = it.metered?.toSet(),
                unfunded = it.unfunded?.toSet(),
            )
        }
    }

    override fun clear() {
        current.value = null
    }
}

/** A server without credits answers 404 (route unknown) or a stable code: nothing to show, not an error. */
private fun AppError.isNoWallet(): Boolean =
    (this is AppError.Rejected && code in NO_WALLET_CODES) || (this is AppError.Unknown && detail == "HTTP 404")

private val NO_WALLET_CODES = setOf("NOT_FOUND", "WALLET_NOT_FOUND", "CREDITS_DISABLED")
