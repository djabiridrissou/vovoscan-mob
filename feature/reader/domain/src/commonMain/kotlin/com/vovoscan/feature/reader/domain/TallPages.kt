package com.vovoscan.feature.reader.domain

/**
 * Tall pages in the paged modes (a webtoon strip of 800 × 14 000 px), as Mihon's paged viewer: a page clearly
 * taller than the reading area is shown fit to width and scrolls vertically inside its page; the next page comes
 * after its bottom. Other pages are fitted to the screen. Pure: the native readers ask and do what comes back.
 */
object TallPages {
    /**
     * Fit to width only when the page would be at least this much taller than the reading area: a page a few
     * pixels taller than the screen reads better whole than scrolled by a few pixels.
     */
    const val MARGIN = 1.25f

    /**
     * Within a gesture that left the page, how far into the next (or previous) page the scroll must come to rest
     * (where the finger let go, or where the fling would carry it) to move there, as a fraction of the reading
     * area's height. Less: back to the page's edge, as a pager does.
     */
    const val TURN_FRACTION = 0.5f

    /** Within this distance (points or pixels) of a page's edge, the scroll is at that edge (layout rounding). */
    const val EDGE = 1f

    /**
     * Whether a page of this width / height ratio is shown fit to width and scrolled inside its page: when it is
     * clearly taller than the screen held upright (its long side over its short side), whichever way the device is
     * held. Judged against the reading area's own shape, a manga page turned "tall" in landscape (fit to the width,
     * it is taller than the screen) and had to be scrolled: it stays a whole page, and only strip-like pages scroll.
     * On an area that takes two pages (`Spreads.fit`: a tablet or an unfolded foldable, wider than tall) a page is
     * never cropped: only a strip (at least [ComicFormat.STRIP_RATIO] times taller than wide) scrolls, every other page
     * fits the height.
     */
    fun fitsWidth(
        pageRatio: Float,
        areaWidth: Float,
        areaHeight: Float,
    ): Boolean {
        if (pageRatio <= 0f || areaWidth <= 0f || areaHeight <= 0f) return false
        val portrait = maxOf(areaWidth, areaHeight) / minOf(areaWidth, areaHeight)
        return 1f / pageRatio > portrait * MARGIN &&
            (!Spreads.fit(areaWidth, areaHeight) || 1f / pageRatio >= ComicFormat.STRIP_RATIO)
    }

    /** The page's height in the reading area: its fit-width height when tall, else the area's height. */
    fun height(
        pageRatio: Float,
        areaWidth: Float,
        areaHeight: Float,
    ): Float = if (fitsWidth(pageRatio, areaWidth, areaHeight)) areaWidth / pageRatio else areaHeight

    /**
     * iOS vertical pages are one scroll view in which a tall page takes its full height (UIKit does not chain two
     * vertical scroll views): where a drag that began on the page at [top] (of [height], in content coordinates)
     * comes to rest. [release]: the offset when the finger let go; [proposed]: where the fling would stop.
     *
     * Inside a tall page, the scroll stays inside it: a fling stops at its edge. From an edge or past it, it is a
     * page turn (a scroll to the next page starts from the page's bottom): the next page's top or the previous
     * page's bottom when the rest point is [TURN_FRACTION] of the area past the edge, else back to the edge. One
     * page at most per gesture.
     */
    fun restingOffset(
        top: Float,
        height: Float,
        viewport: Float,
        hasPrevious: Boolean,
        hasNext: Boolean,
        release: Float,
        proposed: Float,
    ): Float {
        val first = top
        val last = top + (height - viewport).coerceAtLeast(0f)
        val turn = viewport * TURN_FRACTION
        return when {
            last > first && release > first + EDGE && release < last - EDGE -> proposed.coerceIn(first, last)
            proposed > last -> if (hasNext && proposed - last >= turn) top + height.coerceAtLeast(viewport) else last
            proposed < first -> if (hasPrevious && first - proposed >= turn) first - viewport else first
            else -> proposed
        }
    }
}
