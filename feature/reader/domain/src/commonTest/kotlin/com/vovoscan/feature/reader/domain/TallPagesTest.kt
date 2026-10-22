package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TallPagesTest {
    // iPhone 16 Pro reading area, in points.
    private val w = 402f
    private val h = 874f

    @Test
    fun onlyClearlyTallPagesFitTheWidth() {
        // A webtoon strip (800 × 14 000) is tall; a manga page (800 × 1280) and a square page are not.
        assertTrue(TallPages.fitsWidth(800f / 14_000f, w, h))
        assertFalse(TallPages.fitsWidth(800f / 1280f, w, h))
        assertFalse(TallPages.fitsWidth(1f, w, h))
        // A page just a bit taller than the screen stays whole.
        assertFalse(TallPages.fitsWidth(w / (h * 1.2f), w, h))
        assertTrue(TallPages.fitsWidth(w / (h * 1.3f), w, h))
        // Unknown sizes and an area not laid out yet: fitted to the screen.
        assertFalse(TallPages.fitsWidth(0f, w, h))
        assertFalse(TallPages.fitsWidth(0.05f, 0f, 0f))
        assertEquals(w * 14_000f / 800f, TallPages.height(800f / 14_000f, w, h), 0.5f)
        assertEquals(h, TallPages.height(0.625f, w, h))
    }

    // A tall page from 1000 to 8000 (its last screen starts at 8000 - 874 = 7126).
    private fun rest(
        release: Float,
        proposed: Float = release,
        hasPrevious: Boolean = true,
        hasNext: Boolean = true,
    ) = TallPages.restingOffset(1000f, 7000f, h, hasPrevious, hasNext, release, proposed)

    @Test
    fun insideATallPageTheScrollStaysInIt() {
        assertEquals(3000f, rest(2000f, 3000f))
        // A fling stops at the page's edges.
        assertEquals(7126f, rest(6000f, 9000f))
        assertEquals(1000f, rest(1500f, -2000f))
    }

    @Test
    fun pastAnEdgeItIsAPageTurn() {
        // Dragged a little into the next page: back to the bottom of this one.
        assertEquals(7126f, rest(7300f))
        // More than half the screen, or flung that far: the next page's top.
        assertEquals(8000f, rest(7126f + h * 0.6f))
        assertEquals(8000f, rest(7200f, 7800f))
        // Backwards: the previous page's bottom, which ends where this page starts.
        assertEquals(1000f, rest(900f))
        assertEquals(1000f - h, rest(900f, 400f))
        // From the very edge (a scroll to the neighbouring page): the turn.
        assertEquals(8000f, rest(7126f, 8000f))
        // Measured layouts round: half a point off the edge is the edge.
        assertEquals(8000f, rest(7125.6f, 8000f))
        assertEquals(1000f - h, rest(1000f, 1000f - h))
        // Nothing there: back to the edge.
        assertEquals(7126f, rest(7200f, 9000f, hasNext = false))
        assertEquals(1000f, rest(900f, 0f, hasPrevious = false))
    }

    @Test
    fun aShortPageIsAPagerPage() {
        fun short(
            release: Float,
            proposed: Float,
        ) = TallPages.restingOffset(874f, h, h, hasPrevious = true, hasNext = true, release, proposed)
        assertEquals(874f, short(900f, 1100f))
        assertEquals(874f * 2, short(1000f, 1400f))
        assertEquals(0f, short(700f, 300f))
        assertEquals(874f, short(874f, 874f))
    }
}
