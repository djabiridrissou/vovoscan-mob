package com.vovoscan.feature.library.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.feature.library.data.db.LibraryDatabase
import com.vovoscan.feature.library.data.db.Library_entry
import com.vovoscan.feature.library.data.db.Outbox
import com.vovoscan.feature.library.domain.LibraryEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** An action taken on the device, waiting to be sent. */
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

/** A heart given or taken back on the device, waiting to be sent. */
data class PendingFavorite(
    val sourceKey: String,
    val titleRef: String,
    val favorite: Boolean,
    val titleName: String,
    val coverUrl: String?,
    val updatedAt: Long,
)

/** Local source: library entries, outbox and sync cursor. */
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

    /** Applies a local action and queues it, in the same transaction. */
    suspend fun applyLocal(
        entry: LibraryEntry,
        deleted: Boolean,
    )

    suspend fun pending(): List<PendingChange>

    fun isFavorite(
        sourceKey: String,
        titleRef: String,
    ): Flow<Boolean>

    /**
     * The heart on or off, queued, in one transaction. On a title not in the library (or removed), it is added (the
     * server follows it too).
     */
    suspend fun applyFavorite(
        favorite: PendingFavorite,
        followed: LibraryEntry,
    )

    suspend fun pendingFavorites(): List<PendingFavorite>

    /** Removes the sent heart, unless a newer one replaced it meanwhile. */
    suspend fun dequeueFavorite(favorite: PendingFavorite)

    /** A heart the server refused for good: put back as it was (unless a newer one is queued). */
    suspend fun revertFavorite(favorite: PendingFavorite)

    /** Removes the sent operation, unless a newer one has replaced it in the meantime. */
    suspend fun dequeue(change: PendingChange)

    /** State returned by the server; ignored if a newer local action is still waiting. */
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
            putEntry(
                entry.sourceKey,
                entry.titleRef,
                entry.titleName,
                entry.coverUrl,
                entry.lastChapterRef,
                entry.lastPage,
                entry.updatedAt,
                deleted,
                true,
            )
            // Removing a title takes its heart away (the server does the same).
            if (deleted) queries.setFavorite(0, null, entry.updatedAt, entry.sourceKey, entry.titleRef)
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

    override fun isFavorite(
        sourceKey: String,
        titleRef: String,
    ): Flow<Boolean> =
        queries
            .entry(sourceKey, titleRef)
            .asFlow()
            .mapToOneOrNull(io)
            .map { it != null && it.deleted == 0L && it.favorite == 1L }

    override suspend fun applyFavorite(
        favorite: PendingFavorite,
        followed: LibraryEntry,
    ) = withContext(io) {
        queries.transaction {
            val row = queries.entry(favorite.sourceKey, favorite.titleRef).executeAsOneOrNull()
            if (favorite.favorite && (row == null || row.deleted == 1L)) {
                putEntry(
                    followed.sourceKey,
                    followed.titleRef,
                    followed.titleName,
                    followed.coverUrl,
                    row?.last_chapter_ref,
                    row?.last_page?.toInt() ?: 0,
                    favorite.updatedAt,
                    deleted = false,
                    pending = true,
                )
            }
            queries.setFavorite(
                if (favorite.favorite) 1 else 0,
                favorite.updatedAt.takeIf { favorite.favorite },
                favorite.updatedAt,
                favorite.sourceKey,
                favorite.titleRef,
            )
            queries.enqueueFavorite(
                favorite.sourceKey,
                favorite.titleRef,
                if (favorite.favorite) 1 else 0,
                favorite.titleName,
                favorite.coverUrl,
                favorite.updatedAt,
            )
        }
    }

    override suspend fun pendingFavorites(): List<PendingFavorite> =
        withContext(io) {
            queries.pendingFavorites().executeAsList().map {
                PendingFavorite(it.source_key, it.title_ref, it.favorite == 1L, it.title_name, it.cover_url, it.updated_at)
            }
        }

    override suspend fun dequeueFavorite(favorite: PendingFavorite) =
        withContext(io) { queries.dequeueFavorite(favorite.sourceKey, favorite.titleRef, favorite.updatedAt).let { } }

    override suspend fun revertFavorite(favorite: PendingFavorite) =
        withContext(io) {
            queries.transaction {
                if (queries.favoriteQueuedAt(favorite.sourceKey, favorite.titleRef).executeAsOneOrNull() != null) return@transaction
                queries.setFavorite(if (favorite.favorite) 0 else 1, null, favorite.updatedAt, favorite.sourceKey, favorite.titleRef)
            }
        }

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
            val favoriteQueued = queries.favoriteQueuedAt(dto.sourceKey, dto.titleRef).executeAsOneOrNull()
            // A newer library action still waiting keeps the row as it is; the heart and the counts have their own say.
            if (!(queued && local != null && local.updated_at > updatedAt)) {
                putEntry(
                    dto.sourceKey,
                    dto.titleRef,
                    dto.titleName,
                    dto.coverUrl,
                    dto.lastChapterRef,
                    dto.lastPage,
                    updatedAt,
                    dto.deleted,
                    queued || favoriteQueued != null,
                )
            }
            // The heart's own clock: a heart still waiting on this device wins over an older answer.
            val heartAt = dto.favoriteUpdatedAt?.let(::parseIsoMillis)
            val keepLocal = favoriteQueued != null && (heartAt == null || favoriteQueued > heartAt)
            if (!keepLocal) {
                queries.setFavorite(
                    if (dto.favorite && !dto.deleted) 1 else 0,
                    dto.favoritedAt?.let(::parseIsoMillis).takeIf { dto.favorite },
                    heartAt,
                    dto.sourceKey,
                    dto.titleRef,
                )
            }
            queries.setCounts(dto.chapterCount?.toLong(), dto.newChapters?.toLong(), dto.sourceKey, dto.titleRef)
        }
    }

    override suspend fun lastPulledAt(): String? = withContext(io) { queries.lastPulledAt().executeAsOneOrNull()?.last_pulled_at }

    override suspend fun setLastPulledAt(value: String) = withContext(io) { queries.putLastPulledAt(value).let { } }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearEntries()
                queries.clearOutbox()
                queries.clearFavoriteOutbox()
                queries.clearSyncState()
            }
        }

    /** A new row, or the row's library columns (its heart and counts stay). */
    private fun putEntry(
        sourceKey: String,
        titleRef: String,
        titleName: String,
        coverUrl: String?,
        lastChapterRef: String?,
        lastPage: Int,
        updatedAt: Long,
        deleted: Boolean,
        pending: Boolean,
    ) {
        val d = if (deleted) 1L else 0L
        val p = if (pending) 1L else 0L
        queries.insertEntry(sourceKey, titleRef, titleName, coverUrl, lastChapterRef, lastPage.toLong(), updatedAt, d, p)
        queries.updateEntry(titleName, coverUrl, lastChapterRef, lastPage.toLong(), updatedAt, d, p, sourceKey, titleRef)
    }
}

private fun Library_entry.toDomain() =
    LibraryEntry(
        source_key,
        title_ref,
        title_name,
        cover_url,
        last_chapter_ref,
        last_page.toInt(),
        updated_at,
        pending == 1L,
        favorite = favorite == 1L,
        favoritedAt = favorited_at,
        chapterCount = chapter_count?.toInt(),
        newChapters = new_chapters?.toInt(),
    )

private fun Outbox.toChange() =
    PendingChange(source_key, title_ref, kind == "delete", title_name, cover_url, last_chapter_ref, last_page.toInt(), updated_at)
