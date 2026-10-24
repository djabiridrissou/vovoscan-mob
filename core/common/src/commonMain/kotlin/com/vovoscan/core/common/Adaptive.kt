package com.vovoscan.core.common

/** Width classes, at Material's breakpoints (which match the iPad's: a phone, a tablet upright or unfolded, wider). */
enum class WidthClass { Compact, Medium, Expanded }

/** Where the app's sections are: a bottom bar (phones), a rail (medium), a sidebar (expanded). */
enum class Navigation { BottomBar, Rail, Sidebar }

/** How a foldable is held: flat (or no fold), half open like a book (vertical hinge), or like a laptop (horizontal). */
enum class Posture { Flat, Book, Tabletop }

/**
 * A foldable's hinge, in the window's coordinates (dp / pt): [vertical] when it runs top to bottom, [start] and [end]
 * its span across (equal for a seamless fold), [separating] when the system says content should not cross it (a
 * physical gap, or any fold while half open), [halfOpened] when the device is folded partway.
 */
data class Hinge(
    val vertical: Boolean,
    val start: Float,
    val end: Float,
    val separating: Boolean,
    val halfOpened: Boolean,
)

/**
 * The app's layout for a window, decided once here for both platforms (window size and fold, never the device's
 * name). The native side measures the window, reads the hinge (Android's WindowManager; none on iPad) and lays out
 * what this says.
 */
data class AdaptiveLayout(
    val widthClass: WidthClass,
    val navigation: Navigation,
    /** Catalog and library covers: the smallest tile width; the grid fits as many columns as it can. */
    val minTileWidth: Float,
    /** Forms and lists that read best narrow (settings, account, sources): their widest. */
    val readableWidth: Float,
    /** Title page: the cover and synopsis beside the chapters instead of above them. */
    val twoPane: Boolean,
    /** Where the left pane ends: at the hinge when it separates the window, else a fixed width. */
    val paneSplit: Float,
    /** The gap to leave where a hinge separates the two panes (0 without one). */
    val hingeGap: Float,
    val posture: Posture,
    /** Paged reader: the reading area is split by a vertical hinge, so two pages sit one on each side. */
    val verticalHinge: Boolean,
) {
    /** Episode player held like a laptop: the picture above the fold, the controls below it. */
    val tabletop: Boolean get() = posture == Posture.Tabletop
}

object Adaptive {
    const val MEDIUM = 600f
    const val EXPANDED = 840f
    const val READABLE = 720f
    const val PANE = 360f

    fun widthClass(width: Float): WidthClass =
        when {
            width < MEDIUM -> WidthClass.Compact
            width < EXPANDED -> WidthClass.Medium
            else -> WidthClass.Expanded
        }

    fun of(
        width: Float,
        height: Float,
        hinge: Hinge? = null,
    ): AdaptiveLayout {
        val widthClass = widthClass(width)
        val posture =
            when {
                hinge == null || !hinge.halfOpened -> Posture.Flat
                hinge.vertical -> Posture.Book
                else -> Posture.Tabletop
            }
        // A vertical hinge that separates (a gap, or half open) splits the window: each pane keeps to its side.
        val splitting = hinge != null && hinge.vertical && hinge.separating && hinge.start > 0f && hinge.end < width
        val twoPane = splitting || width >= EXPANDED || (widthClass == WidthClass.Medium && width > height)
        return AdaptiveLayout(
            widthClass = widthClass,
            navigation =
                when (widthClass) {
                    WidthClass.Compact -> Navigation.BottomBar
                    WidthClass.Medium -> Navigation.Rail
                    WidthClass.Expanded -> Navigation.Sidebar
                },
            minTileWidth =
                when (widthClass) {
                    WidthClass.Compact -> 150f
                    WidthClass.Medium -> 170f
                    WidthClass.Expanded -> 180f
                },
            readableWidth = READABLE,
            twoPane = twoPane,
            paneSplit = if (splitting) hinge.start else minOf(PANE, width * 0.42f),
            hingeGap = if (splitting) hinge.end - hinge.start else 0f,
            posture = posture,
            verticalHinge = splitting,
        )
    }
}
