package com.vovoscan.core.sources

import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RulesTest {
    private fun rule(json: String) = Json.parseToJsonElement(json)

    @Test
    fun jsonPath() {
        val data = Json.parseToJsonElement("""{"formats":{"image/jpeg":"c.jpg"},"authors":[{"name":"A"},{"name":"B"}]}""")
        assertEquals(listOf(JsonPrimitive("c.jpg")), JsonPath.select(data, "$.formats['image/jpeg']"))
        assertEquals(listOf("A", "B"), JsonPath.select(data, "$.authors[*].name").map { Rules.plain(it) })
        assertEquals(listOf("B"), JsonPath.select(data, "$.authors[-1].name").map { Rules.plain(it) })
        assertEquals(emptyList(), JsonPath.select(data, "$.missing.deep"))
    }

    @Test
    fun htmlRules() {
        val page =
            Ksoup.parse(
                """<div><a href="/series/ink-road">x</a><h3> Ink Road </h3><img src="c/1.jpg"></div>""",
                "https://comics.inkwell.example/browse/all",
            )
        val ctx = RuleContext(page, "https://comics.inkwell.example/browse/all", emptyMap())
        assertEquals("ink-road", Rules.evaluate(ctx, rule("""{"css":"a","attr":"href","then":[{"regex":"/series/([a-z-]+)"}]}""")))
        assertEquals("Ink Road", Rules.evaluate(ctx, rule("""{"css":"h3","then":["trim"]}""")))
        assertEquals(
            "https://comics.inkwell.example/browse/c/1.jpg",
            Rules.evaluate(ctx, rule("""{"css":"img","attr":"src","then":["absUrl"]}""")),
        )
        assertEquals("n/a", Rules.evaluate(ctx, rule("""{"css":".missing","default":"n/a"}""")))
    }

    @Test
    fun transformations() {
        val data = Json.parseToJsonElement("""{"id":1342,"tags":["  a ","b"],"status":"Vertical","next":null}""")
        val ctx = RuleContext(data, "", mapOf("settings" to mapOf("files" to "https://m.example")))
        assertEquals("1342", Rules.evaluate(ctx, rule("""{"path":"$.id","then":["string"]}""")))
        assertEquals(
            "https://m.example/1342.jpg",
            Rules.evaluate(ctx, rule("""{"path":"$.id","then":[{"format":"{{settings.files}}/{value}.jpg"}]}""")),
        )
        assertEquals(
            "webtoon",
            Rules.evaluate(ctx, rule("""{"path":"$.status","then":["lower",{"map":{"vertical":"webtoon"},"default":"manga"}]}""")),
        )
        assertEquals(false, Rules.evaluate(ctx, rule("""{"path":"$.next","then":["notNull"]}""")))
        assertNull(Rules.evaluate(ctx, rule("""{"path":"$.nothing"}""")))
    }

    @Test
    fun xmlRules() {
        val feed =
            Rules.parse(
                """<?xml version="1.0"?><feed xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">""" +
                    """<title>Books</title><link rel="next" href="/search.opds?start_index=26"/>""" +
                    """<entry><id>http://www.example.org/ebooks/103.opds</id><title>Around the World</title>""" +
                    """<category scheme="http://purl.org/dc/terms/LCSH" term="Adventure stories"/></entry>""" +
                    """<entry><id>http://www.example.org/ebooks/authors/search.opds</id><title>Authors</title></entry>""" +
                    """</feed>""",
                "xml",
                "https://www.example.org/ebooks/search.opds/",
            )
        val ctx = RuleContext(feed, "https://www.example.org/ebooks/search.opds/", emptyMap())
        // Only the book entry matches: the "Authors" subsection has a different id shape and is skipped.
        val entries = Rules.nodes(ctx, Json.parseToJsonElement("""{"css":"entry"}"""))
        assertEquals(2, entries.size)
        assertEquals(
            "103",
            Rules.evaluate(ctx.child(entries[0]), rule("""{"css":"id","then":[{"regex":"/ebooks/([0-9]+)\\.opds"}]}""")),
        )
        assertEquals(
            "Around the World",
            Rules.evaluate(ctx.child(entries[0]), rule("""{"css":"title","then":["trim"]}""")),
        )
        assertEquals(
            listOf("Adventure stories"),
            Rules.evaluate(
                ctx.child(entries[0]),
                rule("""{"css":"category[scheme='http://purl.org/dc/terms/LCSH']","attr":"term","all":true}"""),
            ),
        )
        assertEquals(
            "https://www.example.org/search.opds?start_index=26",
            Rules.evaluate(ctx, rule("""{"css":"link[rel=next]","attr":"href","then":["absUrl"]}""")),
        )
    }

    @Test
    fun allowedHosts() {
        val manifest =
            SourceManifest(
                "gutenberg",
                Json.parseToJsonElement(Fixtures.gutenberg).jsonObject,
                mapOf(
                    "catalogBaseUrl" to "https://gutendex.example",
                    "filesBaseUrl" to "https://mirror.example",
                ),
            )
        assertEquals(setOf("gutendex.example", "mirror.example"), manifest.allowedHosts)
    }
}
