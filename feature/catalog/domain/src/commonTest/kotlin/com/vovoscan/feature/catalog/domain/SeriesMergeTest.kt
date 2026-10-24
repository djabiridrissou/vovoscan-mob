package com.vovoscan.feature.catalog.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesMergeTest {
    private val ours = SeriesMember(INTERNAL_SOURCE, "t1", "Ink Road", 1)
    private val inkwell = SeriesMember("inkwell", "ink-road", "Ink Road", 3)
    private val quill = SeriesMember("quill", "ir", "Ink Road", 2)
    private val series = Series("s1", listOf(inkwell, ours, quill))
    private val bySeries = series.members.associate { it.key to series }

    @Test
    fun oneItemPerSeriesStandsAtItsOwnPlace() {
        val items = listOf("other/a", ours.key, "other/b", inkwell.key, quill.key)
        val folded = SeriesMerge.fold(items, { it }, bySeries, emptySet())
        assertEquals(listOf("other/a", "other/b", inkwell.key), folded.map { it.item })
        assertEquals(listOf(0, 0, 2), folded.map { it.otherSources })
    }

    @Test
    fun aMemberAtASourceTurnedOffNeitherStandsNorCounts() {
        val items = listOf(ours.key, inkwell.key)
        val folded = SeriesMerge.fold(items, { it }, bySeries, setOf(INTERNAL_SOURCE, "quill"))
        assertEquals(listOf(ours.key), folded.map { it.item })
        assertEquals(1, folded.single().otherSources)
    }

    @Test
    fun aTitleInNoSeriesStaysAsItIs() {
        val folded = SeriesMerge.fold(listOf("x/1", "x/2"), { it }, bySeries, emptySet())
        assertEquals(listOf(Folded("x/1", 0), Folded("x/2", 0)), folded)
    }

    @Test
    fun chaptersMergeByNumberOwnSourceFirstNewestFirst() {
        fun ch(
            id: String,
            n: Double,
        ) = Chapter(id, n, null, 10, null)
        val merged =
            SeriesMerge.chapters(
                own = ours,
                lists =
                    listOf(
                        inkwell to listOf(ch("i3", 3.0), ch("i2", 2.0), ch("i1", 1.0)),
                        ours to listOf(ch("o1", 1.0)),
                        quill to listOf(ch("q2", 2.0), ch("q1", 1.0), ch("q4", 4.0)),
                    ),
            )
        assertEquals(listOf("q4", "i3", "i2", "o1"), merged.map { it.second.id })
        assertEquals(listOf("quill", "inkwell", "inkwell", INTERNAL_SOURCE), merged.map { it.first.sourceKey })
    }
}
