package com.vovoscan.feature.account.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.feature.account.data.db.AccountDatabase
import com.vovoscan.feature.account.data.db.Provider_key
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.ProviderKey
import com.vovoscan.feature.account.domain.ProviderKeys
import com.vovoscan.feature.account.domain.ReadingModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Local source: the key list (never a secret: the table has no column for one) and the reading model. */
interface AccountLocalDataSource {
    fun observeKeys(): Flow<ProviderKeys?>

    suspend fun saveKeys(keys: ProviderKeysDto)

    /** The server revoked the previous key of that provider when it stored this one. */
    suspend fun saveNewKey(key: ProviderKeyDto)

    suspend fun deleteKey(id: String)

    fun observeReadingModel(): Flow<ReadingModel?>

    suspend fun saveReadingModel(model: ReadingModel)

    fun observeTargetLanguage(): Flow<String?>

    suspend fun saveTargetLanguage(code: String)

    suspend fun clear()
}

class SqlAccountLocalDataSource(
    private val database: AccountDatabase,
    private val io: CoroutineDispatcher,
) : AccountLocalDataSource {
    private val queries get() = database.accountQueries

    override fun observeKeys(): Flow<ProviderKeys?> =
        combine(
            queries.keys().asFlow().mapToList(io),
            queries.value(BYOK_ENABLED).asFlow().mapToOneOrNull(io),
        ) { rows, enabled -> enabled?.let { ProviderKeys(it == "1", rows.mapNotNull { row -> row.toDomain() }) } }

    override suspend fun saveKeys(keys: ProviderKeysDto) =
        withContext(io) {
            queries.transaction {
                queries.clearKeys()
                keys.keys.forEach { put(it) }
                queries.putValue(BYOK_ENABLED, if (keys.enabled) "1" else "0")
            }
        }

    override suspend fun saveNewKey(key: ProviderKeyDto) =
        withContext(io) {
            queries.transaction {
                queries.deleteKeysOf(key.provider)
                put(key)
                queries.putValue(BYOK_ENABLED, "1")
            }
        }

    override suspend fun deleteKey(id: String) {
        withContext(io) { queries.deleteKey(id) }
    }

    override fun observeReadingModel(): Flow<ReadingModel?> =
        queries
            .value(READING_MODEL)
            .asFlow()
            .mapToOneOrNull(io)
            .map { it?.let(ReadingModel::from) }

    override suspend fun saveReadingModel(model: ReadingModel) {
        withContext(io) { queries.putValue(READING_MODEL, model.wire) }
    }

    override fun observeTargetLanguage(): Flow<String?> = queries.value(TARGET_LANGUAGE).asFlow().mapToOneOrNull(io)

    override suspend fun saveTargetLanguage(code: String) {
        withContext(io) { queries.putValue(TARGET_LANGUAGE, code) }
    }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearKeys()
                queries.clearValues()
            }
        }

    private fun put(key: ProviderKeyDto) =
        queries.putKey(
            key.id,
            key.provider,
            key.hint,
            key.label,
            parseIsoMillis(key.createdAt) ?: 0L,
            key.lastUsedAt?.let(::parseIsoMillis),
        )

    private fun Provider_key.toDomain(): ProviderKey? =
        Provider.from(provider)?.let { ProviderKey(id, it, hint, label, created_at, last_used_at) }

    private companion object {
        const val BYOK_ENABLED = "byok_enabled"
        const val READING_MODEL = "reading_model"
        const val TARGET_LANGUAGE = "target_language"
    }
}
