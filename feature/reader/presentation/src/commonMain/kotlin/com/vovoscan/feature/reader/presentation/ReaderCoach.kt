package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.reader.domain.CoachKind
import com.vovoscan.feature.reader.domain.TapZoneMap

/** The gesture a coach line is about: the native side draws its pictogram. */
enum class CoachGesture { Tap, Swipe, DoubleTap, Pull, Help }

data class CoachHint(
    val gesture: CoachGesture,
    val text: String,
)

/**
 * "How to read", over the page the first time a chapter of a kind opens (a webtoon, a manga read right to left, a
 * comic left to right, vertical pages, a book): the real tap zones of the reader (red next, cyan back, ink bars) and a
 * few lines on the gestures. Static: any tap closes it and reading starts; the "?" in the bars brings it back.
 */
data class ReaderCoach(
    val kind: CoachKind,
    val kicker: String,
    val title: String,
    /** The tap zones, as the reader uses them now (null for a book: taps belong to the text). */
    val zones: TapOverlay?,
    val hints: List<CoachHint>,
    val dismiss: String,
) {
    companion object {
        fun of(
            kind: CoachKind,
            title: String,
            zones: TapZoneMap?,
            doubleTapZoom: Boolean,
        ): ReaderCoach {
            val hints =
                buildList {
                    if (kind != CoachKind.Book) add(CoachHint(CoachGesture.Tap, t("reader.coach.zones")))
                    when (kind) {
                        CoachKind.Strip -> add(CoachHint(CoachGesture.Swipe, t("reader.coach.strip")))
                        CoachKind.RightToLeft -> add(CoachHint(CoachGesture.Swipe, t("reader.coach.rtl")))
                        CoachKind.LeftToRight -> add(CoachHint(CoachGesture.Swipe, t("reader.coach.ltr")))
                        CoachKind.Vertical -> add(CoachHint(CoachGesture.Swipe, t("reader.coach.vertical")))
                        CoachKind.Book -> {
                            add(CoachHint(CoachGesture.Swipe, t("reader.coach.book")))
                            add(CoachHint(CoachGesture.Pull, t("reader.coach.bookPull")))
                        }
                    }
                    if (doubleTapZoom && kind != CoachKind.Book) add(CoachHint(CoachGesture.DoubleTap, t("reader.coach.zoom")))
                    add(CoachHint(CoachGesture.Help, t("reader.coach.again")))
                }
            return ReaderCoach(
                kind = kind,
                kicker = t("reader.coach.kicker"),
                title = title,
                zones = zones?.let(TapOverlay::of),
                hints = hints,
                dismiss = t("reader.coach.dismiss"),
            )
        }
    }
}
