package com.vovoscan.feature.reader.domain

import kotlin.math.max

/** Where "Continue reading" opens a strip, and the panel to mark there. */
data class ResumeTarget(
    val offset: Float,
    /** The panel the eye was on (marked for a moment), or none at the very start. */
    val mark: PanelBox?,
)

/**
 * "Continue reading" lands where the eye was, then backs off a little so the thread is picked up again (reader SPEC
 * §9, books contract §4: the comic counterpart of `RESUME_LINES`). The place saved is the reading line
 * ([READING_LINE] of the screen down) as a share of its page, so it holds on another screen or device; the stop whose
 * panel holds that line is the anchor, and the reader opens at the stop before it.
 */
object Resume {
    /** The reading line, as a share of the screen from its top. */
    const val READING_LINE = 1f / 3f

    /** Without panels, or when the stop before is more than a screen up: back by this share of the screen. */
    const val BACK_SCREEN = 1f / 3f

    /** How long the anchor panel stays marked (a soft spotlight, fading out). */
    const val MARK_MS = 1200L

    /** The reading line at [line] (from the first page's top) on a screen [viewport] high. */
    fun target(
        stops: List<Stop>,
        layout: StripLayout,
        line: Float,
        viewport: Float,
    ): ResumeTarget {
        val top = line - READING_LINE * viewport
        if (top <= ReadingStops.SAME_PX) return ResumeTarget(0f, null)
        val fallback = max(0f, top - BACK_SCREEN * viewport)
        val anchor = stops.indexOfLast { panelTop(it.panel, layout) <= line + ReadingStops.SAME_PX }
        if (anchor < 0) return ResumeTarget(fallback, null)
        // One panel back: the closest stop that scrolls above the anchor's (panels side by side share a scroll).
        val start = stops[anchor].offset
        val before = stops.subList(0, anchor).lastOrNull { it.offset < start - ReadingStops.SAME_PX }
        val offset = if (before != null && top - before.offset <= viewport) before.offset else fallback
        return ResumeTarget(offset, stops[anchor].panel)
    }

    private fun panelTop(
        panel: PanelBox,
        layout: StripLayout,
    ): Float = layout.tops.getOrElse(panel.page) { 0f } + panel.y * layout.heights.getOrElse(panel.page) { 0f }
}
