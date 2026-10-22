package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RulesSourceTest {
    private val sent = mutableListOf<String>()

    private fun source(
        manifestJson: String,
        key: String,
        settings: Map<String, String>,
        routes: Map<String, Pair<String, String>>,
    ): RulesSource {
        val engine =
            MockEngine { request ->
                sent += request.url.toString()
                val path = request.url.toString().substringBefore('?')
                val (body, type) = routes[path] ?: return@MockEngine respond("not found", HttpStatusCode.NotFound)
                if (type == "redirect") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, body))
                } else {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, type))
                }
            }
        val manifest = SourceManifest(key, Json.parseToJsonElement(manifestJson).jsonObject, settings)
        return RulesSource(manifest, RulesHttp(createRulesHttpClient(engine)))
    }

    private val gutenbergSettings =
        mapOf(
            "catalogBaseUrl" to "https://gutendex.example",
            "filesBaseUrl" to "https://mirror.example",
            "languages" to "en,fr",
        )

    private val book =
        """{"id":1342,"title":"Pride and  Prejudice","authors":[{"name":"Austen, Jane"}],"summaries":["A novel of manners."],""" +
            """"bookshelves":["Best Books"],"formats":{}}"""

    @Test
    fun gutenbergRunsOnTheDevice() =
        runTest {
            val gutenberg =
                source(
                    Fixtures.gutenberg,
                    "gutenberg",
                    gutenbergSettings,
                    mapOf(
                        "https://gutendex.example/books" to
                            ("""{"count":40,"next":"https://gutendex.example/books?page=2","results":[$book]}""" to "application/json"),
                        "https://gutendex.example/books/1342" to (book to "application/json"),
                    ),
                )
            val page = (gutenberg.catalog(1, "pride") as AppResult.Ok).value
            assertEquals(Triple("1342", "Pride and Prejudice", "book"), page.items.single().let { Triple(it.id, it.name, it.kind) })
            assertEquals("https://mirror.example/cache/epub/1342/pg1342.cover.medium.jpg", page.items.single().coverUrl)
            assertEquals(true to 40, page.hasNext to page.total)
            assertTrue(sent.single().contains("copyright=false") && sent.single().contains("search=pride"))

            val title = (gutenberg.title("1342") as AppResult.Ok).value
            assertEquals(listOf("Austen, Jane"), title.authors)
            assertEquals(listOf("Best Books"), title.genres)
            assertEquals("A novel of manners.", title.description)
            assertEquals(listOf(RulesUnit("1342", 1.0, "Full text")), title.units)
            assertEquals(
                "https://mirror.example/cache/epub/1342/pg1342-images.epub",
                (gutenberg.bookUrl("1342", "1342") as AppResult.Ok).value,
            )
        }

    @Test
    fun htmlComic() =
        runTest {
            val base = "https://comics.inkwell.example"
            val inkwell =
                source(
                    Fixtures.inkwell,
                    "inkwell",
                    emptyMap(),
                    mapOf(
                        "$base/browse" to (
                            """<div class="card"><a href="/series/ink-road">x</a><h3> Ink Road </h3>""" +
                                """<img src="/c.jpg"><span class="tag">Vertical</span></div>""" +
                                """<a class="next" href="/browse?p=2">Next</a>""" to "text/html"
                        ),
                        "$base/series/ink-road" to (
                            """<h1>Ink Road</h1><p class="synopsis">A courier   crosses.</p><span class="author">Studio</span>""" +
                                """<ul class="chapters"><li><a href="/read/12">Chapter 2: The Gate</a></li>""" +
                                """<li><a href="/read/11">Chapter 1</a></li></ul>""" to "text/html"
                        ),
                        "$base/read/12" to
                            (
                                """<div class="reader"><img data-src="/img/1.png" width="60" height="90">""" +
                                    """<img data-src="/img/2.png"></div>""" to "text/html"
                            ),
                    ),
                )
            val title = (inkwell.catalog(1) as AppResult.Ok).value.items.single()
            assertEquals("webtoon" to "https://comics.inkwell.example/c.jpg", title.format to title.coverUrl)
            val detail = (inkwell.title("ink-road") as AppResult.Ok).value
            assertEquals(listOf(RulesUnit("11", 1.0, null), RulesUnit("12", 2.0, "The Gate")), detail.units)
            val pages = (inkwell.comicPages("ink-road", "12") as AppResult.Ok).value
            assertEquals(listOf(RulesImage("$base/img/1.png", 60, 90), RulesImage("$base/img/2.png", 0, 0)), pages)
        }

    @Test
    fun startIndexPaginationOverOpds() =
        runTest {
            val feed =
                """<?xml version="1.0"?><feed>""" +
                    """<link rel="next" href="/ebooks/search.opds/?start_index=26"/>""" +
                    """<entry><id>http://www.gut.example/ebooks/103.opds</id><title>Around the World</title></entry>""" +
                    """</feed>"""
            val opds =
                source(
                    """{"contractVersion":1,"key":"gut","name":"Gut","version":"1.0.0","kinds":["book"],"langs":["en"],"rights":{"basis":"public-domain","notes":"notes here"},"hosts":["www.gut.example"],"catalog":{"request":{"url":"https://www.gut.example/ebooks/search.opds/","type":"xml","query":{"query":"{{search}}","sort_order":"downloads"},"pagination":{"type":"startIndex","param":"start_index","pageSize":25}},"list":{"css":"entry"},"item":{"id":{"css":"id","then":[{"regex":"/ebooks/([0-9]+)\\.opds"}]},"name":{"css":"title","then":["trim"]}},"hasNext":{"css":"link[rel=next]","attr":"href","then":["notNull"]}},"title":{"request":{"url":"https://www.gut.example/ebooks/{{id}}","type":"html"},"fields":{"name":{"css":"h1"}},"units":{"single":{"id":"{{id}}"}}},"content":{"book":{"document":{"url":{"template":"https://www.gut.example/cache/epub/{{id}}/pg{{id}}-images.epub"},"mediaType":"application/epub+zip"}}}}""",
                    "gut",
                    emptyMap(),
                    mapOf("https://www.gut.example/ebooks/search.opds/" to (feed to "application/atom+xml")),
                )
            val page1 = (opds.catalog(1) as AppResult.Ok).value
            assertEquals("103", page1.items.single().id)
            assertEquals(true, page1.hasNext)
            // Page 1 sends no start_index (it equals the base); page 3 computes base + 2 * pageSize.
            assertEquals("https://www.gut.example/ebooks/search.opds/?sort_order=downloads", sent[0])
            opds.catalog(3)
            assertEquals(
                "https://www.gut.example/ebooks/search.opds/?sort_order=downloads&start_index=51",
                sent[1],
            )
        }

    @Test
    fun cursorPagination() =
        runTest {
            val explore =
                """{"items":[{"slug":"alpha","title":"Alpha","coverUrl":"https://cdn.example/a.jpg"}],"nextCursor":"CURSOR-2"}"""
            val explore2 =
                """{"items":[{"slug":"beta","title":"Beta","coverUrl":"https://cdn.example/b.jpg"}],"nextCursor":"CURSOR-3"}"""
            val explore3 =
                """{"items":[{"slug":"gamma","title":"Gamma","coverUrl":"https://cdn.example/c.jpg"}]}"""
            val engine =
                MockEngine { request ->
                    sent += request.url.toString()
                    when (request.url.parameters["cursor"]) {
                        "CURSOR-2" -> respond(explore2, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        "CURSOR-3" -> respond(explore3, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> respond(explore, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            val manifest =
                SourceManifest(
                    "toonspace",
                    Json
                        .parseToJsonElement(
                            """{"contractVersion":1,"key":"toonspace","name":"ToonSpace","version":"1.0.0","kinds":["comic"],"langs":["en"],"rights":{"basis":"public-domain","notes":"notes here"},"hosts":["api.example"],"catalog":{"request":{"url":"https://api.example/api/explore","type":"json","query":{"cursor":"{{cursor}}"},"pagination":{"type":"cursor","param":"cursor","from":{"path":"$.nextCursor"}}},"list":"$.items[*]","item":{"id":{"path":"$.slug"},"name":{"path":"$.title"},"cover":{"path":"$.coverUrl"}}},"title":{"request":{"url":"https://api.example/title/{{id}}","type":"html"},"fields":{"name":{"css":"h1"}},"units":{"single":{"id":"{{id}}"}}},"content":{"comic":{"request":{"url":"https://api.example/read/{{unitId}}","type":"html"},"list":{"css":"img"},"item":{"url":{"css":":self","attr":"src","then":["absUrl"]}}}}}""",
                        ).jsonObject,
                    emptyMap(),
                )
            val toonspace = RulesSource(manifest, RulesHttp(createRulesHttpClient(engine)))

            // The engine follows the cursor chain: page 3 issues three requests, each with the previous cursor.
            val page3 = (toonspace.catalog(3) as AppResult.Ok).value
            assertEquals("gamma", page3.items.single().id)
            assertEquals(false, page3.hasNext)
            assertEquals(3, sent.count { it.startsWith("https://api.example/api/explore") })
            assertTrue(sent.any { it == "https://api.example/api/explore?cursor=CURSOR-3" })
            sent.clear()

            // Page 1 reports a next page while a cursor is present; an exhausted chain stops early.
            val page1 = (toonspace.catalog(1) as AppResult.Ok).value
            assertEquals("alpha", page1.items.single().id)
            assertEquals(true, page1.hasNext)
            sent.clear()
            val page10 = (toonspace.catalog(10) as AppResult.Ok).value
            assertEquals("gamma", page10.items.single().id)
            assertEquals(false, page10.hasNext)
            assertEquals(3, sent.count { it.startsWith("https://api.example/api/explore") })
        }

    @Test
    fun undeclaredHostIsRejected() =
        runTest {
            val gutenberg =
                source(
                    Fixtures.gutenberg,
                    "gutenberg",
                    gutenbergSettings,
                    mapOf("https://gutendex.example/books" to ("https://evil.example/steal" to "redirect")),
                )
            val result = gutenberg.catalog(1)
            assertIs<AppResult.Err>(result)
            assertEquals("HOST_NOT_ALLOWED", (result.error as AppError.Rejected).code)
            // The redirect to an undeclared host was never followed.
            assertTrue(sent.none { it.startsWith("https://evil.example") })
        }
}
