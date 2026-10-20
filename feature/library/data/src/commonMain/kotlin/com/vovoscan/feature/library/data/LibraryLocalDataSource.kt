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
    val titleId: String,
    val delete: Boolean,
    val lastChapterId: String?,
    val lastPage: Int,
    val updatedAt: Long,
)

/** Source locale : entrées de la bibliothèque, file d'envoi et curseur de synchronisation. */
interface LibraryLocalDataSource {
    fun observe(): Flow<List<LibraryEntry>>

    fun isFollowed(titleId: String): Flow<Boolean>

    suspend fun entry(titleId: String): LibraryEntry?

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

    override fun isFollowed(titleId: String): Flow<Boolean> =
        queries
            .isFollowed(titleId)
            .asFlow()
            .mapToOne(io)
            .map { it > 0 }

    override suspend fun entry(titleId: String): LibraryEntry? =
        withContext(io) {
            queries
                .entry(titleId)
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
                entry.titleId,
                entry.titleName,
                entry.coverUrl,
                entry.lastChapterId,
                entry.lastPage.toLong(),
                entry.updatedAt,
                if (deleted) 1 else 0,
                1,
            )
            queries.enqueue(
                entry.titleId,
                if (deleted) "delete" else "save",
                entry.lastChapterId,
                entry.lastPage.toLong(),
                entry.updatedAt,
            )
        }
    }

    override suspend fun pending(): List<PendingChange> = withContext(io) { queries.pending().executeAsList().map { it.toChange() } }

    override suspend fun dequeue(change: PendingChange) = withContext(io) { queries.dequeue(change.titleId, change.updatedAt).let { } }

    override suspend fun applyServer(
        dto: LibraryEntryDto,
        updatedAt: Long,
    ) = withContext(io) {
        queries.transaction {
            val local = queries.entry(dto.titleId).executeAsOneOrNull()
            val queued = queries.isQueued(dto.titleId).executeAsOne() > 0
            if (queued && local != null && local.updated_at > updatedAt) return@transaction
            queries.putEntry(
                dto.titleId,
                dto.titleName,
                dto.coverUrl,
                dto.lastChapterId,
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
    LibraryEntry(title_id, title_name, cover_url, last_chapter_id, last_page.toInt(), updated_at, pending == 1L)

private fun Outbox.toChange() = PendingChange(title_id, kind == "delete", last_chapter_id, last_page.toInt(), updated_at)
