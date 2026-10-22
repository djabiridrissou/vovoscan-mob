package com.vovoscan.feature.library.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** A library title, identified by its source and its id at that source. */
data class LibraryEntry(
    val sourceKey: String,
    val titleRef: String,
    val titleName: String,
    val coverUrl: String?,
    val lastChapterRef: String?,
    val lastPage: Int,
    /** Time of the last change (device or server), in ms. */
    val updatedAt: Long,
    /** Changed on the device, not sent yet. */
    val pendingSync: Boolean,
    /** In the favourites (the heart). */
    val favorite: Boolean = false,
    /** When the heart was given (ms), while [favorite]. */
    val favoritedAt: Long? = null,
    /** Our catalog's titles: released chapters, and those after the last one read (the server's word; null otherwise). */
    val chapterCount: Int? = null,
    val newChapters: Int? = null,
)

/** What a title screen knows about the title when following it (name and cover kept for the library). */
data class TitleRef(
    val sourceKey: String,
    val id: String,
    val name: String,
    val coverUrl: String?,
)

/**
 * Offline-first library, across all sources: each action is applied to the local database at once and goes
 * into an outbox; [sync] drains the outbox, then fetches changes made on other devices.
 * The server settles conflicts (last-write-wins on the action time).
 */
interface LibraryRepository {
    fun observe(): Flow<List<LibraryEntry>>

    fun isFollowed(
        sourceKey: String,
        titleRef: String,
    ): Flow<Boolean>

    suspend fun follow(title: TitleRef)

    suspend fun unfollow(
        sourceKey: String,
        titleRef: String,
    )

    fun isFavorite(
        sourceKey: String,
        titleRef: String,
    ): Flow<Boolean>

    /**
     * The heart, applied at once and queued like the library (its own clock): giving it follows the title, taking it
     * back keeps it followed. Err only when the server refused it for good (the heart is then put back as it was);
     * offline it waits in the queue and the answer is Ok.
     */
    suspend fun setFavorite(
        title: TitleRef,
        favorite: Boolean,
    ): AppResult<Unit>

    suspend fun saveProgress(
        sourceKey: String,
        titleRef: String,
        chapterRef: String,
        page: Int,
    )

    /** Sends the outbox, then pulls changes; without a network, the outbox waits for next time. */
    suspend fun sync(): AppResult<Unit>

    suspend fun clearLocal()
}

/**
 * Port: chapters after the last one read for a title of a source read on the device (the server only counts our own
 * catalog's), from the device's copy of the title; null when it isn't known. Wired by the app to the catalog.
 */
fun interface NewChapters {
    suspend fun count(
        sourceKey: String,
        titleRef: String,
        lastChapterRef: String?,
    ): Int?

    companion object {
        val Unknown = NewChapters { _, _, _ -> null }
    }
}
