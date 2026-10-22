package com.vovoscan.core.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatHintTest {
    @Test
    fun stripGenresMakeAWebtoonLikeTheServer() {
        listOf("Long Strip", " longstrip ", "WEBTOON", "Webtoons", "Manhwa", "manhua", "Vertical", "vertical scroll").forEach {
            assertEquals("webtoon", formatHint(listOf("Action", it)), it)
        }
        assertNull(formatHint(listOf("Action", "Long strips of land", "Vertical-ish")))
        assertNull(formatHint(emptyList()))
    }
}
