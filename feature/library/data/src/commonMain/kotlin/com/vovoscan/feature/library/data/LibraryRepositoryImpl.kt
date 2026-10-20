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
 * Écrit d'abord en local (l'écran change tout de suite, même hors ligne), puis synchronise.
 * Une seule synchronisation à la fois ; l'envoi garde l'heure de l'action, pas celle de l'envoi.
 */
class LibraryRepositoryImpl(
    private val remote: LibraryRemoteDataSource,
    private val local: LibraryLocalDataSource,
    private val clock: Clock,
) : LibraryRepository {
    private val syncing = Mutex()

    override fun observe(): Flow<List<LibraryEntry>> = local.observe()

    override fun isFollowed(titleId: String): Flow<Boolean> = local.isFollowed(titleId)

    override suspend fun follow(title: TitleRef) {
        val current = local.entry(title.id)
        local.applyLocal(
            LibraryEntry(title.id, title.name, title.coverUrl, current?.lastChapterId, current?.lastPage ?: 0, clock.nowMillis(), true),
            deleted = false,
        )
        sync()
    }

    override suspend fun unfollow(titleId: String) {
        val current = local.entry(titleId) ?: return
        local.applyLocal(current.copy(updatedAt = clock.nowMillis(), pendingSync = true), deleted = true)
        sync()
    }

    override suspend fun saveProgress(
        titleId: String,
        chapterId: String,
        page: Int,
    ) {
        // On ne suit pas un titre sans l'avoir demandé : la progression ne vaut que pour un titre suivi.
        val current = local.entry(titleId) ?: return
        local.applyLocal(current.copy(lastChapterId = chapterId, lastPage = page, updatedAt = clock.nowMillis()), deleted = false)
        // Envoyée tout de suite si le réseau répond ; sinon elle attend dans la file (le lecteur espace déjà les appels).
        sync()
    }

    override suspend fun sync(): AppResult<Unit> =
        syncing.withLock {
            for (change in local.pending()) {
                val result =
                    if (change.delete) {
                        remote.remove(change.titleId, isoUtc(change.updatedAt))
                    } else {
                        remote.save(change.titleId, isoUtc(change.updatedAt), change.lastChapterId, change.lastPage)
                    }
                when (result) {
                    is AppResult.Ok -> {
                        local.dequeue(change)
                        local.applyServer(result.value, parseIsoMillis(result.value.updatedAt) ?: change.updatedAt)
                    }
                    // Hors ligne ou session expirée : la file attend, rien n'est perdu.
                    is AppResult.Err ->
                        if (result.error == AppError.Network || result.error == AppError.Unauthorized) {
                            return@withLock result
                        } else {
                            // Refus définitif (titre retiré du catalogue…) : l'opération est abandonnée.
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
