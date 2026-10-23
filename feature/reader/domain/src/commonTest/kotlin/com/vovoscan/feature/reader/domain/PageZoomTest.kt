package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageZoomTest {
    private val w = 400f
    private val h = 800f

    @Test
    fun pinchKeepsTheContentUnderTheFingers() {
        val zoomed = PageZoom.pinch(PageZoom.None, 2f, 100f, 200f, 0f, 0f, w, h)
        assertEquals(ZoomTransform(2f, -100f, -200f), zoomed)
        assertTrue(zoomed.zoomed)
        // The point under the fingers is the same content point: (100, 200) × 2 − (100, 200).
        assertEquals(100f, 100f * zoomed.scale + zoomed.x)
        // The fingers' centre moving pans with it.
        assertEquals(ZoomTransform(2f, -90f, -230f), PageZoom.pinch(zoomed, 1f, 100f, 200f, 10f, -30f, w, h))
    }

    @Test
    fun pinchStaysBetweenFitAndFourTimes() {
        val big = PageZoom.pinch(PageZoom.None, 10f, 0f, 0f, 0f, 0f, w, h)
        assertEquals(PageZoom.MAX, big.scale)
        // Back under 1× (or just above it): fit, no offset left.
        assertEquals(PageZoom.None, PageZoom.pinch(big, 0.1f, 200f, 400f, 0f, 0f, w, h))
        assertEquals(PageZoom.None, PageZoom.pinch(PageZoom.None, 1.01f, 200f, 400f, 0f, 0f, w, h))
        assertFalse(PageZoom.None.zoomed)
    }

    @Test
    fun zoomedContentAlwaysCoversTheArea() {
        // Zooming near a corner cannot pull the content off the area.
        val corner = PageZoom.pinch(PageZoom.None, 2f, 0f, 0f, 50f, 50f, w, h)
        assertEquals(ZoomTransform(2f, 0f, 0f), corner)
        assertEquals(ZoomTransform(2f, -400f, -800f), PageZoom.clamp(ZoomTransform(2f, -900f, -900f), w, h))
    }

    @Test
    fun panMovesTheViewThenScrollsTheContentBeneath() {
        val zoomed = ZoomTransform(2f, -100f, -700f)
        // Across: within bounds only.
        assertEquals(ZoomTransform(2f, -400f, -700f), PageZoom.pan(zoomed, -500f, 0f, w, h).transform)
        // Up the screen by 300: 100 left in the view, the other 200 scroll the content forward by 200 / 2.
        val up = PageZoom.pan(zoomed, 0f, -300f, w, h)
        assertEquals(ZoomTransform(2f, -100f, -800f), up.transform)
        assertEquals(100f, up.scroll)
        // Down past the top: the content scrolls back.
        val down = PageZoom.pan(ZoomTransform(2f, 0f, -20f), 0f, 60f, w, h)
        assertEquals(0f, down.transform.y)
        assertEquals(-20f, down.scroll)
        // Inside the bounds, nothing scrolls.
        assertEquals(0f, PageZoom.pan(zoomed, 0f, 50f, w, h).scroll)
        // At 1× the scroll view has the finger: nothing moves here.
        assertEquals(PageZoom.None, PageZoom.pan(PageZoom.None, 30f, 30f, w, h).transform)
        assertEquals(0f, PageZoom.pan(PageZoom.None, 30f, 30f, w, h).scroll)
    }

    @Test
    fun doubleTapTogglesFitAndTwiceAroundThePoint() {
        val zoomed = PageZoom.doubleTap(PageZoom.None, 300f, 600f, w, h)
        assertEquals(ZoomTransform(PageZoom.DOUBLE_TAP, -300f, -600f), zoomed)
        assertEquals(PageZoom.None, PageZoom.doubleTap(zoomed, 10f, 10f, w, h))
        // From a pinch at any scale, back to fit.
        assertEquals(PageZoom.None, PageZoom.doubleTap(ZoomTransform(3.5f, -10f, -10f), 10f, 10f, w, h))
    }
}
