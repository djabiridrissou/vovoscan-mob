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
