package com.vovoscan.feature.reader.domain

import com.vovoscan.core.common.AppResult
import kotlin.math.max
import kotlin.math.min

enum class TranslationStatus {
    /** Nobody asked for this language yet. */
    None,
    Queued,
    Running,
    Ready,
    Failed,
    ;

    val inProgress: Boolean get() = this == Queued || this == Running

    companion object {
        fun from(wire: String?): TranslationStatus =
            when (wire?.lowercase()) {
                "queued" -> Queued
                "running", "translating", "rendering" -> Running
                "ready", "done", "succeeded" -> Ready
                "failed" -> Failed
                else -> None
            }
    }
}

/** A language of a chapter as the server knows it. */
data class ChapterTranslation(
    val lang: String,
    val status: TranslationStatus,
    /** 0..100 while running; null when unknown. */
    val progress: Int?,
)

/** A box in page pixels. */
data class PixelBox(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
)

/** A translated text box of a page: the original lettering's box, its balloon's interior when known, its paint. */
data class TranslatedText(
    val id: String,
    val box: PixelBox,
    val text: String,
    /** `#rrggbb`: the balloon's paint behind the lettering. */
    val fill: String,
    /** `#rrggbb`: the lettering's colour. */
    val ink: String,
    val round: Boolean,
    val area: PixelBox?,
)

data class TranslatedPage(
    val index: Int,
    val width: Int,
    val height: Int,
    val texts: List<TranslatedText>,
)

/** A rectangle as fractions of its page (0..1), so every renderer places it at any zoom. */
data class PageRect(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
)

/** A patch of the balloon's paint over the original letters. */
data class LetteringPatch(
    val rect: PageRect,
    val ellipse: Boolean,
)

/** What a renderer draws for one translated text: the patches, then the text fitted in [textRect]. */
data class Lettering(
    val id: String,
    val patches: List<LetteringPatch>,
    val textRect: PageRect,
    val text: String,
    val fill: String,
    val ink: String,
)

/**
 * Where a translation is painted (port of the web reader's `lettering.ts`, ADR-0044): patches of the balloon's paint
 * covering the original letters (its interior when known, plus the text box itself), and the rectangle the translation
 * is fitted in (the interior's usable part, or the text box, widened for vertical lettering in a horizontal script).
 */
object LetteringLayout {
    /** The patch over the text box spills this share of its short side on each side (anti-aliased letter edges). */
    const val INFLATE = 0.12

    /** Lettering this much taller than wide was set vertically (Japanese, Chinese). */
    const val VERTICAL = 2.2
    private const val IN_ELLIPSE = 0.74
    private const val BOX_MARGIN = 0.06

    fun page(
        page: TranslatedPage,
        verticalTarget: Boolean = false,
    ): List<Lettering> =
        if (page.width <= 0 || page.height <= 0) {
            emptyList()
        } else {
            page.texts.filter { it.text.isNotBlank() && it.box.w > 0 && it.box.h > 0 }.map { layout(it, page, verticalTarget) }
        }

    fun layout(
        t: TranslatedText,
        page: TranslatedPage,
        verticalTarget: Boolean,
    ): Lettering {
        val (x, y, w, h) = t.box
        val pad = INFLATE * min(w, h)
        val over = clamp(PixelBox(x - pad, y - pad, w + 2 * pad, h + 2 * pad), page)
        val area = t.area
        if (area != null) {
            val usable =
                if (t.round) {
                    PixelBox(
                        area.x + area.w * (1 - IN_ELLIPSE) / 2,
                        area.y + area.h * (1 - IN_ELLIPSE) / 2,
                        area.w * IN_ELLIPSE,
                        area.h * IN_ELLIPSE,
                    )
                } else {
                    shrink(area, BOX_MARGIN)
                }
            // Never smaller than the original lettering.
            val left = min(usable.x, x)
            val top = min(usable.y, y)
            val text = clamp(PixelBox(left, top, max(usable.x + usable.w, x + w) - left, max(usable.y + usable.h, y + h) - top), page)
            return lettering(t, page, listOf(shrink(area, 0.02) to t.round, over to false), text)
        }
        var text = PixelBox(x, y, w, h)
        if (!verticalTarget && h > VERTICAL * w) {
            val wide = min(h * 0.55, w * 2.2)
            text = PixelBox(x - (wide - w) / 2, y, wide, h)
        }
        val tp = INFLATE * min(text.w, text.h)
        val textRect = clamp(PixelBox(text.x - tp, text.y - tp, text.w + 2 * tp, text.h + 2 * tp), page)
        return lettering(t, page, listOf(textRect to false, over to false), textRect)
    }

    private fun lettering(
        t: TranslatedText,
        page: TranslatedPage,
        patches: List<Pair<PixelBox, Boolean>>,
        text: PixelBox,
    ) = Lettering(
        id = t.id,
        patches = patches.map { (box, ellipse) -> LetteringPatch(fraction(box, page), ellipse) },
        textRect = fraction(text, page),
        text = t.text,
        fill = t.fill,
        ink = t.ink,
    )

    private fun clamp(
        r: PixelBox,
        page: TranslatedPage,
    ): PixelBox {
        val x = max(0.0, r.x)
        val y = max(0.0, r.y)
        return PixelBox(
            x,
            y,
            max(1.0, min(page.width.toDouble(), r.x + r.w) - x),
            max(1.0, min(page.height.toDouble(), r.y + r.h) - y),
        )
    }

    private fun shrink(
        r: PixelBox,
        share: Double,
    ) = PixelBox(r.x + r.w * share, r.y + r.h * share, r.w * (1 - 2 * share), r.h * (1 - 2 * share))

    private fun fraction(
        r: PixelBox,
        page: TranslatedPage,
    ) = PageRect(r.x / page.width, r.y / page.height, r.w / page.width, r.h / page.height)
}

/** What making a translation would cost this account (`POST /quotes`, kind `translation`). */
data class TranslationQuote(
    val credits: Long,
    val maxCredits: Long,
    val coveredByOwnKeys: Boolean,
    /** Already made (shared): reading it is free and nothing runs. */
    val alreadyAvailable: Boolean,
    /** Own key, or credits the wallet can hold. */
    val fundable: Boolean,
    /** The wallet's balance when quoted; null when unknown (no wallet read). */
    val balance: Long? = null,
    /** Nobody funds the reading (no own key, platform funding off): [maxCredits] is still its price. */
    val unfunded: Boolean = false,
)

/** Quotes come from the account slice (wired in `shared`); null: no quote (a server without credits, offline). */
fun interface TranslationQuotes {
    suspend fun quote(
        chapterId: String,
        lang: String,
    ): TranslationQuote?

    /** A translation was asked for, made or refused: the balance may have moved (the wallet reads it again). */
    fun changed() {}

    companion object {
        val None = TranslationQuotes { _, _ -> null }
    }
}

/**
 * Translations of a chapter of our catalog (`api/v1/translations/chapters/…`): made once per language and shared by
 * everyone; asking for one that exists costs nothing. Online only: a translation is read fresh.
 */
interface TranslationsRepository {
    suspend fun languages(chapterId: String): AppResult<List<ChapterTranslation>>

    /** 200: it exists (shared, free); 202: queued. 402 when the wallet can't pay. */
    suspend fun request(
        chapterId: String,
        lang: String,
    ): AppResult<ChapterTranslation>

    suspend fun pages(
        chapterId: String,
        lang: String,
    ): AppResult<List<TranslatedPage>>
}
