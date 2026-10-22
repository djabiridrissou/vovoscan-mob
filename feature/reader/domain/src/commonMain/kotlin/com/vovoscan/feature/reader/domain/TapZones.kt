package com.vovoscan.feature.reader.domain

/** What a single tap on the reading area does. */
enum class TapAction {
    Previous,
    Next,
    Menu,
}

/**
 * Where the tap zones are, on a 3 × 3 grid of the reading area (columns and rows are thirds), as in Mihon.
 * `Default` depends on the mode: Right and left for horizontal pages, L-shaped for vertical pages and strips.
 */
enum class TapLayout {
    Default,
    RightAndLeft,
    LShaped,
    Kindle,
    Edge,
    Disabled,
}

/** Swaps the zones: left and right, top and bottom, or both. */
enum class TapInvert(
    val horizontal: Boolean,
    val vertical: Boolean,
) {
    None(false, false),
    Horizontal(true, false),
    Vertical(false, true),
    Both(true, true),
}

/** A rectangle of the reading area, in fractions of its size (0..1, origin top left). */
data class ZoneRect(
    val action: TapAction,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Where a zone's name is written on the overlay (centre of its largest rectangle). */
data class ZoneLabel(
    val action: TapAction,
    val x: Float,
    val y: Float,
)

/** What the "tap zones" overlay draws: merged rectangles and one label per action. */
data class TapZoneMap(
    val rects: List<ZoneRect>,
    val labels: List<ZoneLabel>,
)

/**
 * The tap zones. Pure: the native readers send where the finger went up (normalized 0..1 of the reading area)
 * and do what comes back.
 */
object TapZones {
    private val P = TapAction.Previous
    private val N = TapAction.Next
    private val M = TapAction.Menu

    /** Rows top to bottom, columns left to right. */
    fun grid(layout: TapLayout): List<List<TapAction>> =
        when (layout) {
            TapLayout.Default, TapLayout.RightAndLeft -> listOf(listOf(P, M, N), listOf(P, M, N), listOf(P, M, N))
            TapLayout.LShaped -> listOf(listOf(P, P, P), listOf(P, M, N), listOf(N, N, N))
            TapLayout.Kindle -> listOf(listOf(M, M, M), listOf(P, N, N), listOf(P, N, N))
            TapLayout.Edge -> listOf(listOf(N, M, N), listOf(N, M, N), listOf(N, P, N))
            TapLayout.Disabled -> listOf(listOf(M, M, M), listOf(M, M, M), listOf(M, M, M))
        }

    /** `Default` made concrete for a mode. */
    fun effectiveLayout(
        layout: TapLayout,
        mode: ReadingMode,
    ): TapLayout =
        when {
            layout != TapLayout.Default -> layout
            mode.isHorizontal -> TapLayout.RightAndLeft
            else -> TapLayout.LShaped
        }

    /**
     * The grid as it acts in this mode: right-to-left pages mirror the sides (tapping the left side advances, as
     * in a right-to-left book); an explicit horizontal invert flips that again.
     */
    fun effectiveGrid(
        layout: TapLayout,
        invert: TapInvert,
        mode: ReadingMode,
    ): List<List<TapAction>> {
        val mirrorColumns = invert.horizontal xor (mode == ReadingMode.PagedRightToLeft)
        val rows = grid(effectiveLayout(layout, mode)).let { if (invert.vertical) it.reversed() else it }
        return if (mirrorColumns) rows.map { it.reversed() } else rows
    }

    fun resolve(
        layout: TapLayout,
        invert: TapInvert,
        mode: ReadingMode,
        x: Float,
        y: Float,
    ): TapAction {
        val column = cell(x)
        val row = cell(y)
        return effectiveGrid(layout, invert, mode)[row][column]
    }

    /** The overlay: same-action cells merged into rectangles (rows first, then identical spans down). */
    fun map(
        layout: TapLayout,
        invert: TapInvert,
        mode: ReadingMode,
    ): TapZoneMap {
        val grid = effectiveGrid(layout, invert, mode)
        // Horizontal runs per row: (row, startColumn, endColumnExclusive, action).
        val runs = mutableListOf<IntArray>()
        val actions = mutableListOf<TapAction>()
        grid.forEachIndexed { row, cells ->
            var start = 0
            for (column in 1..cells.size) {
                if (column == cells.size || cells[column] != cells[start]) {
                    runs += intArrayOf(row, start, column)
                    actions += cells[start]
                    start = column
                }
            }
        }
        // Merge a run with the identical runs right below it.
        val used = BooleanArray(runs.size)
        val rects = mutableListOf<ZoneRect>()
        for (i in runs.indices) {
            if (used[i]) continue
            used[i] = true
            val (row, start, end) = runs[i].let { Triple(it[0], it[1], it[2]) }
            var bottom = row + 1
            while (true) {
                val below =
                    runs.indices.firstOrNull { j ->
                        !used[j] &&
                            runs[j][0] == bottom &&
                            runs[j][1] == start &&
                            runs[j][2] == end &&
                            actions[j] == actions[i]
                    }
                        ?: break
                used[below] = true
                bottom++
            }
            rects += ZoneRect(actions[i], start / 3f, row / 3f, end / 3f, bottom / 3f)
        }
        val labels =
            TapAction.entries.mapNotNull { action ->
                rects
                    .filter { it.action == action }
                    .maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                    ?.let { ZoneLabel(action, (it.left + it.right) / 2, (it.top + it.bottom) / 2) }
            }
        return TapZoneMap(rects, labels)
    }

    private fun cell(value: Float): Int = (value.coerceIn(0f, 1f) * 3).toInt().coerceAtMost(2)
}
