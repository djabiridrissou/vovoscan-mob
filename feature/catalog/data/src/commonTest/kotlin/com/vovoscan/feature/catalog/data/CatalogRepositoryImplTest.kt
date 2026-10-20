package com.vovoscan.feature.catalog.data

import app.cash.turbine.test
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.ReadingFormat
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogRepositoryImplTest {
    private val page =
        """{"data":[{"id":"t1","name":"Ink Road","format":"webtoon","status":"ongoing"}],"total":30,"page":1,"perPage":24,"pages":2}"""
    private val detail =
        """{"data":{"id":"t1","name":"Ink Road","format":"webtoon","status":"ongoing","description":"A road of ink.",""" +
            """"authors":["A. Author"],"genres":["Action"],"chapters":[{"id":"c1","number":1,"pageCount":19,"publishedAt":"2026-10-01"}]}}"""

    private fun TestScope.repository(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): CatalogRepositoryImpl {
        val local =
            SqlCatalogLocalDataSource(CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema)), StandardTestDispatcher(testScheduler))
        return CatalogRepositoryImpl(KtorCatalogRemoteDataSource(testHttpClient(handler = handler)), local)
    }

    @Test
    fun laPremierePageRemplitLaCopieLocale() =
        runTest {
            val repo = repository { respondJson(page) }
            repo.observeTitles().test {
                assertEquals(emptyList(), awaitItem())
                assertEquals(true, (repo.refreshTitles() as com.vovoscan.core.common.AppResult.Ok).value)
                val titles = awaitItem()
                assertEquals("Ink Road", titles.single().name)
                assertEquals(ReadingFormat.Webtoon, titles.single().format)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun laFicheSeLitHorsLigneApresUnPremierPassage() =
        runTest {
            var online = true
            val repo = repository { if (online) respondJson(detail) else throw IllegalStateException("offline") }
            repo.refreshTitle("t1")
            online = false
            repo.refreshTitle("t1")
            repo.observeTitle("t1").test {
                val title = awaitItem()!!
                assertEquals("A road of ink.", title.description)
                assertEquals("1", title.chapters.single().label)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun rechercheHorsLigneDansLaCopieLocale() =
        runTest {
            var online = true
            val repo = repository { if (online) respondJson(page) else throw IllegalStateException("offline") }
            repo.refreshTitles()
            online = false
            val result = repo.search("ink")
            assertTrue(result.offline)
            assertEquals("t1", result.titles.single().id)
        }
}
