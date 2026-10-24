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

    /** Hearts the server refused for good during a sync, by title and time, for the [setFavorite] that queued them. */
    private val refused = mutableMapOf<Triple<String, String, Long>, AppError>()

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

    override fun isFavorite(
        sourceKey: String,
        titleRef: String,
    ): Flow<Boolean> = local.isFavorite(sourceKey, titleRef)

    override suspend fun setFavorite(
        title: TitleRef,
        favorite: Boolean,
    ): AppResult<Unit> {
        val now = clock.nowMillis()
        val current = local.entry(title.sourceKey, title.id)
        val followed =
            LibraryEntry(
                title.sourceKey,
                title.id,
                title.name,
                title.coverUrl,
                current?.lastChapterRef,
                current?.lastPage ?: 0,
                now,
                true,
            )
        val pending = PendingFavorite(title.sourceKey, title.id, favorite, current?.titleName ?: title.name, title.coverUrl, now)
        local.applyFavorite(pending, followed)
        sync()
        val key = Triple(title.sourceKey, title.id, now)
        return syncing.withLock { refused.remove(key) }?.let { AppResult.Err(it) } ?: AppResult.Ok(Unit)
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
            sendFavorites()?.let { return@withLock it }
            pull()
        }

    /** Drains the hearts; an error that should stop the sync (offline, signed out, rate limited) is returned. */
    private suspend fun sendFavorites(): AppResult.Err? {
        for (heart in local.pendingFavorites()) {
            val at = isoUtc(heart.updatedAt)
            val result =
                if (heart.favorite) {
                    remote.favorite(heart.sourceKey, heart.titleRef, at, heart.titleName, heart.coverUrl)
                } else {
                    remote.unfavorite(heart.sourceKey, heart.titleRef, at)
                }
            when (result) {
                is AppResult.Ok -> {
                    local.dequeueFavorite(heart)
                    local.applyServer(result.value, parseIsoMillis(result.value.updatedAt) ?: heart.updatedAt)
                }
                is AppResult.Err ->
                    when {
                        result.error.waits() -> return result
                        // Taking back a heart on a title the server no longer has in the library: done.
                        !heart.favorite && (result.error as? AppError.Rejected)?.code == "FAVORITE_NOT_FOUND" ->
                            local.dequeueFavorite(heart)
                        else -> {
                            local.dequeueFavorite(heart)
                            local.revertFavorite(heart)
                            refused[Triple(heart.sourceKey, heart.titleRef, heart.updatedAt)] = result.error
                        }
                    }
            }
        }
        return null
    }

    private fun AppError.waits() = this == AppError.Network || this == AppError.Unauthorized || this == AppError.RateLimited

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
