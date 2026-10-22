package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpreadsTest {
    private val card = SpreadSlot.Alone
    private val page = SpreadSlot.Page
    private val wide = SpreadSlot.Alone

    @Test
    fun theCoverStandsAloneThenFacingPagesGoByTwo() {
        // Previous card, 7 pages, next card.
        val spreads = Spreads.of(listOf(card, page, page, page, page, page, page, page, card), paired = true)
        assertEquals(
            listOf(
                ReaderSpread(0),
                ReaderSpread(1),
                ReaderSpread(2, 3),
                ReaderSpread(4, 5),
                ReaderSpread(6, 7),
                ReaderSpread(8),
            ),
            spreads,
        )
    }

    @Test
    fun aWidePageStandsAloneAndThePairingStartsAgainAfterIt() {
        // Pages 1-3, a double spread printed as one wide image, pages 5-7: the pages facing each other stay together.
        val slots = listOf(card, page, page, page, wide, page, page, page, card)
        assertEquals(
            listOf(
                ReaderSpread(0),
                ReaderSpread(1),
                ReaderSpread(2, 3),
                ReaderSpread(4),
                ReaderSpread(5, 6),
                ReaderSpread(7),
                ReaderSpread(8),
            ),
            Spreads.of(slots, paired = true),
        )
    }

    @Test
    fun withoutPairingEveryItemIsItsOwnScreen() {
        val slots = listOf(card, page, page, card)
        assertEquals(slots.indices.map { ReaderSpread(it) }, Spreads.of(slots, paired = false))
        assertEquals(emptyList(), Spreads.of(emptyList(), paired = true))
    }

    @Test
    fun theScreenOfAnItem() {
        val spreads = Spreads.of(listOf(card, page, page, page, card), paired = true)
        assertEquals(2, Spreads.indexOf(spreads, 3))
        assertEquals(2, Spreads.indexOf(spreads, 2))
        assertEquals(0, Spreads.indexOf(spreads, 99))
        assertTrue(spreads[2].double)
        assertEquals(3, spreads[2].last)
    }

    @Test
    fun aWideReadingAreaOrAVerticalHingeTakesTwoPages() {
        // iPad 11" in landscape, an unfolded Pixel Fold flat, the same in book posture.
        assertTrue(Spreads.fit(1180f, 820f))
        assertTrue(Spreads.fit(841f, 701f))
        assertTrue(Spreads.fit(673f, 841f, verticalHinge = true))
        // iPad upright, a phone held sideways (too small for two pages), nothing measured yet.
        assertFalse(Spreads.fit(820f, 1180f))
        assertFalse(Spreads.fit(560f, 360f))
        assertFalse(Spreads.fit(1000f, 0f))
    }

    @Test
    fun slotOfAPage() {
        assertEquals(SpreadSlot.Alone, Spreads.slotOf(ReaderPage(0, "u", 2000, 1400)))
        assertEquals(SpreadSlot.Page, Spreads.slotOf(ReaderPage(0, "u", 1000, 1400)))
        // A webtoon strip in a paged mode scrolls inside its own page, never squeezed into half the screen.
        assertEquals(SpreadSlot.Alone, Spreads.slotOf(ReaderPage(0, "u", 800, 14000)))
        // Unknown size (a source read on the device): a page until its shape says otherwise.
        assertEquals(SpreadSlot.Page, Spreads.slotOf(ReaderPage(0, "u", 0, 0)))
    }

    @Test
    fun keysFollowThePageDirection() {
        assertEquals(KeyAction.Next, ReaderKeys.action(ReaderKey.Left, ReadingMode.PagedRightToLeft))
        assertEquals(KeyAction.Previous, ReaderKeys.action(ReaderKey.Right, ReadingMode.PagedRightToLeft))
        assertEquals(KeyAction.Next, ReaderKeys.action(ReaderKey.Right, ReadingMode.PagedLeftToRight))
        assertEquals(KeyAction.Previous, ReaderKeys.action(ReaderKey.Left, ReadingMode.LongStrip))
        assertEquals(KeyAction.Next, ReaderKeys.action(ReaderKey.Space, ReadingMode.PagedRightToLeft))
        assertEquals(KeyAction.Previous, ReaderKeys.action(ReaderKey.ShiftSpace, ReadingMode.LongStrip))
        assertEquals(KeyAction.Next, ReaderKeys.action(ReaderKey.Down, ReadingMode.Book))
        assertEquals(KeyAction.Previous, ReaderKeys.action(ReaderKey.PageUp, ReadingMode.PagedVertical))
        assertEquals(KeyAction.Close, ReaderKeys.action(ReaderKey.Escape, ReadingMode.LongStrip))
    }
}
