package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResumeTest {
    // One page 400 wide and 4000 high: panels every 400 px (y shares of 0.1), their stops centred on an 800 screen.
    private val layout = StripLayout(listOf(ReaderPage(0, "u", 400, 4000)), 400f)

    private fun panel(i: Int) = PanelBox(0, 0f, i / 10f, 1f, 0.1f)

    private val stops = (0..9).map { Stop(maxOf(0f, it * 400f + 200f - 400f), panel(it)) }

    @Test
    fun opensOnePanelBeforeTheOneUnderTheReadingLineAndMarksIt() {
        // The reading line in panel 3 (1200..1600).
        val target = Resume.target(stops, layout, line = 1300f, viewport = 800f)
        assertEquals(panel(3), target.mark)
        assertEquals(stops[2].offset, target.offset)
    }

    @Test
    fun panelsSideBySideCountAsOneStepBack() {
        val left = PanelBox(0, 0f, 0.4f, 0.5f, 0.1f)
        val right = PanelBox(0, 0.5f, 0.4f, 0.5f, 0.1f)
        val side = listOf(Stop(0f, panel(0)), Stop(800f, panel(2)), Stop(1400f, left), Stop(1400f, right), Stop(1800f, panel(5)))
        // On the right panel of the pair: back to the stop above the pair, never to its left panel.
        val target = Resume.target(side, layout, line = 1650f, viewport = 800f)
        assertEquals(right, target.mark)
        assertEquals(800f, target.offset)
    }

    @Test
    fun aFarStopBeforeBacksOffAThirdOfTheScreenInstead() {
        val tall = listOf(Stop(0f, panel(0)), Stop(3000f, PanelBox(0, 0f, 0.8f, 1f, 0.2f)))
        val target = Resume.target(tall, layout, line = 3300f, viewport = 900f)
        assertEquals(3300f - 300f - 300f, target.offset)
    }

    @Test
    fun withoutStopsAThirdOfTheScreenAndNoMark() {
        val target = Resume.target(emptyList(), layout, line = 1500f, viewport = 900f)
        assertEquals(900f, target.offset)
        assertNull(target.mark)
    }

    @Test
    fun theStartOfTheChapterOpensAtTheTopWithNothingMarked() {
        assertEquals(ResumeTarget(0f, null), Resume.target(stops, layout, line = 200f, viewport = 800f))
        assertEquals(0f, Resume.target(stops, layout, line = 400f, viewport = 800f).offset)
    }
}
