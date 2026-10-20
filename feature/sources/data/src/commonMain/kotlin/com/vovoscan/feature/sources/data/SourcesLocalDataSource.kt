package com.vovoscan.feature.sources.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.vovoscan.core.network.ApiJson
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.SourceManifest
import com.vovoscan.feature.sources.data.db.SourcesDatabase
import com.vovoscan.feature.sources.domain.SourceEngine
import com.vovoscan.feature.sources.domain.SourceItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Source locale : mes sources, leurs manifestes et le dernier choix pas encore envoyé. Fournit aussi les manifestes. */
interface SourcesLocalDataSource : ManifestProvider {
    fun observe(): Flow<List<SourceItem>>

    /** Remplace la liste par celle du serveur. */
    suspend fun replace(sources: List<MySourceDto>)

    /** Applique un choix sur l'appareil et le garde en attente d'envoi. */
    suspend fun applyPending(choices: List<SourceChoiceDto>)

    suspend fun pending(): List<SourceChoiceDto>?

    suspend fun clearPending()

    suspend fun clear()
}

private const val SEPARATOR = ","

class SqlSourcesLocalDataSource(
    private val database: SourcesDatabase,
    private val io: CoroutineDispatcher,
) : SourcesLocalDataSource {
    private val queries get() = database.sourcesQueries
    private val choices = ListSerializer(SourceChoiceDto.serializer())

    override fun observe(): Flow<List<SourceItem>> =
        queries.all().asFlow().mapToList(io).map { rows ->
            val pending = withContext(io) { queries.pending().executeAsOneOrNull() } != null
            rows.map {
                SourceItem(
                    key = it.key,
                    name = it.name,
                    engine = SourceEngine.fromApi(it.engine),
                    version = it.version,
                    kinds = it.kinds.split(SEPARATOR).filter(String::isNotEmpty),
                    langs = it.langs.split(SEPARATOR).filter(String::isNotEmpty),
                    enabled = it.enabled == 1L,
                    position = it.position.toInt(),
                    pendingSync = pending,
                )
            }
        }

    override suspend fun replace(sources: List<MySourceDto>) =
        withContext(io) {
            queries.transaction {
                queries.clearSources()
                sources.forEach {
                    queries.put(
                        it.key,
                        it.name,
                        it.engine,
                        it.version,
                        it.contentKinds.joinToString(SEPARATOR),
                        it.langs.joinToString(SEPARATOR),
                        if (it.enabled) 1 else 0,
                        it.position.toLong(),
                        it.manifest?.toString(),
                        it.settings?.toString(),
                    )
                }
            }
        }

    override suspend fun applyPending(choices: List<SourceChoiceDto>) =
        withContext(io) {
            queries.transaction {
                choices.forEachIndexed {
                    position,
                    choice,
                    ->
                    queries.setChoice(if (choice.enabled) 1 else 0, position.toLong(), choice.key)
                }
                queries.putPending(ApiJson.encodeToString(this@SqlSourcesLocalDataSource.choices, choices))
            }
        }

    override suspend fun pending(): List<SourceChoiceDto>? =
        withContext(io) {
            queries.pending().executeAsOneOrNull()?.let { runCatching { ApiJson.decodeFromString(choices, it) }.getOrNull() }
        }

    override suspend fun clearPending() = withContext(io) { queries.clearPending().let { } }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearSources()
                queries.clearPending()
            }
        }

    override suspend fun manifest(key: String): SourceManifest? =
        withContext(io) {
            val row = queries.manifest(key).executeAsOneOrNull() ?: return@withContext null
            val manifest =
                row.manifest?.let { runCatching { ApiJson.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: return@withContext null
            val settings =
                row.settings
                    ?.let { runCatching { ApiJson.parseToJsonElement(it) as JsonObject }.getOrNull() }
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                    ?.toMap()
                    .orEmpty()
            SourceManifest(key, manifest, settings)
        }
}
