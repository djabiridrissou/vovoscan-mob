package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderRepository
import kotlinx.coroutines.flow.Flow

/** Stale-while-revalidate : la copie locale s'affiche, la réponse du serveur la remplace (URLs fraîches). */
class ReaderRepositoryImpl(
    private val remote: ReaderRemoteDataSource,
    private val local: ReaderLocalDataSource,
) : ReaderRepository {
    override fun observe(chapterId: String): Flow<ReaderChapter?> = local.observe(chapterId)

    override suspend fun refresh(chapterId: String): AppResult<Unit> =
        remote.chapter(chapterId).also { if (it is AppResult.Ok) local.save(it.value) }.map { }

    override suspend fun clearLocal() = local.clear()
}
