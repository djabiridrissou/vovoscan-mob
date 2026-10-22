package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TapZonesTest {
    /** The layouts as the spec draws them: rows top to bottom, P = Previous, N = Next, M = Menu. */
    private val drawn =
        mapOf(
            TapLayout.RightAndLeft to "PMN/PMN/PMN",
            TapLayout.LShaped to "PPP/PMN/NNN",
            TapLayout.Kindle to "MMM/PNN/PNN",
            TapLayout.Edge to "NMN/NMN/NPN",
            TapLayout.Disabled to "MMM/MMM/MMM",
        )

    private fun action(c: Char) =
        when (c) {
            'P' -> TapAction.Previous
            'N' -> TapAction.Next
            else -> TapAction.Menu
        }

    private fun expected(
        layout: TapLayout,
        invert: TapInvert,
        mode: ReadingMode,
    ): List<String> {
        val concrete =
            when {
                layout != TapLayout.Default -> layout
                mode == ReadingMode.PagedRightToLeft || mode == ReadingMode.PagedLeftToRight -> TapLayout.RightAndLeft
                else -> TapLayout.LShaped
            }
        var rows = drawn.getValue(concrete).split("/")
        if (invert == TapInvert.Vertical || invert == TapInvert.Both) rows = rows.reversed()
        val horizontal = invert == TapInvert.Horizontal || invert == TapInvert.Both
        if (horizontal != (mode == ReadingMode.PagedRightToLeft)) rows = rows.map { it.reversed() }
        return rows
    }

    /** Nine points inside each cell: its centre, near its corners and near the middle of its edges. */
    private val offsets = listOf(0.01f, 0.5f, 0.99f)

    @Test
    fun everyLayoutInvertAndModeOnEveryCell() {
        for (layout in TapLayout.entries) {
            for (invert in TapInvert.entries) {
                for (mode in ReadingMode.comicModes) {
                    val rows = expected(layout, invert, mode)
                    for (row in 0..2) {
                        for (column in 0..2) {
                            for (dy in offsets) {
                                for (dx in offsets) {
                                    val x = (column + dx) / 3f
                                    val y = (row + dy) / 3f
                                    assertEquals(
                                        action(rows[row][column]),
                                        TapZones.resolve(layout, invert, mode, x, y),
                                        "$layout $invert $mode at ($x, $y)",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rightToLeftAdvancesOnTheLeft() {
        val rtl = ReadingMode.PagedRightToLeft
        assertEquals(TapAction.Next, TapZones.resolve(TapLayout.Default, TapInvert.None, rtl, 0.1f, 0.5f))
        assertEquals(TapAction.Previous, TapZones.resolve(TapLayout.Default, TapInvert.None, rtl, 0.9f, 0.5f))
        // An explicit horizontal invert flips the mirroring back.
        assertEquals(TapAction.Previous, TapZones.resolve(TapLayout.Default, TapInvert.Horizontal, rtl, 0.1f, 0.5f))
        assertEquals(TapAction.Next, TapZones.resolve(TapLayout.Default, TapInvert.Both, rtl, 0.9f, 0.5f))
        // Left to right: as drawn.
        assertEquals(TapAction.Previous, TapZones.resolve(TapLayout.Default, TapInvert.None, ReadingMode.PagedLeftToRight, 0.1f, 0.5f))
    }

    @Test
    fun stripsUseTheLShapeByDefault() {
        val strip = ReadingMode.LongStrip
        assertEquals(TapAction.Next, TapZones.resolve(TapLayout.Default, TapInvert.None, strip, 0.5f, 0.9f))
        assertEquals(TapAction.Previous, TapZones.resolve(TapLayout.Default, TapInvert.None, strip, 0.5f, 0.1f))
        assertEquals(TapAction.Menu, TapZones.resolve(TapLayout.Default, TapInvert.None, strip, 0.5f, 0.5f))
        assertEquals(TapAction.Previous, TapZones.resolve(TapLayout.Default, TapInvert.None, strip, 0.1f, 0.5f))
        assertEquals(TapAction.Next, TapZones.resolve(TapLayout.Default, TapInvert.None, strip, 0.9f, 0.5f))
        // Vertical invert: the bottom goes back.
        assertEquals(TapAction.Previous, TapZones.resolve(TapLayout.Default, TapInvert.Vertical, strip, 0.5f, 0.9f))
        assertEquals(TapLayout.LShaped, TapZones.effectiveLayout(TapLayout.Default, ReadingMode.PagedVertical))
        assertEquals(TapLayout.RightAndLeft, TapZones.effectiveLayout(TapLayout.Default, ReadingMode.PagedLeftToRight))
    }

    @Test
    fun edgesAndOutOfRangePointsStayOnTheGrid() {
        val mode = ReadingMode.PagedLeftToRight
        assertEquals(TapAction.Previous, TapZones.resolve(TapLayout.RightAndLeft, TapInvert.None, mode, 0f, 0f))
        assertEquals(TapAction.Next, TapZones.resolve(TapLayout.RightAndLeft, TapInvert.None, mode, 1f, 1f))
        assertEquals(TapAction.Next, TapZones.resolve(TapLayout.RightAndLeft, TapInvert.None, mode, 1.4f, -0.2f))
        assertEquals(TapAction.Menu, TapZones.resolve(TapLayout.RightAndLeft, TapInvert.None, mode, 0.34f, 0.5f))
        assertEquals(TapAction.Previous, TapZones.resolve(TapLayout.RightAndLeft, TapInvert.None, mode, 0.33f, 0.5f))
    }

    @Test
    fun overlayMergesCells() {
        val third = 1 / 3f
        val twoThirds = 2 / 3f
        val rightAndLeft = TapZones.map(TapLayout.RightAndLeft, TapInvert.None, ReadingMode.PagedLeftToRight)
        assertEquals(
            listOf(
                ZoneRect(TapAction.Previous, 0f, 0f, third, 1f),
                ZoneRect(TapAction.Menu, third, 0f, twoThirds, 1f),
                ZoneRect(TapAction.Next, twoThirds, 0f, 1f, 1f),
            ),
            rightAndLeft.rects,
        )
        assertEquals(ZoneLabel(TapAction.Previous, third / 2, 0.5f), rightAndLeft.labels.first { it.action == TapAction.Previous })

        val lShaped = TapZones.map(TapLayout.LShaped, TapInvert.None, ReadingMode.LongStrip)
        assertEquals(
            listOf(
                ZoneRect(TapAction.Previous, 0f, 0f, 1f, third),
                ZoneRect(TapAction.Previous, 0f, third, third, twoThirds),
                ZoneRect(TapAction.Menu, third, third, twoThirds, twoThirds),
                ZoneRect(TapAction.Next, twoThirds, third, 1f, twoThirds),
                ZoneRect(TapAction.Next, 0f, twoThirds, 1f, 1f),
            ),
            lShaped.rects,
        )
        // Labels sit in the largest rectangle of each action.
        assertEquals(ZoneLabel(TapAction.Next, 0.5f, (twoThirds + 1f) / 2), lShaped.labels.first { it.action == TapAction.Next })
        assertEquals(1, TapZones.map(TapLayout.Disabled, TapInvert.Both, ReadingMode.LongStrip).rects.size)
    }

    @Test
    fun overlayCoversTheWholeAreaWithTheResolvedActions() {
        for (layout in TapLayout.entries) {
            for (invert in TapInvert.entries) {
                for (mode in ReadingMode.comicModes) {
                    val map = TapZones.map(layout, invert, mode)
                    val area = map.rects.sumOf { ((it.right - it.left) * (it.bottom - it.top)).toDouble() }
                    assertEquals(1.0, area, 1e-5, "$layout $invert $mode")
                    for (rect in map.rects) {
                        val x = (rect.left + rect.right) / 2
                        val y = (rect.top + rect.bottom) / 2
                        assertEquals(TapZones.resolve(layout, invert, mode, x, y), rect.action)
                    }
                }
            }
        }
    }
}
