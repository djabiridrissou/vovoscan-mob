package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.i18n.t
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
    /** "Default for this series: Continuous." */
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

            fun modes(
                selected: ReadingMode,
                stripOnly: Boolean = false,
            ) = ReadingMode.comicModes
                .filter { !stripOnly || it.isStrip }
                .map { ModeOption(it, ReaderText.mode(it), it == selected) }
            // A webtoon only scrolls (reader/SPEC.md §1b): its choices are with or without gaps.
            val webtoon = format == ComicFormat.Webtoon
            return ReaderSettingsSheet(
                seriesModes = modes(mode, stripOnly = webtoon),
                seriesHint = t("reader.seriesHint", "mode" to ReaderText.mode(formatDefault)),
                canResetSeries = seriesMode != null,
                mangaModes = modes(preferences.mangaMode ?: ComicFormat.Manga.defaultMode),
                webtoonModes = modes(preferences.webtoonMode ?: ComicFormat.Webtoon.defaultMode, stripOnly = true),
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
    val SETTINGS_TITLE: String get() = t("reader.settingsTitle")
    val SERIES_MODE: String get() = t("reader.seriesMode")
    val RESET_SERIES: String get() = t("reader.resetSeries")
    val DEFAULT_MODES: String get() = t("reader.defaultModes")
    val MANGA: String get() = t("reader.manga")
    val WEBTOON: String get() = t("reader.webtoon")
    val TAP_ZONES: String get() = t("reader.tapZones")
    val INVERT: String get() = t("reader.invert")
    val SCROLL_AMOUNT: String get() = t("reader.scrollAmount")
    val SCROLL_AMOUNT_HELP: String get() = t("reader.scrollAmountHelp")
    val SHOW_TAP_ZONES: String get() = t("reader.showTapZones")
    val SHOW_TAP_ZONES_HELP: String get() = t("reader.showTapZonesHelp")
    val DOUBLE_TAP_ZOOM: String get() = t("reader.doubleTapZoom")
    val DOUBLE_TAP_ZOOM_HELP: String get() = t("reader.doubleTapZoomHelp")
    val KEEP_AWAKE: String get() = t("reader.keepAwake")
    val KEEP_AWAKE_HELP: String get() = t("reader.keepAwakeHelp")
    val VOLUME_KEYS: String get() = t("reader.volumeKeys")
    val VOLUME_KEYS_HELP: String get() = t("reader.volumeKeysHelp")
    val VOLUME_KEYS_INVERTED: String get() = t("reader.volumeKeysInverted")

    fun mode(mode: ReadingMode): String =
        when (mode) {
            ReadingMode.PagedRightToLeft -> t("reader.modeRtl")
            ReadingMode.PagedLeftToRight -> t("reader.modeLtr")
            ReadingMode.PagedVertical -> t("reader.modeVertical")
            ReadingMode.LongStrip -> t("reader.modeStrip")
            ReadingMode.LongStripGaps -> t("reader.modeStripGaps")
            ReadingMode.Book -> t("reader.modeBook")
        }

    /** "Webtoon ✦ top to bottom": the format recognised and the way it is read now. */
    fun format(
        format: ComicFormat,
        mode: ReadingMode,
    ): String {
        val name =
            when (format) {
                ComicFormat.Manga -> t("reader.manga")
                ComicFormat.Webtoon -> t("reader.webtoon")
                ComicFormat.Comic -> t("reader.comic")
            }
        val flow =
            when (mode) {
                ReadingMode.PagedRightToLeft -> t("reader.flowRtl")
                ReadingMode.PagedLeftToRight -> t("reader.flowLtr")
                else -> t("reader.flowDown")
            }
        return t("reader.formatLine", "format" to name, "flow" to flow)
    }

    fun layout(layout: TapLayout): String =
        when (layout) {
            TapLayout.Default -> t("reader.layoutDefault")
            TapLayout.RightAndLeft -> t("reader.layoutRightAndLeft")
            TapLayout.LShaped -> t("reader.layoutLShaped")
            TapLayout.Kindle -> t("reader.layoutKindle")
            TapLayout.Edge -> t("reader.layoutEdge")
            TapLayout.Disabled -> t("reader.layoutDisabled")
        }

    fun invert(invert: TapInvert): String =
        when (invert) {
            TapInvert.None -> t("reader.invertNone")
            TapInvert.Horizontal -> t("reader.invertHorizontal")
            TapInvert.Vertical -> t("reader.invertVertical")
            TapInvert.Both -> t("reader.invertBoth")
        }

    fun scroll(amount: ScrollAmount): String = t("reader.scrollPercent", "n" to (amount.fraction * 100).roundToInt())

    fun action(action: TapAction): String =
        when (action) {
            TapAction.Previous -> t("reader.tapPrevious")
            TapAction.Next -> t("reader.tapNext")
            TapAction.Menu -> t("reader.tapMenu")
        }
}
