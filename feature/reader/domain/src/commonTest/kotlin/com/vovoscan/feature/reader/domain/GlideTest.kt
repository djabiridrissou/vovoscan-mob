package com.vovoscan.feature.reader.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlideTest {
    // 400 wide, pages 400 x 1000, 400 x 600, 400 x 1600: tops 0, 1000, 1600; strip 3200.
    private val layout =
        StripLayout(
            listOf(ReaderPage(0, "a", 400, 1000), ReaderPage(1, "b", 400, 600), ReaderPage(2, "c", 400, 1600)),
            width = 400f,
        )

    @Test
    fun pagesAreStackedAtTheStripWidth() {
        assertEquals(listOf(0f, 1000f, 1600f), layout.tops)
        assertEquals(3200f, layout.total)
        assertEquals(1, layout.pageAt(1200f))
        assertEquals(0, layout.pageAt(-50f))
    }

    @Test
    fun withoutAnalysisEachPageIsOnePanelReadAScreenAtATime() {
        val stops = ReadingStops.of(layout, viewport = 800f).map { it.offset }
        // Page 0 (1000 tall, taller than 800 - 48): from its top minus the margin, then its bottom plus the margin.
        // Page 1 (600 tall) fits: centred. Page 2 (1600): 1600 - 24, then +640, then the end (clamped to 2400).
        assertEquals(listOf(0f, 224f, 900f, 1576f, 2216f, 2400f), stops.map { it.toInt().toFloat() })
    }

    @Test
    fun mangaPanelsSideBySideOnlyMoveTheSpotlight() {
        val row = listOf(PanelBox(1, 0.5f, 0f, 0.5f, 0.3f), PanelBox(1, 0f, 0f, 0.5f, 0.3f), PanelBox(1, 0f, 0.35f, 1f, 0.1f))
        val stops = ReadingStops.of(layout, 800f, mapOf(1 to row))
        val onPage1 = stops.filter { it.panel.page == 1 }
        assertEquals(3, onPage1.size)
        // The right panel then the left one (reading order) share one scroll; the panel below is already whole too.
        assertEquals(1, onPage1.map { it.offset }.distinct().size)
        assertEquals(listOf(0.5f, 0f, 0f), onPage1.map { it.panel.x })
    }

    @Test
    fun stopsNeverGoBackUpAndStayInTheStrip() {
        val stops = ReadingStops.of(layout, 800f).map { it.offset }
        assertEquals(stops.sorted(), stops)
        assertTrue(stops.all { it in 0f..2400f })
    }

    @Test
    fun theCurrentStopIsTheLastAtOrAboveTheTop() {
        val stops = ReadingStops.of(layout, 800f)
        assertEquals(0, ReadingStops.current(stops, 100f))
        assertEquals(2, ReadingStops.current(stops, 901f))
        assertEquals(2, ReadingStops.current(stops, 899f))
    }

    @Test
    fun gutterBandsSplitAPageAtItsFlatRows() {
        val busy = IntArray(Gutters.COLUMNS) { if (it % 2 == 0) 0 else 255 }
        val white = IntArray(Gutters.COLUMNS) { 250 }
        // 20 busy rows, 3 white, 20 busy, 2 white, 3 busy (too short for a panel: joins the band above).
        val rows = List(20) { busy } + List(3) { white } + List(20) { busy } + List(2) { white } + List(3) { busy }
        val bands = Gutters.bands(4, rows)
        assertEquals(2, bands.size)
        assertEquals(0f, bands[0].y)
        assertTrue(abs(bands[0].height - 20f / 48) < 1e-4)
        assertTrue(abs(bands[1].y - 23f / 48) < 1e-4)
        assertTrue(abs(bands[1].height - 25f / 48) < 1e-4)
        assertEquals(4, bands[1].page)
        assertEquals(listOf(PanelBox.page(2)), Gutters.bands(2, List(40) { busy }))
    }

    @Test
    fun theSpringSettlesWithoutOvershootInAboutHalfASecond() {
        val seconds = Glide.settleSeconds(800f)
        assertTrue(seconds in 0.4f..0.8f, "settled in $seconds s")
        var t = 0f
        while (t < 1f) {
            val (x, _) = Glide.at(0f, 0f, 800f, t)
            assertTrue(x <= 800.01f)
            t += 0.01f
        }
    }

    @Test
    fun aRetargetKeepsTheVelocity() {
        val (x, v) = Glide.at(0f, 0f, 800f, 0.1f)
        val (x2, v2) = Glide.at(x, v, 1600f, 0f)
        assertTrue(abs(x - x2) < 1e-3)
        assertTrue(abs(v - v2) < 1e-2)
    }

    @Test
    fun aPositionSurvivesItsTextForm() {
        assertEquals(ReadingPosition(3, 0.25f), ReadingPosition.decode(ReadingPosition(3, 0.25f).encode()))
        assertEquals(ReadingPosition(2, 1f), ReadingPosition.decode("2:7"))
        assertEquals(null, ReadingPosition.decode("x:0.5"))
        assertEquals(null, ReadingPosition.decode(null))
    }
}
