package com.vovoscan.feature.reader.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class EpubParserTest {
    private val epub = Base64.decode(EpubFixture.base64)

    @Test
    fun readingOrderAndTableOfContents() {
        val sections = EpubParser().parse(epub)
        assertEquals(listOf("The River Opens", "The Rain Returns"), sections.map { it.title })
        assertTrue("Paper boats wait on the bank." in sections[0].html)
    }

    @Test
    fun htmlIsSanitized() {
        val html = EpubParser().parse(epub)[0].html
        listOf("<script", "alert(", "onclick", "<style", "style=", "<iframe", "tracker.example").forEach { assertFalse(it in html, it) }
        // The archive image is inlined: nothing is loaded from the web.
        assertTrue("src=\"data:image/png;base64," in html)
    }

    @Test
    fun relativePaths() {
        assertEquals("OEBPS/images/dot.png", resolve("OEBPS/text", "../images/dot.png"))
        assertEquals("a/b.xhtml", resolve("", "./a/b.xhtml"))
    }

    @Test
    fun notAnEpub() {
        assertFailsWith<InvalidEpub> { EpubParser().parse("<html>not a book</html>".encodeToByteArray()) }
    }
}
