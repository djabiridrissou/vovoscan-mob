package com.vovoscan.feature.sources.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.core.network.ApiJson
import com.vovoscan.core.sources.ManifestInfo
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.SourceManifest
import com.vovoscan.feature.sources.data.db.Plugin_extra
import com.vovoscan.feature.sources.data.db.Source
import com.vovoscan.feature.sources.data.db.SourcesDatabase
import com.vovoscan.feature.sources.domain.SourceEngine
import com.vovoscan.feature.sources.domain.SourceItem
import com.vovoscan.feature.sources.domain.SourceOrigin
import com.vovoscan.feature.sources.domain.SourceRepositoryItem
import com.vovoscan.feature.sources.domain.SourceUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A row of the user's source list as stored on the device. */
data class StoredSource(
    val key: String,
    val name: String,
    val engine: String,
    val version: String,
    val kinds: List<String>,
    val langs: List<String>,
    val enabled: Boolean,
    val position: Int,
    val manifest: JsonObject?,
    val settings: Map<String, String>,
    val origin: SourceOrigin,
    val originUrl: String?,
    val repositoryId: String?,
    val summary: String,
)

data class StoredRepository(
    val id: String,
    val url: String,
    val name: String,
    val sourceCount: Int?,
)

/** Device-only data of a third-party source: icon and the update found by the last check. */
data class StoredExtra(
    val key: String,
    val iconUrl: String?,
    val updateVersion: String?,
    val updateChanges: String?,
    val updateManifest: String?,
)

/** A plugin or repository write waiting to be sent. */
data class PluginOp(
    val target: String,
    val id: String,
    val kind: String,
    /** Body of a `put`, without `updatedAt`. */
    val payload: String?,
    val updatedAt: Long,
) {
    val isDelete: Boolean get() = kind == DELETE

    companion object {
        const val PLUGIN = "plugin"
        const val REPOSITORY = "repository"
        const val PUT = "put"
        const val DELETE = "delete"
    }
}

/**
 * Local source: my sources, their manifests, third-party sources and repositories, and the writes not sent yet.
 * Also provides the manifests to the catalog and the reader, user plugins included.
 */
interface SourcesLocalDataSource : ManifestProvider {
    fun observe(): Flow<List<SourceItem>>

    fun observeRepositories(): Flow<List<SourceRepositoryItem>>

    /** Replaces the list with the server's. */
    suspend fun replace(sources: List<MySourceDto>)

    /** Applies a choice on the device and keeps it pending until sent. */
    suspend fun applyPending(choices: List<SourceChoiceDto>)

    suspend fun pending(): List<SourceChoiceDto>?

    suspend fun clearPending()

    /** Drops a source from the pending choice (it was removed in the meantime). */
    suspend fun dropFromPending(key: String)

    suspend fun keys(): Set<String>

    suspend fun source(key: String): StoredSource?

    suspend fun userPlugins(): List<StoredSource>

    /** Writes a third-party source; a new one goes at the end of the list. */
    suspend fun putSource(source: StoredSource)

    suspend fun nextPosition(): Int

    suspend fun setSettings(
        key: String,
        settings: Map<String, String>,
    )

    suspend fun deleteSource(key: String)

    suspend fun extra(key: String): StoredExtra?

    suspend fun setIcon(
        key: String,
        iconUrl: String?,
    )

    suspend fun setUpdate(
        key: String,
        version: String?,
        changes: String?,
        manifest: String?,
    )

    suspend fun repositories(): List<StoredRepository>

    suspend fun repository(id: String): StoredRepository?

    suspend fun putRepository(
        id: String,
        url: String,
        name: String,
    )

    suspend fun setRepositoryCount(
        id: String,
        count: Int,
    )

    suspend fun deleteRepository(id: String)

    /** Replaces the repositories with the server's (local counts are kept). */
    suspend fun replaceRepositories(repositories: List<PluginRepositoryDto>)

    suspend fun enqueue(op: PluginOp)

    suspend fun outbox(): List<PluginOp>

    suspend fun dequeue(op: PluginOp)

    suspend fun lastUpdateCheck(): Long?

    suspend fun setLastUpdateCheck(at: Long)

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
        combine(
            queries.all().asFlow().mapToList(io),
            queries.extras().asFlow().mapToList(io),
            queries.repositories().asFlow().mapToList(io),
            queries.outbox().asFlow().mapToList(io),
            queries.pending().asFlow().mapToOneOrNull(io),
        ) { rows, extras, repositories, outbox, pendingChoices ->
            val extraByKey = extras.associateBy { it.key }
            val repositoryNames = repositories.associate { it.id to it.name }
            val queued = outbox.filter { it.target == PluginOp.PLUGIN }.map { it.id }.toSet()
            rows.map { item(it.stored(), extraByKey[it.key], repositoryNames, pendingChoices != null || it.key in queued) }
        }

    override fun observeRepositories(): Flow<List<SourceRepositoryItem>> =
        combine(queries.repositories().asFlow().mapToList(io), queries.outbox().asFlow().mapToList(io)) { rows, outbox ->
            val queued = outbox.filter { it.target == PluginOp.REPOSITORY }.map { it.id }.toSet()
            rows.map { SourceRepositoryItem(it.id, it.name, it.url, it.source_count?.toInt(), it.id in queued) }
        }

    private fun item(
        source: StoredSource,
        extra: Plugin_extra?,
        repositoryNames: Map<String, String>,
        pending: Boolean,
    ): SourceItem {
        val info = source.manifest?.let(::ManifestInfo)
        val settings = effectiveSettings(source.manifest, source.settings)
        val update =
            extra?.update_version?.let { version ->
                val newHosts =
                    extra.update_manifest
                        ?.let(::parseObject)
                        ?.let { ManifestInfo(it).hosts }
                        .orEmpty()
                SourceUpdate(version, extra.update_changes, newHosts - info?.hosts.orEmpty().toSet())
            }
        return SourceItem(
            key = source.key,
            name = source.name,
            engine = SourceEngine.fromApi(source.engine),
            version = source.version,
            kinds = source.kinds,
            langs = source.langs,
            enabled = source.enabled,
            position = source.position,
            pendingSync = pending,
            origin = source.origin,
            summary = source.summary.ifBlank { info?.summary.orEmpty() },
            iconUrl = extra?.icon_url,
            rightsBasis = info?.rightsBasis,
            rightsNotes = info?.rightsNotes,
            hosts = source.manifest?.let { SourceManifest(source.key, it, settings).allowedHosts.sorted() }.orEmpty(),
            settingsSpec = info?.settings?.map { it.toField() }.orEmpty(),
            settings = settings,
            originUrl = source.originUrl,
            repositoryId = source.repositoryId,
            repositoryName = source.repositoryId?.let { repositoryNames[it] },
            update = update,
        )
    }

    override suspend fun replace(sources: List<MySourceDto>) =
        withContext(io) {
            queries.transaction {
                queries.clearSources()
                sources.forEach { put(it.toStored()) }
                val keys = sources.map { it.key }.toSet()
                queries
                    .extras()
                    .executeAsList()
                    .filter { it.key !in keys }
                    .forEach { queries.deleteExtra(it.key) }
            }
        }

    private fun put(source: StoredSource) {
        queries.put(
            source.key,
            source.name,
            source.engine,
            source.version,
            source.kinds.joinToString(SEPARATOR),
            source.langs.joinToString(SEPARATOR),
            if (source.enabled) 1 else 0,
            source.position.toLong(),
            source.manifest?.toString(),
            if (source.manifest == null && source.settings.isEmpty()) null else encodeSettings(source.settings),
            source.origin.apiValue,
            source.originUrl,
            source.repositoryId,
            source.summary,
        )
    }

    override suspend fun applyPending(choices: List<SourceChoiceDto>) =
        withContext(io) {
            queries.transaction {
                choices.forEachIndexed { position, choice ->
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

    override suspend fun dropFromPending(key: String) {
        val current = pending() ?: return
        withContext(io) { queries.putPending(ApiJson.encodeToString(choices, current.filter { it.key != key })) }
    }

    override suspend fun keys(): Set<String> = withContext(io) { queries.keys().executeAsList().toSet() }

    override suspend fun source(key: String): StoredSource? = withContext(io) { queries.byKey(key).executeAsOneOrNull()?.stored() }

    override suspend fun userPlugins(): List<StoredSource> = withContext(io) { queries.userPlugins().executeAsList().map { it.stored() } }

    override suspend fun putSource(source: StoredSource) =
        withContext(io) {
            queries.transaction {
                put(source)
                queries.insertExtra(source.key)
            }
        }

    override suspend fun nextPosition(): Int =
        withContext(io) {
            queries
                .maxPosition()
                .executeAsOne()
                .max_position
                ?.toInt()
                ?.plus(1) ?: 0
        }

    override suspend fun setSettings(
        key: String,
        settings: Map<String, String>,
    ) = withContext(io) { queries.setSettings(encodeSettings(settings), key).let { } }

    override suspend fun deleteSource(key: String) =
        withContext(io) {
            queries.transaction {
                queries.deleteSource(key)
                queries.deleteExtra(key)
            }
        }

    override suspend fun extra(key: String): StoredExtra? =
        withContext(io) {
            queries.extra(key).executeAsOneOrNull()?.let {
                StoredExtra(it.key, it.icon_url, it.update_version, it.update_changes, it.update_manifest)
            }
        }

    override suspend fun setIcon(
        key: String,
        iconUrl: String?,
    ) = withContext(io) {
        queries.transaction {
            queries.insertExtra(key)
            queries.setIcon(iconUrl, key)
        }
    }

    override suspend fun setUpdate(
        key: String,
        version: String?,
        changes: String?,
        manifest: String?,
    ) = withContext(io) {
        queries.transaction {
            queries.insertExtra(key)
            queries.setUpdate(version, changes, manifest, key)
        }
    }

    override suspend fun repositories(): List<StoredRepository> =
        withContext(
            io,
        ) { queries.repositories().executeAsList().map { StoredRepository(it.id, it.url, it.name, it.source_count?.toInt()) } }

    override suspend fun repository(id: String): StoredRepository? =
        withContext(
            io,
        ) { queries.repository(id).executeAsOneOrNull()?.let { StoredRepository(it.id, it.url, it.name, it.source_count?.toInt()) } }

    override suspend fun putRepository(
        id: String,
        url: String,
        name: String,
    ) = withContext(io) {
        queries.transaction {
            queries.insertRepository(id, url, name)
            queries.updateRepository(url, name, id)
        }
    }

    override suspend fun setRepositoryCount(
        id: String,
        count: Int,
    ) = withContext(io) { queries.setRepositoryCount(count.toLong(), id).let { } }

    override suspend fun deleteRepository(id: String) = withContext(io) { queries.deleteRepository(id).let { } }

    override suspend fun replaceRepositories(repositories: List<PluginRepositoryDto>) =
        withContext(io) {
            queries.transaction {
                val alive = repositories.filter { !it.deleted && it.url.isNotBlank() }
                val ids = alive.map { it.id }.toSet()
                queries
                    .repositories()
                    .executeAsList()
                    .filter { it.id !in ids }
                    .forEach { queries.deleteRepository(it.id) }
                alive.forEach {
                    queries.insertRepository(it.id, it.url, it.name.ifBlank { it.id })
                    queries.updateRepository(it.url, it.name.ifBlank { it.id }, it.id)
                }
            }
        }

    override suspend fun enqueue(op: PluginOp) =
        withContext(io) {
            queries.enqueue(op.target, op.id, op.kind, op.payload, op.updatedAt).let { }
        }

    override suspend fun outbox(): List<PluginOp> =
        withContext(io) { queries.outbox().executeAsList().map { PluginOp(it.target, it.id, it.kind, it.payload, it.updated_at) } }

    override suspend fun dequeue(op: PluginOp) = withContext(io) { queries.dequeue(op.target, op.id, op.updatedAt).let { } }

    override suspend fun lastUpdateCheck(): Long? = withContext(io) { queries.lastUpdateCheck().executeAsOneOrNull()?.last_update_check }

    override suspend fun setLastUpdateCheck(at: Long) = withContext(io) { queries.putLastUpdateCheck(at).let { } }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearSources()
                queries.clearPending()
                queries.clearRepositories()
                queries.clearExtras()
                queries.clearOutbox()
                queries.clearState()
            }
        }

    override suspend fun manifest(key: String): SourceManifest? =
        withContext(io) {
            val row = queries.manifest(key).executeAsOneOrNull() ?: return@withContext null
            val manifest = row.manifest?.let(::parseObject) ?: return@withContext null
            SourceManifest(key, manifest, effectiveSettings(manifest, decodeSettings(row.settings)))
        }
}

/** Setting values with the manifest's defaults for the ones not set. */
internal fun effectiveSettings(
    manifest: JsonObject?,
    values: Map<String, String>,
): Map<String, String> {
    val defaults =
        manifest
            ?.let(::ManifestInfo)
            ?.settings
            ?.mapNotNull { s -> s.default?.let { s.name to it } }
            ?.toMap()
            .orEmpty()
    return defaults + values.filterValues { it.isNotBlank() }
}

internal fun parseObject(text: String): JsonObject? = runCatching { ApiJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

internal fun decodeSettings(text: String?): Map<String, String> =
    text
        ?.let(::parseObject)
        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
        ?.toMap()
        .orEmpty()

internal fun encodeSettings(settings: Map<String, String>): String = JsonObject(settings.mapValues { JsonPrimitive(it.value) }).toString()

private fun Source.stored() =
    StoredSource(
        key = key,
        name = name,
        engine = engine,
        version = version,
        kinds = kinds.split(SEPARATOR).filter(String::isNotEmpty),
        langs = langs.split(SEPARATOR).filter(String::isNotEmpty),
        enabled = enabled == 1L,
        position = position.toInt(),
        manifest = manifest?.let(::parseObject),
        settings = decodeSettings(settings),
        origin = SourceOrigin.fromApi(origin),
        originUrl = origin_url,
        repositoryId = repository_id,
        summary = summary,
    )

/** "official" or "user" from the API; for a user plugin, the origin follows from `repositoryId` / `originUrl`. */
internal fun MySourceDto.toStored(): StoredSource {
    val origin =
        when (origin) {
            "official" -> SourceOrigin.Official
            "repository", "link", "file" -> SourceOrigin.fromApi(origin)
            else ->
                when {
                    repositoryId != null -> SourceOrigin.Repository
                    originUrl != null -> SourceOrigin.Link
                    else -> SourceOrigin.File
                }
        }
    return StoredSource(
        key = key,
        name = name,
        engine = engine,
        version = version,
        kinds = contentKinds,
        langs = langs,
        enabled = enabled,
        position = position,
        manifest = manifest,
        settings = settings?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
        origin = origin,
        originUrl = originUrl,
        repositoryId = repositoryId,
        summary = description ?: manifest?.let { ManifestInfo(it).summary }.orEmpty(),
    )
}
