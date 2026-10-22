package com.vovoscan.feature.catalog.data

import app.cash.turbine.test
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.RulesHttp
import com.vovoscan.core.sources.SourceManifest
import com.vovoscan.core.sources.createRulesHttpClient
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.CatalogFilter
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SourceCategory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogRepositoryImplTest {
    private val page =
        """{"data":[{"id":"t1","name":"Ink Road","kind":"comic","format":"webtoon",""" +
            """"status":"ongoing"}],"total":30,"page":1,"perPage":24,"pages":2}"""
    private val detail =
        """{"data":{"id":"t1","name":"Ink Road","format":"webtoon","status":"ongoing","description":"A road of ink.",""" +
            """"authors":["A. Author"],"genres":["Action"],"chapters":[{"id":"c1","number":1,""" +
            """"pageCount":19,"publishedAt":"2026-10-01"}]}}"""

    /** A minimal on-device source: JSON catalog, single-unit title page, book. */
    private val booksManifest =
        """{"contractVersion":1,"key":"books","name":"Books","version":"1.0.0","kinds":["book"],"langs":["en"],""" +
            """"rights":{"basis":"public-domain","notes":"Test source."},"hosts":["books.example"],""" +
            """"categories":[{"id":"poetry","name":"Poetry"},{"id":"plays","name":"Plays"}],""" +
            """"catalog":{"request":{"url":"https://books.example/list","type":"json",""" +
            """"query":{"page":"{{page}}","q":"{{search}}","shelf":"{{category}}"}},""" +
            """"list":"${'$'}.items[*]","item":{"id":{"path":"${'$'}.id","then":["string"]},"name":"${'$'}.title"},""" +
            """"hasNext":{"path":"${'$'}.next","then":["notNull"]}},""" +
            """"title":{"request":{"url":"https://books.example/book/{{id}}","type":"json"},"fields":{"name":"${'$'}.title"},""" +
            """"units":{"single":{"id":"{{id}}","name":"Full text"}}},""" +
            """"content":{"book":{"document":{"url":{"template":"https://books.e""" +
            """xample/files/{{id}}.epub"},"mediaType":"application/epub+zip"}}}}"""

    private val manifests =
        ManifestProvider { key ->
            if (key ==
                "books"
            ) {
                SourceManifest("books", Json.parseToJsonElement(booksManifest).jsonObject, emptyMap())
            } else {
                null
            }
        }

    /** The on-device source's site answers 503. */
    private var deviceDown = false
    private val deviceRequests = mutableListOf<String>()

    private fun TestScope.repository(
        apiHandler:
            suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
                io.ktor.client.request.HttpRequestData,
            ) -> io.ktor.client.request.HttpResponseData,
    ): CatalogRepositoryImpl {
        val local =
            SqlCatalogLocalDataSource(CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema)), StandardTestDispatcher(testScheduler))
        val device =
            MockEngine { request ->
                if (deviceDown) return@MockEngine respond("", HttpStatusCode.ServiceUnavailable)
                deviceRequests += request.url.toString()
                val body =
                    when (request.url.encodedPath) {
                        "/list" ->
                            if (request.url.parameters["shelf"] == "poetry") {
                                """{"items":[{"id":9,"title":"Odes"}],"next":"2"}"""
                            } else {
                                """{"items":[{"id":7,"title":"Old Tales"}],"next":null}"""
                            }
                        "/book/7" -> """{"title":"Old Tales"}"""
                        else -> return@MockEngine respond("", HttpStatusCode.NotFound)
                    }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val remote =
            RoutingCatalogRemoteDataSource(
                manifests,
                ApiCatalogRemoteDataSource(testHttpClient(handler = apiHandler)),
                OnDeviceCatalogRemoteDataSource(manifests, RulesHttp(createRulesHttpClient(device))),
            )
        return CatalogRepositoryImpl(remote, local)
    }

    @Test
    fun internalSourceThroughTheApi() =
        runTest {
            val repo = repository { respondJson(page) }
            repo.observeListing("vovoscan").test {
                assertEquals(emptyList(), awaitItem())
                assertEquals(AppResult.Ok(true), repo.refreshListing("vovoscan"))
                val titles = awaitItem().map { it.title }
                assertEquals(ReadingFormat.Webtoon to "vovoscan", titles.single().format to titles.single().sourceKey)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun onDeviceSourceBypassesTheApi() =
        runTest {
            var apiCalls = 0
            val repo =
                repository {
                    apiCalls++
                    respondJson(page)
                }
            repo.refreshListing("books")
            repo.refreshTitle("books", "7")
            repo.observeTitle("books", "7").test {
                val title = awaitItem()!!
                assertEquals(ContentKind.Book to ReadingFormat.Text, title.summary.kind to title.summary.format)
                assertEquals("Full text", title.chapters.single().name)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, apiCalls)
        }

    @Test
    fun titlePageReadsOfflineAndCatalogsNeverMix() =
        runTest {
            var online = true
            val repo =
                repository { if (online) respondJson(if (it.url.encodedPath.endsWith("/t1")) detail else page) else error("offline") }
            repo.refreshListing("vovoscan")
            repo.refreshTitle("vovoscan", "t1")
            online = false
            repo.observeTitle("vovoscan", "t1").test {
                assertEquals("A road of ink.", awaitItem()!!.description)
                cancelAndIgnoreRemainingEvents()
            }
            val result = repo.search("vovoscan", "ink")
            assertTrue(result.offline)
            assertEquals("t1", result.titles.single().id)
            // Searching "books" never sees the vovoscan titles.
            assertTrue(repo.search("books", "ink").titles.all { it.sourceKey == "books" && it.id != "t1" })
        }

    @Test
    fun aSourceThatIsDownIsNamedAndSearchUsesWhatIsSaved() =
        runTest {
            val repo = repository { error("unused") }
            repo.refreshListing("books")
            deviceDown = true
            assertEquals(AppResult.Err(AppError.SourceUnavailable("Books")), repo.refreshListing("books"))
            val found = repo.search("books", "old")
            assertTrue(found.offline)
            assertEquals("Books" to "7", found.unavailableSource to found.titles.single().id)
        }

    @Test
    fun clearSourceErasesOnlyThatSource() =
        runTest {
            val local =
                SqlCatalogLocalDataSource(CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val title = TitleSummaryDto("t1", "Ink Road", null, "comic", "webtoon", "ongoing")
            local.saveCatalogPage("demo", TitlesPage(1, listOf(title), hasNext = true))
            local.saveCatalogPage("vovoscan", TitlesPage(1, listOf(title), hasNext = false))
            local.clearSource("demo")
            assertEquals(0, local.searchLocal("demo", "Ink").size)
            assertEquals(null, local.nextPage("demo"))
            assertEquals(1, local.searchLocal("vovoscan", "Ink").size)
        }

    @Test
    fun eachCategoryHasItsOwnListing() =
        runTest {
            val repo = repository { error("unused") }
            assertEquals(AppResult.Ok(false), repo.refreshListing("books"))
            assertEquals(AppResult.Ok(true), repo.refreshListing("books", "poetry"))
            assertTrue(deviceRequests.last().contains("shelf=poetry"))
            assertTrue(deviceRequests.none { it.contains("shelf=") && !it.contains("poetry") })
            assertEquals(listOf("7"), repo.observeListing("books").first().map { it.title.id })
            assertEquals(listOf("9" to 1), repo.observeListing("books", "poetry").first().map { it.title.id to it.page })
            assertTrue(repo.hasMore("books", "poetry"))
            // Offline search in a category looks in that category's saved listing only.
            deviceDown = true
            assertEquals(listOf("9"), repo.search("books", "o", "poetry").titles.map { it.id })
        }

    @Test
    fun categoriesComeFromTheManifestOrTheApi() =
        runTest {
            val repo =
                repository {
                    respondJson("""{"data":[{"key":"book","name":"Book","position":1},{"key":"webtoon","name":"Webtoon","position":0}]}""")
                }
            repo.refreshCategories("books")
            assertEquals(
                listOf(SourceCategory("poetry", "Poetry"), SourceCategory("plays", "Plays")),
                repo.observeCategories("books").first(),
            )
            assertEquals(AppResult.Ok(Unit), repo.refreshCategories("vovoscan"))
            assertEquals(listOf("webtoon", "book"), repo.observeCategories("vovoscan").first().map { it.id })
            repo.clearSource("books")
            assertEquals(emptyList(), repo.observeCategories("books").first())
        }

    @Test
    fun theFilterIsRememberedOnTheDevice() =
        runTest {
            val repo = repository { error("unused") }
            assertEquals(CatalogFilter(), repo.observeFilter().first())
            repo.saveFilter(CatalogFilter(listOf("books", "vovoscan"), "Poetry"))
            assertEquals(CatalogFilter(listOf("books", "vovoscan"), "Poetry"), repo.observeFilter().first())
            repo.saveFilter(CatalogFilter())
            assertEquals(CatalogFilter(), repo.observeFilter().first())
        }
}
