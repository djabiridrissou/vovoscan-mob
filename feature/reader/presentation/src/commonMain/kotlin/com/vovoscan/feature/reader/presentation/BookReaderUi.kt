package com.vovoscan.feature.reader.presentation

import com.vovoscan.feature.reader.domain.BookFont
import com.vovoscan.feature.reader.domain.BookLineSpacing
import com.vovoscan.feature.reader.domain.BookMargins
import com.vovoscan.feature.reader.domain.BookSection
import com.vovoscan.feature.reader.domain.BookTheme
import com.vovoscan.feature.reader.domain.BookTypography
import com.vovoscan.feature.reader.domain.DownloadableChapter
import com.vovoscan.feature.reader.domain.TocEntry

/**
 * Where the section on screen must be shown, once: after it has loaded and laid out (a section of several MB takes a
 * moment), or at once when the section is already there (a contents entry inside it). The native side applies each
 * [seq] once, only to the section it was made for ([section]), and stops as soon as the reader touches the page.
 * Nothing set: its top.
 */
data class SectionOpening(
    val seq: Int,
    val section: Int,
    /** The top of the screen at this fraction of the section's height (reopening the book, a typography change). */
    val fraction: Float? = null,
    /** This element (an id in the section) at the top of the screen: a contents entry inside the section. */
    val anchor: String? = null,
    /** Its end (reached going back). */
    val fromEnd: Boolean = false,
)

/** A row of the contents sheet: a part of the book, a section, or an entry of the book's own table of contents. */
data class ContentsRow(
    /** Stable key for lists, sent back with `BookIntent.ChooseContents`. */
    val key: String,
    val label: String,
    /** Indentation: 0 for the top level. */
    val depth: Int,
    /** In the section being read (or the part being read, for a part's row). */
    val current: Boolean,
    /** A part of a book in several parts (a chapter of the title). */
    val part: Boolean,
    /** Tapping it opens something (the part being read has nothing to open). */
    val enabled: Boolean,
)

data class TextSizeStep(
    /** "100 %": on top of the device's text size. */
    val label: String,
    val canShrink: Boolean,
    val canGrow: Boolean,
    /** Not at 100 %: "Reset" is offered. */
    val canReset: Boolean,
)

data class SpacingOption(
    val spacing: BookLineSpacing,
    val label: String,
    val selected: Boolean,
)

data class MarginOption(
    val margins: BookMargins,
    val label: String,
    val selected: Boolean,
)

data class FontOption(
    val font: BookFont,
    val label: String,
    val selected: Boolean,
)

data class ThemeOption(
    val theme: BookTheme,
    val label: String,
    val selected: Boolean,
    /** The page's colours, for the swatch (`0xRRGGBB`). */
    val background: Long,
    val text: Long,
)

/** The book settings sheet (gear in the top bar), ready to draw. */
data class BookSettingsSheet(
    val textSize: TextSizeStep,
    val lineSpacings: List<SpacingOption>,
    val margins: List<MarginOption>,
    val fonts: List<FontOption>,
    val justify: Boolean,
    val themes: List<ThemeOption>,
) {
    companion object {
        fun of(typography: BookTypography): BookSettingsSheet =
            BookSettingsSheet(
                textSize =
                    TextSizeStep(
                        "${typography.textPercent} %",
                        typography.canShrink,
                        typography.canGrow,
                        typography.textPercent != BookTypography().textPercent,
                    ),
                lineSpacings = BookLineSpacing.entries.map { SpacingOption(it, BookText.spacing(it), it == typography.lineSpacing) },
                margins = BookMargins.entries.map { MarginOption(it, BookText.margins(it), it == typography.margins) },
                fonts = BookFont.entries.map { FontOption(it, BookText.font(it), it == typography.font) },
                justify = typography.justify,
                themes =
                    BookTheme.entries.map {
                        val palette = BookPalette.of(it)
                        ThemeOption(
                            it,
                            BookText.theme(it),
                            it == typography.theme,
                            palette.background,
                            palette.text.removePrefix("#").toLong(16),
                        )
                    },
            )
    }
}

/** What the book reader adds to `ReaderUiState`: the page's settings, the contents, how to open the section. */
data class BookReaderState(
    val opening: SectionOpening? = null,
    /** The web view's colour (`0xRRGGBB`), the page's. */
    val pageBackground: Long = BookPalette.of(BookTheme.Dark).background,
    val settings: BookSettingsSheet = BookSettingsSheet.of(BookTypography()),
    val contents: List<ContentsRow> = emptyList(),
)

/** Book intents: typography, the position inside the section, the contents. */
sealed interface BookIntent : ReaderIntent {
    /**
     * The device's text size as a scale of its default (Android font scale, iOS Dynamic Type for body text): the
     * page's text follows it. Sent when the reader opens and whenever it changes.
     */
    data class SystemTextScale(
        val scale: Float,
    ) : BookIntent

    data object TextSmaller : BookIntent

    data object TextBigger : BookIntent

    data object ResetTextSize : BookIntent

    data class SetLineSpacing(
        val spacing: BookLineSpacing,
    ) : BookIntent

    data class SetMargins(
        val margins: BookMargins,
    ) : BookIntent

    data class SetFont(
        val font: BookFont,
    ) : BookIntent

    data class SetJustify(
        val justify: Boolean,
    ) : BookIntent

    data class SetTheme(
        val theme: BookTheme,
    ) : BookIntent

    /**
     * The section [section] scrolled: the top of the screen is at [fraction] of its height. Sent while the reader
     * scrolls, never while an opening is being applied (the section is not where it will be yet).
     */
    data class SectionScrolled(
        val section: Int,
        val fraction: Float,
    ) : BookIntent

    /** A row of the contents sheet was tapped ([ContentsRow.key]). */
    data class ChooseContents(
        val key: String,
    ) : BookIntent
}

/** The contents sheet's rows, in reading order (pure: tested on its own). */
object BookContents {
    private const val MAX_DEPTH = 3

    /** Where a row leads. */
    internal sealed interface Target {
        data class Section(
            val index: Int,
            val anchor: String?,
        ) : Target

        data class Part(
            val chapterId: String,
        ) : Target
    }

    /**
     * The book's table of contents ([toc]; the sections themselves for a book opened before it was kept), each
     * section in reading order: a section the table leaves out keeps its own row. A book in several [parts] (the
     * title's chapters, in reading order) lists them, the part being read ([chapterId]) holding its rows.
     */
    fun rows(
        sections: List<BookSection>,
        toc: List<TocEntry>,
        current: Int,
        chapterId: String,
        parts: List<DownloadableChapter>,
    ): List<ContentsRow> {
        val own = sectionRows(sections, toc, current)
        if (parts.size < 2 || parts.none { it.id == chapterId }) return own
        return parts.flatMap { part ->
            val here = part.id == chapterId
            val row = ContentsRow("p:${part.id}", part.label, 0, current = here, part = true, enabled = !here)
            if (here) listOf(row) + own.map { it.copy(depth = (it.depth + 1).coerceAtMost(MAX_DEPTH)) } else listOf(row)
        }
    }

    private fun sectionRows(
        sections: List<BookSection>,
        toc: List<TocEntry>,
        current: Int,
    ): List<ContentsRow> {
        val bySection = toc.withIndex().groupBy { it.value.section }
        val top = toc.minOfOrNull { it.depth } ?: 0
        return sections.flatMap { section ->
            val entries = bySection[section.index]
            if (entries.isNullOrEmpty()) {
                listOf(ContentsRow("s:${section.index}", section.title, 0, section.index == current, part = false, enabled = true))
            } else {
                entries.map { (i, entry) ->
                    ContentsRow("e:$i", entry.title, (entry.depth - top).coerceIn(0, MAX_DEPTH), entry.section == current, false, true)
                }
            }
        }
    }

    /** What a row's key opens. */
    internal fun target(
        key: String,
        toc: List<TocEntry>,
    ): Target? {
        val value = key.substringAfter(':')
        return when (key.substringBefore(':')) {
            "s" -> value.toIntOrNull()?.let { Target.Section(it, null) }
            "e" -> value.toIntOrNull()?.let(toc::getOrNull)?.let { Target.Section(it.section, it.anchor) }
            "p" -> Target.Part(value)
            else -> null
        }
    }
}

/** The book reader's words (English, short and calm). */
object BookText {
    const val SETTINGS_TITLE = "Book settings"
    const val TEXT_SIZE = "Text size"
    const val TEXT_SIZE_HELP = "Follows your device's text size, then this."
    const val RESET = "Reset"
    const val SMALLER = "Smaller text"
    const val BIGGER = "Larger text"
    const val LINE_SPACING = "Line spacing"
    const val MARGINS = "Margins"
    const val FONT = "Font"
    const val JUSTIFY = "Justify text"
    const val JUSTIFY_HELP = "Straight edges on both sides."
    const val THEME = "Page"
    const val THEME_HELP = "For the book's pages only."
    const val CONTENTS = "Contents"
    const val CONTENTS_BUTTON = "Contents"

    fun spacing(spacing: BookLineSpacing): String =
        when (spacing) {
            BookLineSpacing.Compact -> "Compact"
            BookLineSpacing.Normal -> "Normal"
            BookLineSpacing.Relaxed -> "Relaxed"
        }

    fun margins(margins: BookMargins): String =
        when (margins) {
            BookMargins.Narrow -> "Narrow"
            BookMargins.Normal -> "Normal"
            BookMargins.Wide -> "Wide"
        }

    fun font(font: BookFont): String =
        when (font) {
            BookFont.Serif -> "Serif"
            BookFont.Sans -> "Sans"
        }

    fun theme(theme: BookTheme): String =
        when (theme) {
            BookTheme.Dark -> "Dark"
            BookTheme.Sepia -> "Sepia"
            BookTheme.Light -> "Light"
        }
}

/**
 * Where an element (by id) sits in a section, estimated without laying it out: the share of the page's text that comes
 * before it (markup and inlined images don't count). Android opens a contents entry inside a section there: its web
 * view has no way, without JavaScript, to jump to an element that is sure not to leave the page (iOS jumps to the
 * element itself). Null when the page holds no such id.
 */
object BookAnchors {
    fun textFraction(
        html: String,
        anchor: String,
    ): Float? {
        val start = html.indexOf("<body").takeIf { it >= 0 } ?: 0
        val at =
            listOf("id=\"$anchor\"", "id='$anchor'")
                .map { html.indexOf(it, start) }
                .filter { it >= 0 }
                .minOrNull() ?: return null
        // The tag holding the id starts at the last '<' before it.
        val tag = html.lastIndexOf('<', at).coerceAtLeast(start)
        val before = textLength(html, start, tag)
        val total = before + textLength(html, tag, html.length)
        return if (total == 0) 0f else before.toFloat() / total
    }

    private fun textLength(
        html: String,
        from: Int,
        to: Int,
    ): Int {
        var count = 0
        var inTag = false
        for (i in from until to) {
            when (html[i]) {
                '<' -> inTag = true
                '>' -> inTag = false
                else -> if (!inTag && !html[i].isWhitespace()) count++
            }
        }
        return count
    }
}
