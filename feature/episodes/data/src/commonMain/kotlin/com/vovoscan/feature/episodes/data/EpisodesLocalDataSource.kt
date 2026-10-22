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

/**
 * Local source: the last known episode per chapter and language, and its last watch. Rows are keyed by [storeKey]:
 * the chapter id for the original (as before languages existed), `<chapterId>~<lang>` for an episode in a language.
 */
interface EpisodesLocalDataSource {
    fun observe(
        chapterId: String,
        lang: String = "",
    ): Flow<Episode?>

    /** Keeps [episode] (filed under its own chapter and language) unless a newer snapshot is already stored. */
    suspend fun save(episode: EpisodeDto)

    suspend fun delete(
        chapterId: String,
        lang: String = "",
    )

    /** Puts [export] on the stored episode (if any), keeping its `updatedAt`: an export does not move it. */
    suspend fun saveExport(
        chapterId: String,
        lang: String = "",
        export: ExportDto,
    )

    suspend fun watch(
        chapterId: String,
        lang: String = "",
    ): Watch?

    suspend fun saveWatch(
        watch: WatchDto,
        lang: String = "",
    )

    suspend fun clear()
}

/** Chapter ids are UUIDs: `~` never appears in one, so a language never collides with another chapter. */
internal fun storeKey(
    chapterId: String,
    lang: String,
) = if (lang.isEmpty()) chapterId else "$chapterId~$lang"

class SqlEpisodesLocalDataSource(
    private val database: EpisodesDatabase,
    private val io: CoroutineDispatcher,
) : EpisodesLocalDataSource {
    private val queries get() = database.episodesQueries

    override fun observe(
        chapterId: String,
        lang: String,
    ): Flow<Episode?> =
        queries
            .episode(storeKey(chapterId, lang))
            .asFlow()
            .mapToOneOrNull(io)
            .map { body -> body?.let(::decodeEpisode)?.toDomain() }

    override suspend fun save(episode: EpisodeDto) =
        withContext(io) {
            val updated = parseIsoMillis(episode.updatedAt) ?: 0L
            val key = storeKey(episode.chapterId, episode.language)
            queries.transaction {
                val stored = queries.episodeUpdatedAt(key).executeAsOneOrNull()
                // SSE, polling and a prepare answer can cross: an older snapshot never replaces a newer one.
                if (stored == null || updated >= stored) {
                    queries.putEpisode(key, episode.status, updated, ApiJson.encodeToString(EpisodeDto.serializer(), episode))
                }
            }
        }

    override suspend fun saveExport(
        chapterId: String,
        lang: String,
        export: ExportDto,
    ) {
        val key = storeKey(chapterId, lang)
        withContext(io) {
            queries.transaction {
                val body = queries.episode(key).executeAsOneOrNull() ?: return@transaction
                val stored = decodeEpisode(body) ?: return@transaction
                val updated = parseIsoMillis(stored.updatedAt) ?: 0L
                val next = stored.copy(export = export)
                queries.putEpisode(key, next.status, updated, ApiJson.encodeToString(EpisodeDto.serializer(), next))
            }
        }
    }

    override suspend fun delete(
        chapterId: String,
        lang: String,
    ) {
        withContext(io) { queries.deleteEpisode(storeKey(chapterId, lang)) }
    }

    override suspend fun watch(
        chapterId: String,
        lang: String,
    ): Watch? =
        withContext(io) {
            queries.watch(storeKey(chapterId, lang)).executeAsOneOrNull()?.let { row ->
                runCatching { ApiJson.decodeFromString(WatchDto.serializer(), row.body) }.getOrNull()?.toDomain()
            }
        }

    override suspend fun saveWatch(
        watch: WatchDto,
        lang: String,
    ) {
        withContext(io) {
            queries.putWatch(
                storeKey(watch.chapterId, lang),
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
