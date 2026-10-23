package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryMergeTest {
    private fun read(
        title: String,
        chapter: String,
        at: Long,
    ) = LastRead("gutenberg", title, chapter, 3, at)

    private fun place(
        title: String,
        chapter: String?,
        at: Long,
    ) = AccountPlace("gutenberg", title, chapter?.let { read(title, it, at) }, at)

    @Test
    fun anotherDevicesNewerPlaceReplacesThisOne() {
        val changes = HistoryMerge.apply(mapOf("gutenberg/t1" to read("t1", "c1", 100)), listOf(place("t1", "c4", 200)))
        assertEquals(listOf(read("t1", "c4", 200)), changes.put)
        assertEquals(emptyList(), changes.remove)
    }

    @Test
    fun thisDevicesNewerReadStays() {
        val changes = HistoryMerge.apply(mapOf("gutenberg/t1" to read("t1", "c5", 300)), listOf(place("t1", "c4", 200)))
        assertEquals(HistoryChanges(emptyList(), emptyList()), changes)
    }

    @Test
    fun aTitleOnlyReadElsewhereJoinsTheHistory() {
        val changes = HistoryMerge.apply(emptyMap(), listOf(place("t2", "c1", 50)))
        assertEquals(listOf(read("t2", "c1", 50)), changes.put)
    }

    @Test
    fun aNewerTombstoneForgetsTheTitleAnOlderOneDoesNot() {
        val local = mapOf("gutenberg/t1" to read("t1", "c1", 100), "gutenberg/t2" to read("t2", "c1", 500))
        val changes = HistoryMerge.apply(local, listOf(place("t1", null, 200), place("t2", null, 400), place("t3", null, 50)))
        assertEquals(listOf("gutenberg/t1"), changes.remove)
        assertEquals(emptyList(), changes.put)
    }

    @Test
    fun theNewestOfSeveralPlacesForOneTitleWins() {
        val changes = HistoryMerge.apply(emptyMap(), listOf(place("t1", "c2", 200), place("t1", "c9", 900), place("t1", null, 400)))
        assertEquals(listOf(read("t1", "c9", 900)), changes.put)
    }
}
