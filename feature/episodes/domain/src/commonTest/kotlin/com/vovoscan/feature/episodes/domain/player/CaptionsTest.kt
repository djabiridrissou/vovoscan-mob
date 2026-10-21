package com.vovoscan.feature.episodes.domain.player

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** captions.test.ts, ported: the shared vectors (checked against captions.py by the worker's tests too). */
class CaptionsTest {
    private val vectors = VectorJson.parse(Vectors.captions)

    @Test
    fun captionsMatchTheExport() {
        for (c in vectors.cases("cases")) {
            val text = c.str("text")!!
            val display = c.str("display")
            val duration = c.num("durationMs")!!
            val name = text.take(30).ifEmpty { "empty" }
            assertEquals(c.str("caption"), captionText(text, display), name)
            val cards = captionCards(text, duration.toInt(), null, display)
            assertEquals(c.getValue("cards").jsonArray.map { it.jsonPrimitive.content }, cards, name)
            val at =
                c.getValue("at").jsonArray.map {
                    it.jsonArray.let { p ->
                        p[0].jsonPrimitive.content.toDouble() to
                            p[1].jsonPrimitive.content.toInt()
                    }
                }
            assertEquals(at, at.map { (e, _) -> e to cardAt(cards, e, duration, c.flag("after") ?: false) }, name)
        }
    }

    @Test
    fun keepsEveryCardWithinTwoSubtitleLines() {
        for (c in vectors.cases("cases")) {
            val display = c.str("display")
            val limit = captionLimit(captionText(c.str("text")!!, display))
            for (card in captionCards(c.str("text")!!, c.num("durationMs")!!.toInt(), null, display)) {
                assertTrue(chars(card) <= min(limit, CAPTION_CHARS), card)
            }
        }
    }

    @Test
    fun captionLanguageFollowsTheScript() {
        val cases =
            listOf(
                "Hello there!" to null,
                "……!?" to null,
                "こんにちは、世界！" to "ja",
                "漢字だけ" to "ja",
                "안녕하세요 세계" to "ko",
                "韓國 사람" to "ko",
                "你好，世界。" to "zh",
                "！？" to "zh",
            )
        for ((line, lang) in cases) assertEquals(lang, captionLang(line), line)
    }
}
