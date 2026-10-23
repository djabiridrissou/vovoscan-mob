package com.vovoscan.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveTest {
    @Test
    fun phonesKeepTheBottomBarAndOneColumn() {
        val iphone = Adaptive.of(430f, 932f)
        assertEquals(WidthClass.Compact, iphone.widthClass)
        assertEquals(Navigation.BottomBar, iphone.navigation)
        assertFalse(iphone.twoPane)
        assertEquals(Posture.Flat, iphone.posture)
        assertEquals(0f, iphone.hingeGap)
    }

    @Test
    fun tabletsUprightGetARailAndSidewaysASidebarWithTwoPanes() {
        // iPad mini upright, 11" upright, 11" sideways, 13" sideways, a Split View third.
        val mini = Adaptive.of(744f, 1133f)
        assertEquals(Navigation.Rail, mini.navigation)
        assertFalse(mini.twoPane)
        assertEquals(170f, mini.minTileWidth)
        assertEquals(Navigation.Rail, Adaptive.of(820f, 1180f).navigation)
        val landscape = Adaptive.of(1180f, 820f)
        assertEquals(Navigation.Sidebar, landscape.navigation)
        assertTrue(landscape.twoPane)
        assertEquals(Adaptive.PANE, landscape.paneSplit)
        assertTrue(Adaptive.of(1376f, 1032f).twoPane)
        assertEquals(Navigation.BottomBar, Adaptive.of(375f, 1032f).navigation)
        // A medium window wider than tall (Stage Manager, an unfolded phone sideways): two panes.
        assertTrue(Adaptive.of(800f, 600f).twoPane)
    }

    @Test
    fun aSeparatingVerticalHingeSplitsThePanesAtTheFold() {
        // Unfolded, half open like a book: the hinge at 420-420 (seamless) separates because it is half open.
        val book = Adaptive.of(840f, 900f, Hinge(vertical = true, start = 420f, end = 420f, separating = true, halfOpened = true))
        assertEquals(Posture.Book, book.posture)
        assertTrue(book.twoPane)
        assertTrue(book.verticalHinge)
        assertEquals(420f, book.paneSplit)
        assertEquals(0f, book.hingeGap)
        // A device with a physical gap, flat: still split, the gap left empty.
        val gap = Adaptive.of(1114f, 705f, Hinge(vertical = true, start = 540f, end = 574f, separating = true, halfOpened = false))
        assertEquals(Posture.Flat, gap.posture)
        assertEquals(540f, gap.paneSplit)
        assertEquals(34f, gap.hingeGap)
        // Flat and seamless: one window, no split at the fold.
        val flat = Adaptive.of(673f, 841f, Hinge(vertical = true, start = 336f, end = 336f, separating = false, halfOpened = false))
        assertFalse(flat.verticalHinge)
        assertFalse(flat.twoPane)
    }

    @Test
    fun aHorizontalHingeHalfOpenIsTabletop() {
        val laptop = Adaptive.of(841f, 673f, Hinge(vertical = false, start = 336f, end = 336f, separating = true, halfOpened = true))
        assertEquals(Posture.Tabletop, laptop.posture)
        assertTrue(laptop.tabletop)
        assertFalse(laptop.verticalHinge)
    }
}
