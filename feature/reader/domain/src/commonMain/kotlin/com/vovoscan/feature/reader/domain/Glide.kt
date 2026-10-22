package com.vovoscan.feature.reader.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// The immersive reader's shared numbers and geometry (reader/SPEC.md, the same values as the web reader).

/**
 * Every page stacked at the strip's width, one under the other (`flow`: webtoon logic for every format). Units are
 * the native side's (dp / pt): it reports them, the shared code answers in them, from the first page's top.
 */
class StripLayout(
    pages: List<ReaderPage>,
    val width: Float,
    val gap: Float = 0f,
) {
    val heights: List<Float> = pages.map { width / it.aspectRatio }

    /** Top of each page, from the first page's top. */
    val tops: List<Float>
    val total: Float

    init {
        var y = 0f
        tops =
            heights.map { height ->
                val top = y
                y += height + gap
                top
            }
        total = if (heights.isEmpty()) 0f else y - gap
    }

    /** The page under [y]; clamped to the pages. */
    fun pageAt(y: Float): Int {
        if (tops.isEmpty()) return 0
        return tops.indexOfLast { it <= y }.coerceIn(0, tops.lastIndex)
    }
}

/** A panel of a page, its box as fractions of the page (0..1, origin top left). Lists of them are in reading order. */
data class PanelBox(
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    companion object {
        /** The whole page: a page with no analysis and no gutter found. */
        fun page(index: Int) = PanelBox(index, 0f, 0f, 1f, 1f)
    }
}

/** Where one "Next" goes: a scroll offset (from the first page's top) and the panel being read there. */
data class Stop(
    val offset: Float,
    val panel: PanelBox,
)

/**
 * A chapter's stops (SPEC §2), computed once per layout, never while scrolling. A panel that fits is centred, unless
 * it is already whole at the previous stop (manga panels side by side: only the spotlight moves); a taller one is read
 * a screen at a time from its top (20 % overlap), the last stop exactly at its bottom.
 */
object ReadingStops {
    const val MARGIN = 0.03f
    const val STEP = 0.8f

    /** Stops closer than this keep only the panel (for the spotlight), not a scroll. */
    const val SAME_PX = 2f

    /**
     * [panels]: per page, in reading order (the analysis, else the gutter bands, else the page). Pages missing from the
     * map are one panel.
     */
    fun of(
        layout: StripLayout,
        viewport: Float,
        panels: Map<Int, List<PanelBox>> = emptyMap(),
    ): List<Stop> {
        if (layout.tops.isEmpty() || viewport <= 0f) return emptyList()
        val margin = MARGIN * viewport
        val end = max(0f, layout.total - viewport)
        val stops = mutableListOf<Stop>()
        layout.tops.indices.forEach { page ->
            val boxes = panels[page]?.takeIf { it.isNotEmpty() } ?: listOf(PanelBox.page(page))
            boxes.forEach { panel ->
                val y0 = layout.tops[page] + panel.y * layout.heights[page]
                val y1 = y0 + panel.height * layout.heights[page]
                val previous = stops.lastOrNull()?.offset
                val offsets =
                    when {
                        y1 - y0 <= viewport - 2 * margin ->
                            if (previous != null && y0 >= previous && y1 <= previous + viewport) {
                                listOf(previous)
                            } else {
                                listOf((y0 + y1 - viewport) / 2f)
                            }
                        else -> {
                            val list = mutableListOf<Float>()
                            val last = y1 + margin - viewport
                            var at = y0 - margin
                            while (at < last - SAME_PX) {
                                list += at
                                at += STEP * viewport
                            }
                            list + last
                        }
                    }
                offsets.forEach { raw ->
                    val clamped = raw.coerceIn(0f, end)
                    val before = stops.lastOrNull()?.offset
                    // A strip reads downwards: a stop never goes back up; a near one only moves the spotlight.
                    val offset = if (before != null && clamped < before + SAME_PX) before else clamped
                    stops += Stop(offset, panel)
                }
            }
        }
        return stops
    }

    /** The stop being read at [offset]: the last one at or above the top of the screen (+ 2 px). */
    fun current(
        stops: List<Stop>,
        offset: Float,
    ): Int = stops.indexOfLast { it.offset <= offset + SAME_PX }.coerceAtLeast(0)
}

/**
 * Gutter bands found on the device when a page has no analysis (SPEC §2.2, the worker's `gutters.py` thresholds): the
 * page's luminance downscaled to 48 columns, one row per [luminance] entry (0..255 values).
 */
object Gutters {
    const val COLUMNS = 48
    private const val FLAT = 12
    private const val GUTTER = 0.02f
    private const val MIN_BAND = 0.12f

    /** The page's panels as full-width bands, top to bottom; the page itself when nothing splits it. */
    fun bands(
        page: Int,
        luminance: List<IntArray>,
    ): List<PanelBox> {
        val rows = luminance.size
        if (rows == 0) return listOf(PanelBox.page(page))
        val width = luminance.first().size.coerceAtLeast(1)
        val flat = luminance.map { row -> row.isNotEmpty() && row.max() - row.min() <= FLAT }
        val minGutter = max(1, ceil(GUTTER * width).toInt())
        // Content runs between gutters (flat runs at least `minGutter` rows long).
        val bands = mutableListOf<IntRange>()
        var start = -1
        var flatRun = 0
        for (r in 0 until rows) {
            if (flat[r]) {
                flatRun++
                if (flatRun == minGutter && start >= 0) {
                    bands += start until r - flatRun + 1
                    start = -1
                }
            } else {
                if (start < 0) start = r
                flatRun = 0
            }
        }
        if (start >= 0) bands += start until rows
        if (bands.isEmpty()) return listOf(PanelBox.page(page))
        // A band shorter than 0.12 widths joins its neighbour (the closer one; the previous on a tie).
        val minBand = MIN_BAND * width
        val merged = bands.toMutableList()
        var i = 0
        while (merged.size > 1 && i < merged.size) {
            val band = merged[i]
            if (band.last - band.first + 1 >= minBand) {
                i++
                continue
            }
            val gapBefore = if (i > 0) band.first - merged[i - 1].last else Int.MAX_VALUE
            val gapAfter = if (i < merged.lastIndex) merged[i + 1].first - band.last else Int.MAX_VALUE
            if (gapBefore <= gapAfter) {
                merged[i - 1] = merged[i - 1].first..band.last
                merged.removeAt(i)
                i = max(0, i - 1)
            } else {
                merged[i] = band.first..merged[i + 1].last
                merged.removeAt(i + 1)
            }
        }
        if (merged.size == 1) return listOf(PanelBox.page(page))
        return merged.map { band ->
            PanelBox(page, 0f, band.first.toFloat() / rows, 1f, (band.last - band.first + 1).toFloat() / rows)
        }
    }
}

/**
 * The tap scroll (SPEC §3): a critically damped spring on the scroll position, solved from the frame's time so it
 * looks the same at 60, 90 and 120 Hz. A new Next while moving retargets from the current position and velocity.
 */
object Glide {
    const val OMEGA = 13f

    /** Spotlight move between panels (focus). */
    const val FOCUS_MS = 380

    /** Light of everything but the panel being read (focus), the episode's value. */
    const val OTHER_LIGHT = 0.72f

    /** Position and velocity [seconds] after the start, from [from] at [velocity] towards [target]. */
    fun at(
        from: Float,
        velocity: Float,
        target: Float,
        seconds: Float,
    ): Pair<Float, Float> {
        val t = max(0f, seconds)
        val x0 = from - target
        val c = velocity + OMEGA * x0
        val e = exp(-OMEGA * t)
        val x = (x0 + c * t) * e
        val v = (c - OMEGA * (x0 + c * t)) * e
        return (target + x) to v
    }

    /** Close enough to stop the frame loop and land exactly on the target. */
    fun settled(
        position: Float,
        velocity: Float,
        target: Float,
    ): Boolean = abs(position - target) < 0.5f && abs(velocity) < 5f

    /** Seconds until settled (for tests and a frame loop's safety cap). */
    fun settleSeconds(
        distance: Float,
        velocity: Float = 0f,
    ): Float {
        var t = 0f
        while (t < 3f) {
            val (x, v) = at(distance, velocity, 0f, t)
            if (settled(x, v, 0f)) return t
            t += 1f / 240f
        }
        return min(t, 3f)
    }
}
