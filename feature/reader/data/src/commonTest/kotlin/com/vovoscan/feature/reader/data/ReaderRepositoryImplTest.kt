package com.vovoscan.feature.reader.data

import app.cash.turbine.test
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.ReadingMode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderRepositoryImplTest {
    private val chapter =
        """{"data":{"id":"c2","titleId":"t1","titleName":"Paper Tide","number":2,"format":"manga","previousChapterId":"c1",""" +
            """"nextChapterId":null,"pages":[{"index":0,"url":"https://api.test/p0","width":1000,"height":1500},""" +
            """{"index":1,"url":"https://api.test/p1","width":1000,"height":1500}]}}"""

    @Test
    fun unChapitreOuvertSeRelitHorsLigne() =
        runTest {
            var online = true
            val local =
                SqlReaderLocalDataSource(ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val repo =
                ReaderRepositoryImpl(
                    KtorReaderRemoteDataSource(
                        testHttpClient { if (online) respondJson(chapter) else throw IllegalStateException("offline") },
                    ),
                    local,
                )
            repo.refresh("c2")
            online = false
            repo.refresh("c2")
            repo.observe("c2").test {
                val read = awaitItem()!!
                assertEquals(ReadingMode.PagedRightToLeft, read.mode)
                assertEquals(listOf(0, 1), read.pages.map { it.index })
                assertEquals("c1", read.previousChapterId)
                assertEquals("Chapter 2", read.label)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
