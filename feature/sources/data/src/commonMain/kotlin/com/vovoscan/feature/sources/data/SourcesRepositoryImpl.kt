package com.vovoscan.feature.sources.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.sources.domain.SourceItem
import com.vovoscan.feature.sources.domain.SourcesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Le choix s'applique tout de suite sur l'appareil ; il part au serveur dès que le réseau répond. */
class SourcesRepositoryImpl(
    private val remote: SourcesRemoteDataSource,
    private val local: SourcesLocalDataSource,
) : SourcesRepository {
    private val syncing = Mutex()

    override fun observe(): Flow<List<SourceItem>> = local.observe()

    override suspend fun refresh(): AppResult<Unit> =
        syncing.withLock {
            // Un choix fait hors ligne part d'abord, sinon la liste du serveur l'écraserait.
            local.pending()?.let { pending ->
                when (val sent = remote.save(pending)) {
                    is AppResult.Ok -> {
                        local.clearPending()
                        local.replace(sent.value)
                        return@withLock AppResult.Ok(Unit)
                    }
                    is AppResult.Err ->
                        if (sent.error == AppError.Network || sent.error == AppError.Unauthorized) {
                            return@withLock sent
                        } else {
                            local.clearPending()
                        }
                }
            }
            when (val mine = remote.mine()) {
                is AppResult.Ok -> AppResult.Ok(local.replace(mine.value))
                is AppResult.Err -> mine
            }
        }

    override suspend fun save(choices: List<Pair<String, Boolean>>): AppResult<Unit> {
        local.applyPending(choices.map { (key, enabled) -> SourceChoiceDto(key, enabled) })
        return refresh()
    }

    override suspend fun clearLocal() = local.clear()
}
