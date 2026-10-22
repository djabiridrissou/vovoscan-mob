package com.vovoscan.feature.account.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.ProviderKey
import com.vovoscan.feature.account.domain.ProviderKeys
import com.vovoscan.feature.account.domain.ProviderKeysRepository
import com.vovoscan.feature.account.domain.ReadingModel
import com.vovoscan.feature.account.domain.ReadingSettingsRepository
import com.vovoscan.feature.account.domain.UnitType
import com.vovoscan.feature.account.domain.Usage
import com.vovoscan.feature.account.domain.UsageEntry
import com.vovoscan.feature.account.domain.UsageRepository
import com.vovoscan.feature.account.domain.UsageTotal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Keys, reading model and costs. Reads are offline first (the key list and the model are kept on the device);
 * writes need the network and are never queued: a secret must not wait on the device for a connection.
 */
class AccountRepositoryImpl(
    private val remote: AccountRemoteDataSource,
    private val local: AccountLocalDataSource,
) : ProviderKeysRepository,
    ReadingSettingsRepository,
    UsageRepository {
    override fun observeKeys(): Flow<ProviderKeys?> = local.observeKeys()

    override suspend fun refreshKeys(): AppResult<Unit> = remote.keys().also { if (it is AppResult.Ok) local.saveKeys(it.value) }.map { }

    override suspend fun add(
        provider: Provider,
        secret: String,
        label: String?,
    ): AppResult<ProviderKey> {
        val result = remote.addKey(provider.wire, secret.trim(), label?.trim()?.ifEmpty { null })
        if (result !is AppResult.Ok) return result as AppResult.Err
        local.saveNewKey(result.value)
        return AppResult.Ok(
            ProviderKey(
                result.value.id,
                provider,
                result.value.hint,
                result.value.label,
                parseIsoMillis(result.value.createdAt) ?: 0L,
                result.value.lastUsedAt?.let(::parseIsoMillis),
            ),
        )
    }

    override suspend fun remove(id: String): AppResult<Unit> {
        val result = remote.removeKey(id)
        // Idempotent: a key already revoked (here or on another device) is simply gone.
        val gone = result is AppResult.Ok || (result as? AppResult.Err)?.error.let { it is AppError.Rejected && it.code == KEY_NOT_FOUND }
        if (!gone) return result
        local.deleteKey(id)
        return AppResult.Ok(Unit)
    }

    override suspend fun clearLocal() = local.clear()

    // Reading model.

    override fun observeReadingModel(): Flow<ReadingModel?> = local.observeReadingModel()

    override suspend fun refreshReadingModel(): AppResult<Unit> {
        // A language chosen while the server could not take it goes first, so the server's older answer never wins.
        val pending = local.targetLanguagePending()
        if (pending) local.observeTargetLanguage().first()?.let { pushTargetLanguage(it) }
        val stillPending = pending && local.targetLanguagePending()
        return remote
            .readingSettings()
            .also { result ->
                if (result is AppResult.Ok) {
                    result.value.ocrModel
                        ?.let(ReadingModel::from)
                        ?.let { local.saveReadingModel(it) }
                    if (!stillPending) result.value.targetLanguage?.let { local.saveTargetLanguage(it) }
                }
            }.map { }
    }

    override suspend fun set(model: ReadingModel): AppResult<ReadingModel> {
        val result = remote.setReadingSettings(ocrModel = model.wire)
        if (result !is AppResult.Ok) return result as AppResult.Err
        val saved = result.value.ocrModel?.let(ReadingModel::from) ?: model
        local.saveReadingModel(saved)
        return AppResult.Ok(saved)
    }

    override fun observeTargetLanguage(): Flow<String?> = local.observeTargetLanguage()

    /**
     * The choice is the device's at once. Offline, or refused by a server that doesn't know the field yet, it stays on
     * the device and is sent again at the next refresh: choosing a language never blocks (the first-launch tour).
     */
    override suspend fun setTargetLanguage(code: String): AppResult<String> {
        local.saveTargetLanguage(code)
        return when (val result = pushTargetLanguage(code)) {
            is AppResult.Ok -> result
            is AppResult.Err ->
                when (result.error) {
                    AppError.Network, is AppError.Rejected, is AppError.Unknown -> AppResult.Ok(code)
                    else -> result
                }
        }
    }

    override suspend fun coached(): AppResult<Set<String>> = remote.readingSettings().map { it.coached.orEmpty().toSet() }

    override suspend fun markCoached(kind: String): AppResult<Unit> = remote.markCoached(kind).map { }

    // The current model goes with it: a server without `targetLanguage` (it requires `ocrModel` and ignores the rest)
    // takes the request, a newer one keeps both.
    private suspend fun pushTargetLanguage(code: String): AppResult<String> {
        val model = local.observeReadingModel().first() ?: ReadingModel.Haiku
        val result = remote.setReadingSettings(ocrModel = model.wire, targetLanguage = code)
        if (result !is AppResult.Ok) {
            local.setTargetLanguagePending(true)
            return result as AppResult.Err
        }
        val saved = result.value.targetLanguage ?: code
        local.saveTargetLanguage(saved)
        local.setTargetLanguagePending(false)
        return AppResult.Ok(saved)
    }

    // Costs.

    override suspend fun usage(
        fromMillis: Long?,
        toMillis: Long?,
    ): AppResult<Usage> = remote.usage(fromMillis, toMillis).map { it.toDomain() }
}

private const val KEY_NOT_FOUND = "PROVIDER_KEY_NOT_FOUND"

internal fun UsageDto.toDomain() =
    Usage(
        totals =
            totals.mapNotNull { total ->
                Provider.from(total.provider)?.let { provider ->
                    UsageTotal(provider, total.costUsd, total.units.mapNotNull { (k, v) -> UnitType.from(k)?.let { it to v } }.toMap())
                }
            },
        entries =
            entries.mapNotNull { e ->
                val provider = Provider.from(e.provider) ?: return@mapNotNull null
                val unit = UnitType.from(e.unitType) ?: return@mapNotNull null
                UsageEntry(e.id, provider, e.model, unit, e.units, e.costUsd, e.chapterId, parseIsoMillis(e.createdAt) ?: 0L)
            },
    )
