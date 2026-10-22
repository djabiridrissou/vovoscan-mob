package com.vovoscan.feature.catalog.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogMergeTest {
    private fun t(
        source: String,
        id: String,
    ) = TitleSummary(source, id, id, null, ContentKind.Comic, ReadingFormat.Manga, "ongoing")

    private fun listing(
        source: String,
        vararg pages: List<String>,
    ) = pages.flatMapIndexed { i, ids -> ids.map { ListedTitle(t(source, it), i + 1) } }

    @Test
    fun roundRobinTakesOneOfEachInTurn() {
        assertEquals(listOf(1, 10, 100, 2, 20, 3), CatalogMerge.roundRobin(listOf(listOf(1, 2, 3), listOf(10, 20), listOf(100))))
        assertEquals(emptyList(), CatalogMerge.roundRobin(emptyList<List<Int>>()))
    }

    @Test
    fun aBigSourceDoesNotDrownTheOthersAndPagesStayInRounds() {
        val big = listing("big", listOf("b1", "b2", "b3", "b4"), listOf("b5", "b6"))
        val small = listing("small", listOf("s1"), listOf("s2"))
        val empty = listing("empty")
        val merged = CatalogMerge.merge(listOf(big, small, empty)).map { it.id }
        assertEquals(listOf("b1", "s1", "b2", "b3", "b4", "b5", "s2", "b6"), merged)
        // Loading page 3 of one source appends; everything before keeps its place.
        val more =
            CatalogMerge
                .merge(
                    listOf(listing("big", listOf("b1", "b2", "b3", "b4"), listOf("b5", "b6"), listOf("b7")), small),
                ).map { it.id }
        assertEquals(merged + "b7", more)
    }

    @Test
    fun categoriesMergeByNameWithEachSourcesOwnId() {
        val union =
            CategoryUnion.of(
                listOf(
                    "vovoscan" to listOf(SourceCategory("manga", "Manga"), SourceCategory("book", "Book")),
                    "gutenberg" to listOf(SourceCategory("fiction", "Fiction"), SourceCategory("8", " BOOK "), SourceCategory("9", "book")),
                    "blank" to listOf(SourceCategory("x", "  ")),
                ),
            )
        assertEquals(listOf("Manga", "Book", "Fiction"), union.map { it.name })
        assertEquals(mapOf("vovoscan" to "book", "gutenberg" to "8"), union[1].ids)
        assertEquals("Book", CategoryUnion.find(union, "bOoK")?.name)
        assertNull(CategoryUnion.find(union, "Horror"))
        assertNull(CategoryUnion.find(union, null))
    }
}
