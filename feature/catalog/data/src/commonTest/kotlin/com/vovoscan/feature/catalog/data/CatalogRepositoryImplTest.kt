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

    /** A comic source whose catalog declares a format rule (it reads nothing for some titles), and one without. */
    private val inkwellManifest =
        """{"contractVersion":1,"key":"inkwell","name":"Inkwell","version":"1.0.0","kinds":["comic"],"langs":["en"],""" +
            """"rights":{"basis":"public-domain","notes":"Test source."},"hosts":["inkwell.example"],""" +
            """"catalog":{"request":{"url":"https://inkwell.example/strips","type":"json","query":{"page":"{{page}}"}},""" +
            """"list":"${'$'}.items[*]","item":{"id":{"path":"${'$'}.id","then":["string"]},"name":"${'$'}.title",""" +
            """"format":"${'$'}.format"}},""" +
            """"title":{"request":{"url":"https://inkwell.example/t/{{id}}","type":"json"},"fields":{"name":"${'$'}.title"},""" +
            """"units":{"single":{"id":"{{id}}"}}},"content":{"comic":{"request":{"url":"https://inkwell.example/r/{{unitId}}",""" +
            """"type":"json"},"list":"${'$'}.pages[*]","item":{"url":"${'$'}.url"}}}}"""
    private val quillManifest =
        """{"contractVersion":1,"key":"quill","name":"Quill","version":"1.0.0","kinds":["comic"],"langs":["en"],""" +
            """"rights":{"basis":"public-domain","notes":"Test source."},"hosts":["quill.example"],""" +
            """"catalog":{"request":{"url":"https://quill.example/strips","type":"json","query":{"page":"{{page}}"}},""" +
            """"list":"${'$'}.items[*]","item":{"id":{"path":"${'$'}.id","then":["string"]},"name":"${'$'}.title"}},""" +
            """"title":{"request":{"url":"https://quill.example/t/{{id}}","type":"json"},"fields":{"name":"${'$'}.title"},""" +
            """"units":{"single":{"id":"{{id}}"}}},"content":{"comic":{"request":{"url":"https://quill.example/r/{{unitId}}",""" +
            """"type":"json"},"list":"${'$'}.pages[*]","item":{"url":"${'$'}.url"}}}}"""

    private val manifests =
        ManifestProvider { key ->
            when (key) {
                "books" -> booksManifest
                "inkwell" -> inkwellManifest
                "quill" -> quillManifest
                else -> null
            }?.let { SourceManifest(key, Json.parseToJsonElement(it).jsonObject, emptyMap()) }
        }

    /** The on-device source's site answers 503. */
    private var deviceDown = false
    private val deviceRequests = mutableListOf<String>()

    private fun TestScope.repository(
        local: SqlCatalogLocalDataSource =
            SqlCatalogLocalDataSource(CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema)), StandardTestDispatcher(testScheduler)),
        apiHandler:
            suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
                io.ktor.client.request.HttpRequestData,
            ) -> io.ktor.client.request.HttpResponseData,
    ): CatalogRepositoryImpl {
        val device =
            MockEngine { request ->
                if (deviceDown) return@MockEngine respond("", HttpStatusCode.ServiceUnavailable)
                deviceRequests += request.url.toString()
                val body =
                    when (request.url.encodedPath) {
                        // Ink Road names no format although the rule is declared; Paper Kite says manga.
                        "/strips" ->
                            if (request.url.host == "inkwell.example") {
                                """{"items":[{"id":1,"title":"Ink Road"},{"id":2,"title":"Paper Kite","format":"manga"}]}"""
                            } else {
                                """{"items":[{"id":5,"title":"Quill Tide"}]}"""
                            }
                        "/list" ->
                            if (request.url.parameters["shelf"] == "poetry") {
                                """{"items":[{"id":9,"title":"Odes"}],"next":"2"}"""
                            } else {
                                """{"items":[{"id":7,"title":"Old Tales"}],"next":null}"""
                            }
                        "/book/7" -> """{"title":"Old Tales"}"""
                        "/t/1" -> """{"title":"Ink Road"}"""
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
    fun aStripReadFromItsGenresStaysAWebtoonWhateverTheListingsGuess() =
        runTest {
            val local =
                SqlCatalogLocalDataSource(CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema)), StandardTestDispatcher(testScheduler))
            // The title page: no format named, genres say it is a strip.
            local.saveDetail(
                "inkwell",
                TitleDetailDto(
                    "ink-road",
                    "Ink Road",
                    format = "manga",
                    status = "ongoing",
                    genres = listOf("Action", "Webtoons"),
                    formatKnown = false,
                ),
            )
            assertEquals(mapOf("ink-road" to "webtoon"), local.savedFormats("inkwell", listOf("ink-road", "unknown")))
            // A later listing or search from a source that names no format sends the default: it never wins.
            local.saveSummaries(
                "inkwell",
                listOf(TitleSummaryDto("ink-road", "Ink Road", format = "manga", status = "ongoing", formatKnown = false)),
            )
            assertEquals(mapOf("ink-road" to "webtoon"), local.savedFormats("inkwell", listOf("ink-road")))
            // A source's own "manga" never turns a strip known here back (its genres, or the server's word).
            local.saveSummaries("inkwell", listOf(TitleSummaryDto("ink-road", "Ink Road", format = "manga", status = "ongoing")))
            assertEquals(mapOf("ink-road" to "webtoon"), local.savedFormats("inkwell", listOf("ink-road")))
            // A search answer without a cover keeps the cover the listing saved.
            local.saveSummaries(
                "inkwell",
                listOf(
                    TitleSummaryDto(
                        "paper-kite",
                        "Paper Kite",
                        coverUrl = "https://inkwell.example/c.webp",
                        format = "manga",
                        status = "ongoing",
                    ),
                ),
            )
            local.saveSummaries("inkwell", listOf(TitleSummaryDto("paper-kite", "Paper Kite", format = "manga", status = "ongoing")))
            assertEquals(
                mapOf("paper-kite" to "https://inkwell.example/c.webp"),
                local.savedCovers("inkwell", listOf("paper-kite", "ink-road")),
            )
            // Our own catalog's format is the server's word, whatever the genres.
            local.saveDetail("vovoscan", TitleDetailDto("t1", "Griot", format = "manga", status = "ongoing", genres = listOf("Webtoon")))
            assertEquals(mapOf("t1" to "manga"), local.savedFormats("vovoscan", listOf("t1")))
        }

    @Test
    fun formatsAreAskedForEveryComicSourceAndTheServersWordBeatsTheMangaDefault() =
        runTest {
            val asked = mutableListOf<String>()
            val local =
                SqlCatalogLocalDataSource(CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val repo =
                repository(local) { request ->
                    val path = request.url.encodedPath
                    if (!path.endsWith("/formats")) return@repository respond("", HttpStatusCode.NotFound)
                    asked += path + "?" +
                        request.url.parameters
                            .getAll("ref")
                            .orEmpty()
                            .joinToString(",")
                    when (path) {
                        "/api/v1/source-reader/inkwell/formats" -> respondJson("""{"data":{"1":"webtoon","2":"webtoon"}}""")
                        "/api/v1/source-reader/quill/formats" -> respondJson("""{"data":{"5":"webtoon"}}""")
                        else -> respondJson("""{"data":{}}""")
                    }
                }
            repo.refreshListing("inkwell")
            repo.refreshListing("quill")
            repo.refreshListing("books")
            // A listing whose declared rule read nothing: the default, which a strip known on the device beats.
            assertEquals(
                listOf("1" to ReadingFormat.Manga, "2" to ReadingFormat.Manga),
                repo.observeListing("inkwell").first().map { it.title.id to it.title.format },
            )
            assertEquals(mapOf("1" to ReadingFormat.Webtoon, "2" to ReadingFormat.Webtoon), repo.learnFormats("inkwell", listOf("1", "2")))
            assertEquals(mapOf("5" to ReadingFormat.Webtoon), repo.learnFormats("quill", listOf("5")))
            // Never a source without comics.
            assertEquals(emptyMap(), repo.learnFormats("books", listOf("7")))
            assertEquals(
                listOf("/api/v1/source-reader/inkwell/formats?1,2", "/api/v1/source-reader/quill/formats?5"),
                asked,
            )
            // Applied to every listing of the source, whatever the category, over the source's own "manga" too.
            assertEquals(
                listOf("1" to ReadingFormat.Webtoon, "2" to ReadingFormat.Webtoon),
                repo.observeListing("inkwell").first().map { it.title.id to it.title.format },
            )
            // And to search results (typed query, no format filter: nothing more is asked).
            assertEquals(
                setOf("1" to ReadingFormat.Webtoon, "2" to ReadingFormat.Webtoon),
                repo.search("inkwell", "a").titles.mapTo(HashSet()) { it.id to it.format },
            )
            assertEquals(
                ReadingFormat.Webtoon,
                repo
                    .observeListing("quill")
                    .first()
                    .single()
                    .title.format,
            )
            // Asked once each.
            repo.learnFormats("inkwell", listOf("1", "2"))
            assertEquals(2, asked.size)

            // Kept on the device: the next launch (a new repository) and a listing that names no format still show it.
            val relaunched = repository(local) { respond("", HttpStatusCode.NotFound) }
            relaunched.refreshListing("inkwell")
            assertEquals(
                listOf("1" to ReadingFormat.Webtoon, "2" to ReadingFormat.Webtoon),
                relaunched.observeListing("inkwell").first().map { it.title.id to it.title.format },
            )
            // This source answers nothing here: the saved rows are searched ("a" is in both names).
            assertEquals(
                setOf("1" to ReadingFormat.Webtoon, "2" to ReadingFormat.Webtoon),
                relaunched.search("inkwell", "a").titles.mapTo(HashSet()) { it.id to it.format },
            )
            assertEquals(2, asked.size)
        }

    @Test
    fun titlesWithAnEpisodeComeFromTheServerAndTheirCountsStayWhenTheDeviceListsThem() =
        runTest {
            val asked = mutableListOf<String>()
            val local =
                SqlCatalogLocalDataSource(CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val repo =
                repository(local) { request ->
                    val path = request.url.encodedPath
                    val has = request.url.parameters["has"]
                    asked += if (has == null) path else "$path?has=$has"
                    when (path) {
                        "/api/v1/catalog/sources/vovoscan/titles" ->
                            if (has == "episode") {
                                respondJson(
                                    """{"data":[{"id":"t1","name":"Ink Road","kind":"comic","format":"webtoon","status":"ongoing",""" +
                                        """"episodeCount":2}],"total":1,"page":1,"perPage":24,"pages":1}""",
                                )
                            } else {
                                respondJson(page)
                            }
                        "/api/v1/catalog/titles/t1" ->
                            respondJson(
                                """{"data":{"id":"t1","name":"Ink Road","format":"webtoon","status":"ongoing","episodeCount":1,""" +
                                    """"chapters":[{"id":"c2","number":2,"episodeLanguages":[]},""" +
                                    """{"id":"c1","number":1,"episodeLanguages":["","en"]}]}}""",
                            )
                        "/api/v1/source-reader/inkwell/titles" ->
                            respondJson(
                                """{"data":{"items":[{"id":"1","name":"Ink Road","coverUrl":null,"kind":"comic","format":"webtoon",""" +
                                    """"status":"ongoing","episodeCount":1}],"page":1,"hasNext":false,"total":1}}""",
                            )
                        "/api/v1/source-reader/inkwell/title" ->
                            respondJson("""{"data":{"episodeCount":1,"units":[{"id":"1","episodeLanguages":["","fr"]}]}}""")
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }

            // Our catalog: its own listing of titles with an episode, apart from everything.
            assertEquals(AppResult.Ok(false), repo.refreshListing("vovoscan", episodes = true))
            assertEquals(
                listOf("t1" to 2),
                repo.observeListing("vovoscan", episodes = true).first().map {
                    it.title.id to
                        it.title.episodeCount
                },
            )
            assertEquals(emptyList(), repo.observeListing("vovoscan").first())
            assertEquals("/api/v1/catalog/sources/vovoscan/titles?has=episode", asked.last())

            // A source read on the device: the server lists the titles imported from it that have one (not the site).
            val sitesBefore = deviceRequests.size
            assertEquals(AppResult.Ok(false), repo.refreshListing("inkwell", episodes = true))
            assertEquals("/api/v1/source-reader/inkwell/titles?has=episode", asked.last())
            assertEquals(sitesBefore, deviceRequests.size)
            assertEquals(
                listOf(Triple("1", 1, ReadingFormat.Webtoon)),
                repo.observeListing("inkwell", episodes = true).first().map { Triple(it.title.id, it.title.episodeCount, it.title.format) },
            )
            // The device's own listing can't know: the server's count stays on the title, the others have none.
            repo.refreshListing("inkwell")
            assertEquals(
                listOf("1" to 1, "2" to 0),
                repo.observeListing("inkwell").first().map { it.title.id to it.title.episodeCount },
            )
            // Offline, a search under the filter keeps only those.
            assertEquals(listOf("1"), local.searchLocal("inkwell", "", episodes = true).map { it.id })

            // Its title page asks the server for the chapters' languages, because the server said it has an episode.
            assertEquals(AppResult.Ok(Unit), repo.refreshTitle("inkwell", "1"))
            assertEquals("/api/v1/source-reader/inkwell/title", asked.last())
            val title = repo.observeTitle("inkwell", "1").first()!!
            assertEquals(listOf(listOf("", "fr")), title.chapters.map { it.episodeLanguages })
            assertEquals(1, title.summary.episodeCount)

            // Our catalog's chapters come with theirs; "" (the original) is kept like any language.
            repo.refreshTitle("vovoscan", "t1")
            assertEquals(
                listOf("c2" to emptyList(), "c1" to listOf("", "en")),
                repo
                    .observeTitle("vovoscan", "t1")
                    .first()!!
                    .chapters
                    .map { it.id to it.episodeLanguages },
            )

            // The filter is remembered.
            local.saveFilter(CatalogFilter(episodesOnly = true))
            assertTrue(local.observeFilter().first().episodesOnly)
        }

    @Test
    fun aTitleTheServerNeverSaidHasAnEpisodeTellsItNothing() =
        runTest {
            val asked = mutableListOf<String>()
            val repo =
                repository { request ->
                    asked += request.url.encodedPath
                    respond("", HttpStatusCode.NotFound)
                }
            assertEquals(AppResult.Ok(Unit), repo.refreshTitle("inkwell", "1"))
            assertEquals(emptyList(), asked)
            assertEquals(
                listOf(emptyList()),
                repo
                    .observeTitle("inkwell", "1")
                    .first()!!
                    .chapters
                    .map { it.episodeLanguages },
            )
        }

    @Test
    fun aLongSeriesComesByPagesSavedAsTheyArrive() =
        runTest {
            fun chapter(n: Int) = """{"id":"c$n","number":$n,"pageCount":10}"""
            val head =
                """{"data":{"id":"t1","name":"Ink Road","format":"webtoon","status":"ongoing","chapterCount":5,"chapters":[]}}"""
            val asked = mutableListOf<String>()
            var failSecond = false
            var server = (1..5).toList()
            val repo =
                repository { request ->
                    val path = request.url.encodedPath
                    asked += path + "?" + request.url.encodedQuery
                    when {
                        path.endsWith("/titles/t1") -> respondJson(head)
                        path.endsWith("/titles/t1/chapters") -> {
                            val after = request.url.parameters["after"]
                            val limit = request.url.parameters["limit"]!!.toInt()
                            if (after != null && failSecond) return@repository respond("", HttpStatusCode.ServiceUnavailable)
                            val rest = server.filter { after == null || it > after.removePrefix("k").toInt() }
                            val slice = rest.take(minOf(limit, 2))
                            val next = if (rest.size > slice.size) "\"k${slice.last()}\"" else "null"
                            respondJson(
                                """{"data":[${slice.joinToString(",") { chapter(it) }}],"total":${server.size},"nextCursor":$next}""",
                            )
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }

            assertEquals(AppResult.Ok(Unit), repo.refreshTitle("vovoscan", "t1"))
            // The title alone, then a small first page in the list's order (oldest first), then large ones.
            assertEquals("/api/v1/catalog/titles/t1?chapters=none", asked.first())
            val pages = asked.filter { it.contains("/chapters?") }
            assertEquals(3, pages.size)
            assertTrue(pages[0].contains("order=asc") && pages[0].contains("limit=100") && !pages[0].contains("after="))
            assertTrue(pages[1].contains("limit=500") && pages[1].contains("after=k2"))
            assertEquals(
                (1..5).map { "c$it" }.toSet(),
                repo
                    .observeTitle("vovoscan", "t1")
                    .first()!!
                    .chapters
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(null, repo.observeChapterLoad("vovoscan", "t1").first())

            // A reload cut short keeps every chapter saved, even those it didn't reach.
            server = listOf(1, 2, 3)
            failSecond = true
            assertTrue(repo.refreshTitle("vovoscan", "t1") is AppResult.Err)
            assertEquals(
                5,
                repo
                    .observeTitle("vovoscan", "t1")
                    .first()!!
                    .chapters.size,
            )

            // Read to the end, the chapters gone from the server go.
            failSecond = false
            assertEquals(AppResult.Ok(Unit), repo.refreshTitle("vovoscan", "t1"))
            assertEquals(
                setOf("c1", "c2", "c3"),
                repo
                    .observeTitle("vovoscan", "t1")
                    .first()!!
                    .chapters
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun aServerThatSendsEveryChapterIsReadAsItIs() =
        runTest {
            val asked = mutableListOf<String>()
            val repo =
                repository {
                    asked += it.url.encodedPath
                    respondJson(detail)
                }
            assertEquals(AppResult.Ok(Unit), repo.refreshTitle("vovoscan", "t1"))
            assertEquals(listOf("/api/v1/catalog/titles/t1"), asked)
            assertEquals(
                listOf("c1"),
                repo
                    .observeTitle("vovoscan", "t1")
                    .first()!!
                    .chapters
                    .map { it.id },
            )
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
                assertEquals("A road of ink.", awaitItem()!!.synopsis)
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
