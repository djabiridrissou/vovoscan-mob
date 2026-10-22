package com.vovoscan.feature.reader.domain

/**
 * One screen of a paged reader: one item, or two pages side by side ([second], the page after [first] in reading
 * order). Indices are positions in the reader's items (chapter cards and pages).
 */
data class ReaderSpread(
    val first: Int,
    val second: Int? = null,
) {
    val last: Int get() = second ?: first

    val double: Boolean get() = second != null

    operator fun contains(item: Int): Boolean = item == first || item == second
}

/** What an item is for pairing: a portrait page pairs, anything else (a chapter card, a wide page) stands alone. */
enum class SpreadSlot { Page, Alone }

/**
 * Two pages side by side, as a printed manga or comic is read open (iPad in landscape, an unfolded foldable, a book
 * posture). Only for horizontal paged modes; the native side lays a spread's pages in reading order (right to left:
 * [ReaderSpread.first] on the right).
 */
object Spreads {
    /** Narrower than a small tablet, a page pair is too small to read. */
    const val MIN_WIDTH = 600f

    /** Width / height of the reading area from which two portrait pages fit better than one. */
    const val MIN_ASPECT = 1.15f

    /**
     * Whether the reading area takes two pages: wide enough, or a foldable's vertical hinge splitting it in two
     * (book posture, or flat with a separating hinge), whatever its shape.
     */
    fun fit(
        width: Float,
        height: Float,
        verticalHinge: Boolean = false,
    ): Boolean = verticalHinge || (width >= MIN_WIDTH && height > 0f && width / height >= MIN_ASPECT)

    /**
     * The screens, in order. With [paired], the first page (the cover) stands alone, then pages go by two; a card
     * or a wide page (already a double spread) stands alone and the pairing starts again after it, so facing pages
     * stay together. Without, one item per screen.
     */
    fun of(
        slots: List<SpreadSlot>,
        paired: Boolean,
    ): List<ReaderSpread> {
        if (!paired) return slots.indices.map { ReaderSpread(it) }
        val spreads = ArrayList<ReaderSpread>(slots.size)
        var firstPage = true
        var i = 0
        while (i < slots.size) {
            val pairs = slots[i] == SpreadSlot.Page && !firstPage && slots.getOrNull(i + 1) == SpreadSlot.Page
            if (slots[i] == SpreadSlot.Page) firstPage = false
            if (pairs) {
                spreads += ReaderSpread(i, i + 1)
                i += 2
            } else {
                spreads += ReaderSpread(i)
                i += 1
            }
        }
        return spreads
    }

    /** The screen showing [item] (0 when out of range). */
    fun indexOf(
        spreads: List<ReaderSpread>,
        item: Int,
    ): Int = spreads.indexOfFirst { item in it }.coerceAtLeast(0)

    /** A page whose shape is known and wider than tall: a double spread on its own. Unknown sizes pair. */
    fun slotOf(page: ReaderPage): SpreadSlot = if (page.hasSize && page.width > page.height) SpreadSlot.Alone else SpreadSlot.Page
}

/** A hardware key in the reader (iPad keyboard, a Chromebook or a foldable's keyboard, a remote). */
enum class ReaderKey { Left, Right, Up, Down, Space, ShiftSpace, PageUp, PageDown, Escape }

enum class KeyAction { Next, Previous, Close }

/**
 * Keys follow the page direction: in a right-to-left manga the left arrow goes forward (the next page is on the
 * left), in a left-to-right comic the right arrow does; up and down, space, page up and down always go back and
 * forward. Escape closes the reader.
 */
object ReaderKeys {
    fun action(
        key: ReaderKey,
        mode: ReadingMode,
    ): KeyAction? =
        when (key) {
            ReaderKey.Escape -> KeyAction.Close
            ReaderKey.Down, ReaderKey.Space, ReaderKey.PageDown -> KeyAction.Next
            ReaderKey.Up, ReaderKey.ShiftSpace, ReaderKey.PageUp -> KeyAction.Previous
            ReaderKey.Left -> if (mode == ReadingMode.PagedRightToLeft) KeyAction.Next else KeyAction.Previous
            ReaderKey.Right -> if (mode == ReadingMode.PagedRightToLeft) KeyAction.Previous else KeyAction.Next
        }
}
