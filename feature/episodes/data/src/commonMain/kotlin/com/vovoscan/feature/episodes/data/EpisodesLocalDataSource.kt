package com.vovoscan.feature.episodes.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.core.network.ApiJson
import com.vovoscan.feature.episodes.data.db.EpisodesDatabase
import com.vovoscan.feature.episodes.domain.Episode
import com.vovoscan.feature.episodes.domain.Watch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Local source: the last known episode per chapter and its last watch. */
interface EpisodesLocalDataSource {
    fun observe(chapterId: String): Flow<Episode?>

    /** Keeps [episode] unless a newer snapshot (later `updatedAt`) is already stored. */
    suspend fun save(episode: EpisodeDto)

    suspend fun delete(chapterId: String)

    /** Puts [export] on the stored episode (if any), keeping its `updatedAt`: an export does not move it. */
    suspend fun saveExport(
        chapterId: String,
        export: ExportDto,
    )

    suspend fun watch(chapterId: String): Watch?

    suspend fun saveWatch(watch: WatchDto)

    suspend fun clear()
}

class SqlEpisodesLocalDataSource(
    private val database: EpisodesDatabase,
    private val io: CoroutineDispatcher,
) : EpisodesLocalDataSource {
    private val queries get() = database.episodesQueries

    override fun observe(chapterId: String): Flow<Episode?> =
        queries
            .episode(chapterId)
            .asFlow()
            .mapToOneOrNull(io)
            .map { body -> body?.let(::decodeEpisode)?.toDomain() }

    override suspend fun save(episode: EpisodeDto) =
        withContext(io) {
            val updated = parseIsoMillis(episode.updatedAt) ?: 0L
            queries.transaction {
                val stored = queries.episodeUpdatedAt(episode.chapterId).executeAsOneOrNull()
                // SSE, polling and a prepare answer can cross: an older snapshot never replaces a newer one.
                if (stored == null || updated >= stored) {
                    queries.putEpisode(episode.chapterId, episode.status, updated, ApiJson.encodeToString(EpisodeDto.serializer(), episode))
                }
            }
        }

    override suspend fun saveExport(
        chapterId: String,
        export: ExportDto,
    ) {
        withContext(io) {
            queries.transaction {
                val body = queries.episode(chapterId).executeAsOneOrNull() ?: return@transaction
                val stored = decodeEpisode(body) ?: return@transaction
                val updated = parseIsoMillis(stored.updatedAt) ?: 0L
                val next = stored.copy(export = export)
                queries.putEpisode(chapterId, next.status, updated, ApiJson.encodeToString(EpisodeDto.serializer(), next))
            }
        }
    }

    override suspend fun delete(chapterId: String) {
        withContext(io) { queries.deleteEpisode(chapterId) }
    }

    override suspend fun watch(chapterId: String): Watch? =
        withContext(io) {
            queries.watch(chapterId).executeAsOneOrNull()?.let { row ->
                runCatching { ApiJson.decodeFromString(WatchDto.serializer(), row.body) }.getOrNull()?.toDomain()
            }
        }

    override suspend fun saveWatch(watch: WatchDto) {
        withContext(io) {
            queries.putWatch(
                watch.chapterId,
                watch.renderKey,
                parseIsoMillis(watch.expiresAt) ?: 0L,
                ApiJson.encodeToString(WatchDto.serializer(), watch),
            )
        }
    }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearEpisodes()
                queries.clearWatches()
            }
        }

    private fun decodeEpisode(body: String): EpisodeDto? =
        runCatching { ApiJson.decodeFromString(EpisodeDto.serializer(), body) }.getOrNull()
}
