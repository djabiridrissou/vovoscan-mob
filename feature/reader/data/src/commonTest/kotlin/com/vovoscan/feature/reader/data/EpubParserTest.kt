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
    fun ordreDeLectureEtTableDesMatieres() {
        val sections = EpubParser().parse(epub)
        assertEquals(listOf("The River Opens", "The Rain Returns"), sections.map { it.title })
        assertTrue("Paper boats wait on the bank." in sections[0].html)
    }

    @Test
    fun leHtmlEstNettoye() {
        val html = EpubParser().parse(epub)[0].html
        listOf("<script", "alert(", "onclick", "<style", "style=", "<iframe", "tracker.example").forEach { assertFalse(it in html, it) }
        // L'image de l'archive est intégrée : rien n'est chargé du web.
        assertTrue("src=\"data:image/png;base64," in html)
    }

    @Test
    fun cheminsRelatifs() {
        assertEquals("OEBPS/images/dot.png", resolve("OEBPS/text", "../images/dot.png"))
        assertEquals("a/b.xhtml", resolve("", "./a/b.xhtml"))
    }

    @Test
    fun pasUnEpub() {
        assertFailsWith<InvalidEpub> { EpubParser().parse("<html>not a book</html>".encodeToByteArray()) }
    }
}
