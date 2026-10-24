package com.vovoscan.feature.catalog.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRulesTest {
    private fun found(
        source: String,
        id: String,
    ) = TitleSummary(source, id, "$source $id", "https://cover/$id", ContentKind.Comic, ReadingFormat.Manga, "ongoing")

    @Test
    fun normalisingFoldsCaseAccentsAndSpaces() {
        assertEquals("kingdom of ink", SearchRules.normalise("  Kíngdom   OF ink "))
        assertEquals("", SearchRules.normalise("   "))
    }

    @Test
    fun aSearchIsRememberedOnceNewestFirstAndCapped() {
        val recent = (1..10).map { "search $it" }
        val next = SearchRules.remember(recent, " Search 3 ")
        assertEquals("Search 3", next.first())
        assertEquals(10, next.size)
        assertEquals(1, next.count { SearchRules.normalise(it) == "search 3" })
        // Too short to teach anything: nothing changes.
        assertEquals(recent, SearchRules.remember(recent, "a"))
        assertEquals(10, SearchRules.remember(recent, "brand new").size)
    }

    @Test
    fun recentSearchesMatchWhatIsTyped() {
        val recent = listOf("Kingdom", "Ink Road", "The king's ink")
        assertEquals(recent, SearchRules.matching(recent, " "))
        assertEquals(listOf("Kingdom", "The king's ink"), SearchRules.matching(recent, "KING"))
        // What is typed already is not offered back.
        assertEquals(emptyList(), SearchRules.matching(recent, "kingdom"))
    }

    @Test
    fun suggestedTitlesComeFirstOnceEachThenTheMix() {
        val mixed = listOf(found("a", "1"), found("b", "2"), found("a", "3"))
        val suggested =
            listOf(
                SuggestedTitle("a", "3", "a 3", reason = SuggestReason.Popular),
                SuggestedTitle("c", "9", "Typo match", format = ReadingFormat.Webtoon, reason = SuggestReason.Popular),
                SuggestedTitle("a", "3", "a 3 again", reason = SuggestReason.Yours),
            )
        val ordered = SearchRules.order(suggested, mixed)
        assertEquals(listOf("a/3", "c/9", "a/1", "b/2"), ordered.map { "${it.sourceKey}/${it.id}" })
        // The source's own copy wins (its cover); a title no source returned is a card of its own.
        assertEquals("https://cover/3", ordered[0].coverUrl)
        assertEquals(ReadingFormat.Webtoon, ordered[1].format)
        assertEquals(null, ordered[1].coverUrl)
        // Without extras (a curated category), only what the sources returned.
        assertEquals(listOf("a/3", "a/1", "b/2"), SearchRules.order(suggested, mixed, extras = false).map { "${it.sourceKey}/${it.id}" })
    }

    @Test
    fun aTextTitleIsABook() {
        assertEquals(ContentKind.Book, SuggestedTitle("g", "1", "Book", format = ReadingFormat.Text).toSummary().kind)
        assertEquals(ReadingFormat.Manga, SuggestedTitle("g", "2", "Unknown").toSummary().format)
    }
}
