package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesModesTest {
    private val a = SeriesKey("vovoscan", "t1")
    private val b = SeriesKey("inkwell", "ink:road")
    private val c = SeriesKey("gutenberg", "84")

    @Test
    fun modesAndKeysReadAsTheAccountWritesThem() {
        ReadingMode.comicModes.forEach { assertEquals(it, readingModeOfWire(it.wire!!)) }
        assertNull(ReadingMode.Book.wire)
        assertNull(readingModeOfWire("book"))
        assertEquals(
            setOf("long-strip", "long-strip-gaps", "paged-rtl", "paged-ltr", "paged-vertical"),
            ReadingMode.entries.mapNotNull { it.wire }.toSet(),
        )
        // A title ref may hold a colon; the source key never does.
        assertEquals(b, SeriesKey.of(b.wire))
        assertNull(SeriesKey.of("nokey"))
        assertNull(SeriesKey.of(":t"))
        assertNull(SeriesKey.of("s:"))
    }

    @Test
    fun theDevicesEarlierChoicesAreSentOnceThenTheAccountWins() {
        val first =
            SeriesModeMerge.plan(
                account = mapOf(a to ReadingMode.LongStrip),
                device = mapOf(a to ReadingMode.PagedVertical, b to ReadingMode.PagedRightToLeft),
                pending = emptyMap(),
                migrated = false,
            )
        assertEquals(mapOf(b to ReadingMode.PagedRightToLeft), first.push)
        assertEquals(mapOf(a to ReadingMode.LongStrip), first.local)

        // Migrated: a series the account no longer has (reset on another device) goes back to the default here too.
        val later =
            SeriesModeMerge.plan(
                account = mapOf(a to ReadingMode.LongStrip),
                device = mapOf(a to ReadingMode.LongStrip, b to ReadingMode.PagedRightToLeft),
                pending = emptyMap(),
                migrated = true,
            )
        assertEquals(emptyMap(), later.push)
        assertEquals(mapOf<SeriesKey, ReadingMode?>(b to null), later.local)
    }

    @Test
    fun aChoiceNotSentYetWinsAndGoesOut() {
        val plan =
            SeriesModeMerge.plan(
                account = mapOf(a to ReadingMode.LongStrip, c to ReadingMode.PagedLeftToRight),
                device = mapOf(a to ReadingMode.PagedVertical),
                pending = mapOf(a to ReadingMode.PagedVertical, c to null),
                migrated = true,
            )
        assertEquals(mapOf(a to ReadingMode.PagedVertical, c to null), plan.push)
        assertEquals(emptyMap(), plan.local)
    }
}
