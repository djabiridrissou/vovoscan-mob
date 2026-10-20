package com.vovoscan.feature.library.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.vovoscan.feature.library.data.db.LibraryDatabase
import com.vovoscan.feature.library.data.db.Library_entry
import com.vovoscan.feature.library.data.db.Outbox
import com.vovoscan.feature.library.domain.LibraryEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Une action faite sur l'appareil, en attente d'envoi. */
data class PendingChange(
    val sourceKey: String,
    val titleRef: String,
    val delete: Boolean,
    val titleName: String,
    val coverUrl: String?,
    val lastChapterRef: String?,
    val lastPage: Int,
    val updatedAt: Long,
)

/** Source locale : entrées de la bibliothèque, file d'envoi et curseur de synchronisation. */
interface LibraryLocalDataSource {
    fun observe(): Flow<List<LibraryEntry>>

    fun isFollowed(
        sourceKey: String,
        titleRef: String,
    ): Flow<Boolean>

    suspend fun entry(
        sourceKey: String,
        titleRef: String,
    ): LibraryEntry?

    /** Applique une action locale et la met en file, dans la même transaction. */
    suspend fun applyLocal(
        entry: LibraryEntry,
        deleted: Boolean,
    )

    suspend fun pending(): List<PendingChange>

    /** Retire l'opération envoyée, sauf si une plus récente l'a remplacée entre-temps. */
    suspend fun dequeue(change: PendingChange)

    /** État rendu par le serveur ; ignoré si une action locale plus récente attend encore. */
    suspend fun applyServer(
        dto: LibraryEntryDto,
        updatedAt: Long,
    )

    suspend fun lastPulledAt(): String?

    suspend fun setLastPulledAt(value: String)

    suspend fun clear()
}

class SqlLibraryLocalDataSource(
    private val database: LibraryDatabase,
    private val io: CoroutineDispatcher,
) : LibraryLocalDataSource {
    private val queries get() = database.libraryQueries

    override fun observe(): Flow<List<LibraryEntry>> =
        queries
            .entries()
            .asFlow()
            .mapToList(io)
            .map { rows -> rows.map { it.toDomain() } }

    override fun isFollowed(
        sourceKey: String,
        titleRef: String,
    ): Flow<Boolean> =
        queries
            .isFollowed(sourceKey, titleRef)
            .asFlow()
            .mapToOne(io)
            .map { it > 0 }

    override suspend fun entry(
        sourceKey: String,
        titleRef: String,
    ): LibraryEntry? =
        withContext(io) {
            queries
                .entry(sourceKey, titleRef)
                .executeAsOneOrNull()
                ?.takeIf { it.deleted == 0L }
                ?.toDomain()
        }

    override suspend fun applyLocal(
        entry: LibraryEntry,
        deleted: Boolean,
    ) = withContext(io) {
        queries.transaction {
            queries.putEntry(
                entry.sourceKey,
                entry.titleRef,
                entry.titleName,
                entry.coverUrl,
                entry.lastChapterRef,
                entry.lastPage.toLong(),
                entry.updatedAt,
                if (deleted) 1 else 0,
                1,
            )
            queries.enqueue(
                entry.sourceKey,
                entry.titleRef,
                if (deleted) "delete" else "save",
                entry.titleName,
                entry.coverUrl,
                entry.lastChapterRef,
                entry.lastPage.toLong(),
                entry.updatedAt,
            )
        }
    }

    override suspend fun pending(): List<PendingChange> = withContext(io) { queries.pending().executeAsList().map { it.toChange() } }

    override suspend fun dequeue(change: PendingChange) =
        withContext(io) {
            queries.dequeue(change.sourceKey, change.titleRef, change.updatedAt).let { }
        }

    override suspend fun applyServer(
        dto: LibraryEntryDto,
        updatedAt: Long,
    ) = withContext(io) {
        queries.transaction {
            val local = queries.entry(dto.sourceKey, dto.titleRef).executeAsOneOrNull()
            val queued = queries.isQueued(dto.sourceKey, dto.titleRef).executeAsOne() > 0
            if (queued && local != null && local.updated_at > updatedAt) return@transaction
            queries.putEntry(
                dto.sourceKey,
                dto.titleRef,
                dto.titleName,
                dto.coverUrl,
                dto.lastChapterRef,
                dto.lastPage.toLong(),
                updatedAt,
                if (dto.deleted) 1 else 0,
                if (queued) 1 else 0,
            )
        }
    }

    override suspend fun lastPulledAt(): String? = withContext(io) { queries.lastPulledAt().executeAsOneOrNull()?.last_pulled_at }

    override suspend fun setLastPulledAt(value: String) = withContext(io) { queries.putLastPulledAt(value).let { } }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearEntries()
                queries.clearOutbox()
                queries.clearSyncState()
            }
        }
}

private fun Library_entry.toDomain() =
    LibraryEntry(source_key, title_ref, title_name, cover_url, last_chapter_ref, last_page.toInt(), updated_at, pending == 1L)

private fun Outbox.toChange() =
    PendingChange(source_key, title_ref, kind == "delete", title_name, cover_url, last_chapter_ref, last_page.toInt(), updated_at)
