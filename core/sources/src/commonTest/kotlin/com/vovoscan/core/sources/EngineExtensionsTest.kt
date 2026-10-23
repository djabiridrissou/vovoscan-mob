package com.vovoscan.core.sources

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The engine extensions that unblocked signed and script-fed sources: md5, the clock, POST, embedded JSON. */
class EngineExtensionsTest {
    private fun rule(json: String) = Json.parseToJsonElement(json)

    @Test
    fun md5Vectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", md5Hex("The quick brown fox jumps over the lazy dog"))
    }

    @Test
    fun md5Transform() {
        val ctx = RuleContext(Json.parseToJsonElement("""{"t":"abc"}"""), "", emptyMap())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Rules.evaluate(ctx, rule("""{"path":"$.t","then":["md5"]}""")))
    }

    @Test
    fun clockTemplates() {
        assertTrue(Regex("^[0-9]{9,}$").containsMatchIn(Template.render("{{unix}}", emptyMap())))
        assertTrue(Regex("^[0-9]{10}$").containsMatchIn(Template.render("{{utc:yyyyMMddHH}}", emptyMap())))
        // The clock expands inside longer templates, alongside ordinary variables.
        val rendered = Template.render("{{unix}}/{{settings.x}}", mapOf("settings" to mapOf("x" to "v")))
        assertTrue(Regex("^[0-9]+/v$").containsMatchIn(rendered))
    }

    @Test
    fun attrsFallback() {
        val page =
            Ksoup.parse(
                """<div><img id="a" src="https://cdn.example/1.webp"><img id="b" data-src="https://cdn.example/2.webp"></div>""",
                "https://m.example/ch/1",
            )
        val ctx = RuleContext(page, "https://m.example/ch/1", emptyMap())
        // First image: data-src empty, src wins. Second: data-src wins.
        assertEquals(
            listOf("https://cdn.example/1.webp", "https://cdn.example/2.webp"),
            Rules.nodes(ctx, rule("""{"css":"img"}""")).map { node ->
                Rules.evaluate(ctx.child(node), rule("""{"css":":self","attrs":["data-src","src"]}"""))
            },
        )
    }

    @Test
    fun jsonEmbeddedInAScriptTag() {
        val page =
            Ksoup.parse(
                """<div><script id="mk-chapters-data" type="application/json">{"items":[{"num":"2"},{"num":"1"}]}</script></div>""",
                "https://m.example/manga/x/",
            )
        val ctx = RuleContext(page, "https://m.example/manga/x/", emptyMap())
        val nums =
            Rules.nodes(ctx, rule("""{"css":"script#mk-chapters-data","path":"$.items[*]"}""")).map { node ->
                Rules.evaluate(ctx.child(node), rule("""{"path":"$.num"}"""))
            }
        assertEquals(listOf("2", "1"), nums)
    }

    @Test
    fun postWithRenderedBodyAndSignedQuery() =
        runTest {
            val manifestJson =
                """
                {
                  "key": "signed", "name": "Signed", "contractVersion": 1, "kinds": ["comic"], "langs": ["en"],
                  "hosts": ["api.signed.example"],
                  "catalog": {
                    "request": {
                      "url": "https://api.signed.example/list",
                      "type": "json", "method": "post", "contentType": "application/json",
                      "body": "{\"page\": {{page}}, \"search\": \"{{search}}\"}",
                      "query": {"_ts": {"template": "{{unix}}"}, "_t": {"template": "{{unix}}salt", "then": ["md5"]}}
                    },
                    "list": "$.data[*]",
                    "item": {"id": "$.id", "name": "$.name"}
                  },
                  "title": {
                    "request": {"url": "https://api.signed.example/title/{{id}}", "type": "json"},
                    "fields": {"name": "$.name"},
                    "units": {"single": {"id": "{{id}}", "name": "One"}}
                  },
                  "content": {
                    "comic": {
                      "request": {"url": "https://api.signed.example/pages", "type": "json"},
                      "list": "$.pages[*]",
                      "item": {"url": "$"}
                    }
                  }
                }
                """.trimIndent()
            val manifest = SourceManifest("signed", Json.parseToJsonElement(manifestJson).jsonObject, emptyMap())
            val seen = mutableListOf<Triple<HttpMethod, String, String?>>()
            val engine =
                MockEngine { request ->
                    val text = (request.body as? io.ktor.http.content.TextContent)?.text
                    seen += Triple(request.method, request.url.toString(), text)
                    respond(
                        """{"data":[{"id":"a","name":"A"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val source = RulesSource(manifest, RulesHttp(createRulesHttpClient(engine)))
            val page = source.catalog(1)
            assertTrue(page is com.vovoscan.core.common.AppResult.Ok, "catalog failed: $page")
            val (method, url, body) = seen.single()
            assertEquals(HttpMethod.Post, method)
            assertTrue(url.startsWith("https://api.signed.example/list?_ts="), url)
            val ts = Regex("_ts=([0-9]+)").find(url)?.groupValues?.get(1)
            val token = Regex("_t=([0-9a-f]{32})").find(url)?.groupValues?.get(1)
            assertTrue(ts != null && token != null, url)
            assertEquals(md5Hex(ts!! + "salt"), token)
            assertEquals("""{"page": 1, "search": ""}""", body)
        }

    @Test
    fun redirectsMayLeadToSubdomainsOfADeclaredHostOnly() =
        runTest {
            val manifest = SourceManifest("inkwell", Json.parseToJsonElement(Fixtures.inkwell).jsonObject, emptyMap())
            // comics.inkwell.example is declared; its CDN subdomain must be followed, a stranger still refused.
            val toCdn =
                MockEngine { request ->
                    if (request.url.host == "comics.inkwell.example") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.comics.inkwell.example/pages/1.jpg"))
                    } else {
                        respond("image", HttpStatusCode.OK)
                    }
                }
            val ok = RulesHttp(createRulesHttpClient(toCdn)).get(manifest, "https://comics.inkwell.example/pages/1.jpg")
            assertTrue(ok is com.vovoscan.core.common.AppResult.Ok, "subdomain redirect refused: $ok")

            val toStranger =
                MockEngine {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/pages/1.jpg"))
                }
            val refused = RulesHttp(createRulesHttpClient(toStranger)).get(manifest, "https://comics.inkwell.example/pages/1.jpg")
            assertTrue(refused is com.vovoscan.core.common.AppResult.Err)
        }
}
