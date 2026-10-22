package com.vovoscan.feature.catalog.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun mixKeepsEverythingAndEachSourcesOrder() {
        val lists = listOf((1..8).toList(), listOf(10, 20, 30), listOf(100))
        repeat(50) { seed ->
            val mixed = CatalogMerge.mix(lists, Random(seed))
            assertEquals(lists.flatten().sorted(), mixed.sorted())
            lists.forEach { list -> assertEquals(list, mixed.filter { it in list }) }
        }
        assertEquals(emptyList(), CatalogMerge.mix(emptyList<List<Int>>(), Random(1)))
    }

    @Test
    fun mixIsRandomButRepeatsForTheSameSeed() {
        val lists = listOf((1..6).toList(), (10..15).toList(), (100..105).toList())
        assertEquals(CatalogMerge.mix(lists, Random(7)), CatalogMerge.mix(lists, Random(7)))
        val orders = (0 until 20).map { CatalogMerge.mix(lists, Random(it)) }.toSet()
        assertTrue(orders.size > 10)
        // Not a rotation: the strict one-of-each order is only one of many.
        assertTrue(orders.any { it.take(3).toSet() != setOf(1, 10, 100) })
    }

    @Test
    fun noSourceTakesMoreThanTwoPlacesInARowWhileAnotherHasTitlesLeft() {
        val lists = listOf((1..20).toList(), listOf(100, 101, 102))
        repeat(100) { seed ->
            val mixed = CatalogMerge.mix(lists, Random(seed))
            mixed.windowed(3).forEachIndexed { i, window ->
                val big = window.all { it < 100 }
                // Three of the big source in a row only once the small one has run out.
                if (big) assertTrue(mixed.take(i + 3).count { it >= 100 } == 3, "seed $seed: $mixed")
            }
        }
    }

    @Test
    fun pagesStayInRoundsAndLoadingMoreAppends() {
        val big = listing("big", listOf("b1", "b2", "b3", "b4"), listOf("b5", "b6"))
        val small = listing("small", listOf("s1"), listOf("s2"))
        val empty = listing("empty")
        val merged = CatalogMerge.merge(listOf(big, small, empty), seed = 42).map { it.id }
        assertEquals(setOf("b1", "b2", "b3", "b4", "s1"), merged.take(5).toSet())
        assertEquals(setOf("b5", "b6", "s2"), merged.drop(5).toSet())
        // Loading page 3 of one source appends; everything before keeps its place.
        val more =
            CatalogMerge
                .merge(listOf(listing("big", listOf("b1", "b2", "b3", "b4"), listOf("b5", "b6"), listOf("b7")), small), seed = 42)
                .map { it.id }
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
