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

    @Test
    fun theRuleIsTheSameWhicheverWayTheDeviceIsHeld() {
        // Landscape: the reading area is wide (874 × 402). A manga page fit to that width would be taller than the
        // screen, yet it stays a whole page (fitted to the screen), as in portrait.
        assertFalse(TallPages.fitsWidth(800f / 1280f, h, w))
        assertFalse(TallPages.fitsWidth(1f, h, w))
        assertFalse(TallPages.fitsWidth(w / (h * 1.2f), h, w))
        // Strip-like pages still fit the width and scroll inside their page.
        assertTrue(TallPages.fitsWidth(800f / 14_000f, h, w))
        assertTrue(TallPages.fitsWidth(w / (h * 1.3f), h, w))
        // The same answer in both orientations, for any page.
        listOf(0.05f, 0.2f, 0.3f, 0.35f, 0.4f, 0.625f, 0.7f, 1f, 1.5f).forEach { ratio ->
            assertEquals(TallPages.fitsWidth(ratio, w, h), TallPages.fitsWidth(ratio, h, w), "ratio $ratio")
        }
        // Fit to width in landscape: its height there.
        assertEquals(h * 14_000f / 800f, TallPages.height(800f / 14_000f, h, w), 1f)
        assertEquals(w, TallPages.height(0.625f, h, w))
        // A Pixel 7 in landscape (2400 × 1080 px, bars aside): the same.
        assertFalse(TallPages.fitsWidth(0.7f, 2400f, 1000f))
        assertTrue(TallPages.fitsWidth(800f / 12_000f, 2400f, 1000f))
    }

    @Test
    fun anAreaThatTakesTwoPagesNeverCropsAPage() {
        // An unfolded foldable (841 × 701 dp): a manga page, even a long one, fits the height; only a strip scrolls.
        assertFalse(TallPages.fitsWidth(800f / 1280f, 841f, 701f))
        assertFalse(TallPages.fitsWidth(800f / 1700f, 841f, 701f))
        assertTrue(TallPages.fitsWidth(800f / 14_000f, 841f, 701f))
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
