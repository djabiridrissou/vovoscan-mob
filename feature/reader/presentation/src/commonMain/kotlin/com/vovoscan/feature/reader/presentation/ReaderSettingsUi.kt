package com.vovoscan.feature.reader.presentation

import com.vovoscan.feature.reader.domain.ComicFormat
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ScrollAmount
import com.vovoscan.feature.reader.domain.TapAction
import com.vovoscan.feature.reader.domain.TapInvert
import com.vovoscan.feature.reader.domain.TapLayout
import kotlin.math.roundToInt

data class ModeOption(
    val mode: ReadingMode,
    val label: String,
    val selected: Boolean,
)

data class LayoutOption(
    val layout: TapLayout,
    val label: String,
    val selected: Boolean,
)

data class InvertOption(
    val invert: TapInvert,
    val label: String,
    val selected: Boolean,
)

data class ScrollOption(
    val amount: ScrollAmount,
    val label: String,
    val selected: Boolean,
)

/** The reader settings sheet, ready to draw: every choice with its label and whether it is the current one. */
data class ReaderSettingsSheet(
    val seriesModes: List<ModeOption>,
    /** "Default for this series: Long strip." */
    val seriesHint: String,
    /** The series has its own mode: "Reset to default" is offered. */
    val canResetSeries: Boolean,
    val mangaModes: List<ModeOption>,
    val webtoonModes: List<ModeOption>,
    val layouts: List<LayoutOption>,
    val inverts: List<InvertOption>,
    val scrollAmounts: List<ScrollOption>,
    val showTapZones: Boolean,
    val doubleTapZoom: Boolean,
    val keepAwake: Boolean,
    val volumeKeys: Boolean,
    val volumeKeysInverted: Boolean,
) {
    companion object {
        fun of(
            mode: ReadingMode,
            format: ComicFormat,
            seriesMode: ReadingMode?,
            preferences: ReaderPreferences,
        ): ReaderSettingsSheet {
            val formatDefault = preferences.defaultFor(format) ?: format.defaultMode

            fun modes(selected: ReadingMode) = ReadingMode.comicModes.map { ModeOption(it, ReaderText.mode(it), it == selected) }
            return ReaderSettingsSheet(
                seriesModes = modes(mode),
                seriesHint = "Default for this series: ${ReaderText.mode(formatDefault)}.",
                canResetSeries = seriesMode != null,
                mangaModes = modes(preferences.mangaMode ?: ComicFormat.Manga.defaultMode),
                webtoonModes = modes(preferences.webtoonMode ?: ComicFormat.Webtoon.defaultMode),
                layouts = TapLayout.entries.map { LayoutOption(it, ReaderText.layout(it), it == preferences.tapLayout) },
                inverts = TapInvert.entries.map { InvertOption(it, ReaderText.invert(it), it == preferences.tapInvert) },
                scrollAmounts = ScrollAmount.entries.map { ScrollOption(it, ReaderText.scroll(it), it == preferences.scrollAmount) },
                showTapZones = preferences.showTapZones,
                doubleTapZoom = preferences.doubleTapZoom,
                keepAwake = preferences.keepAwake,
                volumeKeys = preferences.volumeKeys,
                volumeKeysInverted = preferences.volumeKeysInverted,
            )
        }
    }
}

/** The reader's words (English, short and calm). */
object ReaderText {
    const val SETTINGS_TITLE = "Reader settings"
    const val SERIES_MODE = "Mode for this series"
    const val RESET_SERIES = "Reset to default"
    const val DEFAULT_MODES = "Default mode"
    const val MANGA = "Manga"
    const val WEBTOON = "Webtoon"
    const val TAP_ZONES = "Tap zones"
    const val INVERT = "Invert"
    const val SCROLL_AMOUNT = "Tap scroll amount"
    const val SCROLL_AMOUNT_HELP = "How far a tap moves a long strip or a tall page. The gap between pages comes with the mode."
    const val SHOW_TAP_ZONES = "Show tap zones"
    const val SHOW_TAP_ZONES_HELP = "Briefly, the first time and after a change."
    const val DOUBLE_TAP_ZOOM = "Double tap to zoom"
    const val DOUBLE_TAP_ZOOM_HELP = "Single taps then wait a moment, in case a second tap follows."
    const val KEEP_AWAKE = "Keep screen awake"
    const val KEEP_AWAKE_HELP = "While reading."
    const val VOLUME_KEYS = "Volume keys turn pages"
    const val VOLUME_KEYS_HELP = "Volume down goes forward, volume up goes back."
    const val VOLUME_KEYS_INVERTED = "Invert volume keys"

    fun mode(mode: ReadingMode): String =
        when (mode) {
            ReadingMode.PagedRightToLeft -> "Right to left"
            ReadingMode.PagedLeftToRight -> "Left to right"
            ReadingMode.PagedVertical -> "Vertical pages"
            ReadingMode.LongStrip -> "Long strip"
            ReadingMode.LongStripGaps -> "Long strip with gaps"
            ReadingMode.Book -> "Book"
        }

    fun layout(layout: TapLayout): String =
        when (layout) {
            TapLayout.Default -> "Default"
            TapLayout.RightAndLeft -> "Right and left"
            TapLayout.LShaped -> "L-shaped"
            TapLayout.Kindle -> "Kindle-ish"
            TapLayout.Edge -> "Edge"
            TapLayout.Disabled -> "Disabled"
        }

    fun invert(invert: TapInvert): String =
        when (invert) {
            TapInvert.None -> "None"
            TapInvert.Horizontal -> "Horizontal"
            TapInvert.Vertical -> "Vertical"
            TapInvert.Both -> "Both"
        }

    fun scroll(amount: ScrollAmount): String = "${(amount.fraction * 100).roundToInt()} %"

    fun action(action: TapAction): String =
        when (action) {
            TapAction.Previous -> "Previous"
            TapAction.Next -> "Next"
            TapAction.Menu -> "Menu"
        }
}
