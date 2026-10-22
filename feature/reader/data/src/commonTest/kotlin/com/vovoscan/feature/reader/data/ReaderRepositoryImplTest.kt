package com.vovoscan.feature.reader.data

import app.cash.turbine.test
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.RulesHttp
import com.vovoscan.core.sources.createRulesHttpClient
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.ComicFormat
import com.vovoscan.feature.reader.domain.ReadingMode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class ReaderRepositoryImplTest {
    private val comic =
        """{"data":{"id":"c2","titleId":"t1","titleName":"Paper Tide","kind":"comic","number":2,"format":"manga",""" +
            """"previousChapterId":"c1",""" +
            """"nextChapterId":null,"pages":[{"index":0,"url":"https://api.test/p0","width":1000,"height":1500},""" +
            """{"index":1,"url":"https://api.test/p1","width":1000,"height":1500}]}}"""
    private val book =
        """{"data":{"id":"b1","titleId":"t9","titleName":"Letters","kind":"book","number":1,"name":"Full text","format":"text",""" +
            """"pages":[],"document":{"url":"https://api.test/media/documents/d1?sig=x","mediaType":"application/epub+zip"}}}"""
    private val epub = Base64.decode(EpubFixture.base64)
    private val noManifests = ManifestProvider { null }

    private fun TestScope.repository(
        handler:
            suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
                io.ktor.client.request.HttpRequestData,
            ) -> io.ktor.client.request.HttpResponseData,
    ): ReaderRepositoryImpl {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val local = SqlReaderLocalDataSource(ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema)), dispatcher)
        val remote =
            RoutingReaderRemoteDataSource(
                noManifests,
                ApiReaderRemoteDataSource(testHttpClient(handler = handler)),
                OnDeviceReaderRemoteDataSource(
                    noManifests,
                    RulesHttp(createRulesHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
                ),
            )
        return ReaderRepositoryImpl(remote, local, EpubParser(), dispatcher)
    }

    @Test
    fun openedChapterCanBeReadAgainOffline() =
        runTest {
            var online = true
            val repo = repository { if (online) respondJson(comic) else error("offline") }
            repo.refresh("vovoscan", "t1", "c2")
            online = false
            repo.refresh("vovoscan", "t1", "c2")
            repo.observe("vovoscan", "c2").test {
                val read = awaitItem()!!
                // The format rule (vertical pages for every comic); the format itself is kept for the user's defaults.
                assertEquals(ReadingMode.PagedVertical, read.mode)
                assertEquals(ComicFormat.Manga, read.format)
                assertEquals(listOf(0, 1), read.pages.map { it.index })
                assertEquals("c1", read.previousChapterId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun bookIsDownloadedOnceThenReadOffline() =
        runTest {
            var downloads = 0
            var online = true
            val repo =
                repository { request ->
                    if (!online) error("offline")
                    if (request.url.encodedPath.startsWith("/media/documents")) {
                        downloads++
                        respond(epub, HttpStatusCode.OK, headersOf("Content-Type", "application/epub+zip"))
                    } else {
                        respondJson(book)
                    }
                }
            repo.refresh("vovoscan", "t9", "b1")
            assertEquals(AppResult.Ok(Unit), repo.loadBook("vovoscan", "b1"))
            online = false
            assertEquals(AppResult.Ok(Unit), repo.loadBook("vovoscan", "b1"))
            assertEquals(1, downloads)
            repo.observeBook("vovoscan", "b1").test {
                assertEquals(listOf("The River Opens", "The Rain Returns"), awaitItem()!!.sections.map { it.title })
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(repo.sectionHtml("vovoscan", "b1", 1)!!.contains("Every boat finds the sea."))
        }

    @Test
    fun fileThatIsNotABook() =
        runTest {
            val repo =
                repository { request ->
                    if (request.url.encodedPath.startsWith("/media/documents")) respond("<html/>", HttpStatusCode.OK) else respondJson(book)
                }
            repo.refresh("vovoscan", "t9", "b1")
            val result = repo.loadBook("vovoscan", "b1")
            assertIs<AppResult.Err>(result)
            assertEquals("INVALID_BOOK", (result.error as AppError.Rejected).code)
        }
}
