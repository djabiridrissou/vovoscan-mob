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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Categories (fixed or fetched), `{{category}}` in the catalog request, and the page images' Referer. Fake sites only. */
class CategoriesAndRefererTest {
    private class Sent(
        val url: String,
        val referer: String?,
    )

    private val sent = mutableListOf<Sent>()

    private fun manifest(
        categories: String?,
        referer: String? = null,
        key: String = "shelf",
    ): SourceManifest {
        val json =
            """
            {"contractVersion": 1, "key": "$key", "name": "Shelf", "version": "1.0.0", "kinds": ["comic"], "langs": ["en"],
             "settings": {"baseUrl": {"label": "Site", "type": "url", "default": "https://shelf.example"}},
             "hosts": ["img.shelf.example"],
             ${categories?.let { "\"categories\": $it," } ?: ""}
             "catalog": {"request": {"url": "{{settings.baseUrl}}/titles", "type": "json",
                                     "query": {"page": "{{page}}", "q": "{{search}}", "genre": "{{category}}"}},
                         "list": "$.items[*]", "item": {"id": "$.id", "name": "$.name"}},
             "title": {"request": {"url": "{{settings.baseUrl}}/titles/{{id}}", "type": "json"},
                       "fields": {"name": "$.name"}, "units": {"single": {"id": "{{id}}"}}},
             "content": {"comic": {"request": {"url": "{{settings.baseUrl}}/read/{{unitId}}", "type": "json"},
                                   "list": "$.pages[*]", "item": {"url": "$.url"}
                                   ${referer?.let { ", \"referer\": \"$it\"" } ?: ""}}}}
            """.trimIndent()
        return SourceManifest(key, Json.parseToJsonElement(json).jsonObject, mapOf("baseUrl" to "https://shelf.example"))
    }

    private fun source(
        manifest: SourceManifest,
        routes: Map<String, String> = emptyMap(),
    ): RulesSource {
        val engine =
            MockEngine { request ->
                sent += Sent(request.url.toString(), request.headers[HttpHeaders.Referrer])
                val body = routes[request.url.toString().substringBefore('?')] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        return RulesSource(manifest, RulesHttp(createRulesHttpClient(engine)))
    }

    @AfterTest
    fun tearDown() = ImageReferers.update(emptyList())

    @Test
    fun aFixedListIsReadWithoutARequest() =
        runTest {
            val shelf =
                source(
                    manifest(
                        """[{"id": "action", "name": "Action"}, {"id": "drama", "name": " Drama "}, {"id": "action", "name": "Again"}]""",
                    ),
                )
            val categories = (shelf.categories() as AppResult.Ok).value
            assertEquals(listOf(RulesCategory("action", "Action"), RulesCategory("drama", "Drama")), categories)
            assertTrue(sent.isEmpty())
        }

    @Test
    fun categoriesCanComeFromTheSite() =
        runTest {
            val shelf =
                source(
                    manifest(
                        """{"request": {"url": "{{settings.baseUrl}}/genres", "type": "json"}, "list": "$.genres[*]",
                            "item": {"id": "$.slug", "name": "$.label"}}""",
                    ),
                    mapOf("https://shelf.example/genres" to """{"genres": [{"slug": "sf", "label": "Science fiction"}, {"slug": "x"}]}"""),
                )
            assertEquals(listOf(RulesCategory("sf", "Science fiction")), (shelf.categories() as AppResult.Ok).value)
            assertEquals("https://shelf.example/genres", sent.single().url)
        }

    @Test
    fun noCategoriesDeclaredIsAnEmptyList() =
        runTest {
            assertEquals(emptyList(), (source(manifest(null)).categories() as AppResult.Ok).value)
        }

    @Test
    fun theChosenCategoryGoesInTheCatalogRequestOnly() =
        runTest {
            val shelf =
                source(
                    manifest("""[{"id": "sf", "name": "Science fiction"}]"""),
                    mapOf("https://shelf.example/titles" to """{"items": [{"id": 7, "name": "Dune Road"}]}"""),
                )
            val page = (shelf.catalog(1, "dune", "sf") as AppResult.Ok).value
            assertEquals("Dune Road", page.items.single().name)
            assertTrue("genre=sf" in sent.last().url && "q=dune" in sent.last().url, sent.last().url)
            // Browsing everything: the empty value is not sent at all.
            shelf.catalog(2)
            assertTrue("genre" !in sent.last().url && "page=2" in sent.last().url, sent.last().url)
        }

    @Test
    fun pageImagesCarryTheRefererBookFilesDoNot() =
        runTest {
            val shelf =
                source(
                    manifest(null, referer = "{{settings.baseUrl}}/"),
                    mapOf("https://img.shelf.example/1.webp" to "{}", "https://shelf.example/book.epub" to "{}"),
                )
            assertEquals("https://shelf.example/", shelf.manifest.imageReferer)
            shelf.downloadImage("https://img.shelf.example/1.webp")
            assertEquals("https://shelf.example/", sent.last().referer)
            shelf.download("https://shelf.example/book.epub")
            assertNull(sent.last().referer)
            // A page image on a host the rules produced (a per-chapter image server): fetched, without the Referer.
            shelf.downloadImage("https://cdn7.elsewhere.example/1.webp")
            assertEquals("https://cdn7.elsewhere.example/1.webp" to null, sent.last().url to sent.last().referer)
            // Never the local network, never another scheme, and a book file only from the declared hosts.
            listOf("http://192.168.1.10/1.webp", "http://localhost/1.webp", "ftp://elsewhere.example/1.webp").forEach {
                assertEquals("HOST_NOT_ALLOWED", ((shelf.downloadImage(it) as AppResult.Err).error as AppError.Rejected).code)
            }
            assertTrue(shelf.download("https://elsewhere.example/book.epub") is AppResult.Err)
            assertEquals(3, sent.size)
        }

    @Test
    fun anImageRedirectedToADeclaredHostGetsTheRefererThere() =
        runTest {
            val engine =
                MockEngine { request ->
                    sent += Sent(request.url.toString(), request.headers[HttpHeaders.Referrer])
                    when (request.url.host) {
                        "img.elsewhere.example" ->
                            respond(
                                "",
                                HttpStatusCode.Found,
                                headersOf(HttpHeaders.Location, "https://img.shelf.example/1.webp"),
                            )
                        "img.shelf.example" -> respond("{}", HttpStatusCode.OK)
                        else -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://10.0.0.2/1.webp"))
                    }
                }
            val shelf = RulesSource(manifest(null, referer = "{{settings.baseUrl}}/"), RulesHttp(createRulesHttpClient(engine)))
            assertTrue(shelf.downloadImage("https://img.elsewhere.example/1.webp") is AppResult.Ok)
            assertEquals(listOf(null, "https://shelf.example/"), sent.map { it.referer })
            // A public image server that redirects into the local network is refused on that hop.
            assertTrue(shelf.downloadImage("https://other.example/1.webp") is AppResult.Err)
            assertEquals(3, sent.size)
        }

    @Test
    fun withoutARefererImagesAreFetchedPlain() =
        runTest {
            val shelf = source(manifest(null), mapOf("https://img.shelf.example/1.webp" to "{}"))
            assertNull(shelf.manifest.imageReferer)
            shelf.downloadImage("https://img.shelf.example/1.webp")
            assertNull(sent.single().referer)
        }

    @Test
    fun aRefererThatIsNotAnAddressIsIgnored() {
        assertNull(manifest(null, referer = "{{settings.missing}}").imageReferer)
    }

    @Test
    fun imageLoadersAskByHost() {
        val first = manifest(null, referer = "{{settings.baseUrl}}/", key = "shelf")
        val other = SourceManifest("other", first.json, mapOf("baseUrl" to "https://other.example"))
        val plain = manifest(null, key = "plain")
        ImageReferers.update(listOf(plain, first, other))
        // The site's own host (url setting) and its declared image host; the first source in order wins a shared host.
        assertEquals("https://shelf.example/", ImageReferers.refererFor("https://img.shelf.example/a/1.webp?x=1"))
        assertEquals("https://shelf.example/", ImageReferers.refererFor("https://shelf.example/cover.jpg"))
        assertEquals("https://other.example/", ImageReferers.refererFor("https://other.example/cover.jpg"))
        assertNull(ImageReferers.refererFor("http://localhost:8000/api/v1/media/pages/1"))
        assertNull(ImageReferers.refererFor("file:///data/downloads/0001.webp"))
        ImageReferers.update(emptyList())
        assertNull(ImageReferers.refererFor("https://img.shelf.example/a/1.webp"))
    }
}
