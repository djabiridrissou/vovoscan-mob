package com.vovoscan.feature.library.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.isoUtc
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.feature.library.domain.LibraryEntry
import com.vovoscan.feature.library.domain.LibraryRepository
import com.vovoscan.feature.library.domain.TitleRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writes locally first (the screen updates at once, even offline), then syncs.
 * One sync at a time; a change is sent with the time of the action, not the time it is sent.
 */
class LibraryRepositoryImpl(
    private val remote: LibraryRemoteDataSource,
    private val local: LibraryLocalDataSource,
    private val clock: Clock,
) : LibraryRepository {
    private val syncing = Mutex()

    override fun observe(): Flow<List<LibraryEntry>> = local.observe()

    override fun isFollowed(
        sourceKey: String,
        titleRef: String,
    ): Flow<Boolean> = local.isFollowed(sourceKey, titleRef)

    override suspend fun follow(title: TitleRef) {
        val current = local.entry(title.sourceKey, title.id)
        local.applyLocal(
            LibraryEntry(
                title.sourceKey,
                title.id,
                title.name,
                title.coverUrl,
                current?.lastChapterRef,
                current?.lastPage ?: 0,
                clock.nowMillis(),
                true,
            ),
            deleted = false,
        )
        sync()
    }

    override suspend fun unfollow(
        sourceKey: String,
        titleRef: String,
    ) {
        val current = local.entry(sourceKey, titleRef) ?: return
        local.applyLocal(current.copy(updatedAt = clock.nowMillis(), pendingSync = true), deleted = true)
        sync()
    }

    override suspend fun saveProgress(
        sourceKey: String,
        titleRef: String,
        chapterRef: String,
        page: Int,
    ) {
        // A title is never followed implicitly: progress only counts for a followed title.
        val current = local.entry(sourceKey, titleRef) ?: return
        local.applyLocal(current.copy(lastChapterRef = chapterRef, lastPage = page, updatedAt = clock.nowMillis()), deleted = false)
        // Sent right away if the network responds; otherwise it waits in the queue (the reader already spaces out calls).
        sync()
    }

    override suspend fun sync(): AppResult<Unit> =
        syncing.withLock {
            for (change in local.pending()) {
                val result =
                    if (change.delete) {
                        remote.remove(change.sourceKey, change.titleRef, isoUtc(change.updatedAt))
                    } else {
                        remote.save(
                            change.sourceKey,
                            change.titleRef,
                            isoUtc(change.updatedAt),
                            change.lastChapterRef,
                            change.lastPage,
                            change.titleName,
                            change.coverUrl,
                        )
                    }
                when (result) {
                    is AppResult.Ok -> {
                        local.dequeue(change)
                        local.applyServer(result.value, parseIsoMillis(result.value.updatedAt) ?: change.updatedAt)
                    }
                    // Offline or session expired: the queue waits, nothing is lost.
                    is AppResult.Err ->
                        if (result.error == AppError.Network || result.error == AppError.Unauthorized) {
                            return@withLock result
                        } else {
                            // Permanent rejection (title removed from the catalog…): the operation is dropped.
                            local.dequeue(change)
                        }
                }
            }
            pull()
        }

    private suspend fun pull(): AppResult<Unit> =
        when (val result = remote.entries(local.lastPulledAt())) {
            is AppResult.Err -> result
            is AppResult.Ok -> {
                var newest: Long? = null
                result.value.forEach { dto ->
                    val at = parseIsoMillis(dto.updatedAt) ?: return@forEach
                    local.applyServer(dto, at)
                    if (newest == null || at > newest) newest = at
                }
                newest?.let { local.setLastPulledAt(isoUtc(it)) }
                AppResult.Ok(Unit)
            }
        }

    override suspend fun clearLocal() = local.clear()
}
