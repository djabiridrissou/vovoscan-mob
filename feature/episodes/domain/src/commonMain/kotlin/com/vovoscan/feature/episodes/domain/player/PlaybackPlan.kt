package com.vovoscan.feature.episodes.domain.player

import com.vovoscan.feature.episodes.domain.Box
import com.vovoscan.feature.episodes.domain.PageSize
import com.vovoscan.feature.episodes.domain.TimelineBubble
import com.vovoscan.feature.episodes.domain.Watch
import kotlin.math.max
import kotlin.math.min

/** Draw an image of `width × height` at (`translateX`, `translateY`) scaled by `scale`, in stage points. */
data class StageTransform(
    val scale: Double,
    val translateX: Double,
    val translateY: Double,
)

/**
 * One page as the stage shows it: the image, where to draw it, the panel outline to clip it to, and the backdrop.
 * - Draw [backdropTransform] of the page image first, blurred and darkened (it fills the stage around the panel).
 * - Then the page image with [transform], clipped to [framing].shape mapped through the same transform.
 * - [bubbleBox] (page pixels): the balloon being read, for a discreet highlight; null on a wordless beat.
 */
data class PageShot(
    /** Index of the page in the timeline (`TimelinePage.index`). */
    val pageIndex: Int,
    /** Signed URL; null if the watch has none (show the backdrop colour). */
    val pageUrl: String?,
    val pageWidth: Double,
    val pageHeight: Double,
    val framing: Framing,
    val transform: StageTransform,
    val backdropTransform: StageTransform,
    val bubbleBox: Box?,
    val shout: Boolean,
)

/**
 * The caption of the beat: subtitle cards shown one after the other over the bubble, in natural case.
 * [text] is the card up now ("" in the short blank between two cards). [needed] false: the balloon is legible in
 * frame, so the caption is not drawn (keep it for screen readers only).
 */
data class CaptionFrame(
    val text: String,
    val cardIndex: Int,
    val cards: List<String>,
    /** "ja", "ko", "zh" or null: pick the region's CJK font. */
    val lang: String?,
    /** normal, shout, whisper, thought, trembling, electronic, narration. */
    val tone: String,
    /** A card longer than 52 characters: set it smaller so it stays on two lines. */
    val small: Boolean,
    val needed: Boolean,
)

/** Where the caption block goes, from [PlaybackPlan.captionPlacement]. */
data class CaptionPlacement(
    /** False: no place clear of the panel's lettering (or no caption needed): don't draw it. */
    val visible: Boolean,
    /** The block's top, in stage heights (0 = top); draw the text one padding below it. */
    val top: Double,
    /** The block crosses the panel: draw a shade behind it. */
    val overArt: Boolean,
)

enum class AudioKind {
    /** The bubble's voiced line. */
    Voice,

    /** A panel sound (effect or ambience), started with its bubble, heard across its panel. */
    Sound,

    /** An ambience bed, looping. */
    Bed,

    /** A cue of the score, looping. */
    Music,
}

/**
 * A clip that must be playing now. Keep one player per [id]: create it when the id appears (seek to [offsetMs]),
 * update its volume and pan every frame, seek again if it drifts (more than ~250 ms, e.g. after a seek), release it
 * when the id disappears. Play at the player's speed; pause them all when paused.
 */
data class AudioCue(
    val id: String,
    val kind: AudioKind,
    val audioKey: String,
    /** Signed URL; null when the watch has none for this key (skip it). */
    val url: String?,
    /** Where in the clip it should be now (looping clips already wrapped). */
    val offsetMs: Double,
    /** Volume 0..1: fades, crossfades, ducking under the lines. 0 when muted. */
    val gain: Double,
    /** −1 left … +1 right, constant-power law (StereoPannerNode, `AVAudioPlayer.pan`). */
    val pan: Double,
    val loop: Boolean,
)

/** Everything the native player draws and plays at one moment. */
data class PlayerFrame(
    val timeMs: Double,
    /** The beat (bubble) index. */
    val beat: Int,
    val bubbleId: String,
    val shot: PageShot,
    /**
     * The previous shot under a dissolve or a page turn (ADR-0043): draw it over [shot] with opacity
     * `1 - shot.framing.mix`; null when nothing fades.
     */
    val ghost: PageShot?,
    /** 1 − light: a black veil over everything (only the episode's opening fade). */
    val veil: Double,
    val caption: CaptionFrame?,
    val audio: List<AudioCue>,
)

/**
 * The episode as a pure function of time: built once from a [Watch], it says for any moment what page, framing,
 * caption and sounds the native players show and play (camera.ts, captions.ts and mix.ts, ported line for line).
 * Deterministic and side-effect free; caches depend only on the stage's aspect.
 */
class PlaybackPlan(
    val watch: Watch,
) {
    private val timeline = watch.timeline
    val bubbles: List<TimelineBubble> = timeline.bubbles
    private val starts: DoubleArray
    private val pageSizes: Map<Int, PageSize> =
        timeline.pages.associate { it.index to PageSize(it.width.toDouble(), it.height.toDouble()) }
    private val pagePositions: Map<Int, Int> = timeline.pages.withIndex().associate { (i, p) -> p.index to i }
    private val ducks = duckIntervals(bubbles)
    private val kept = keptSounds(bubbles)
    private val cards = bubbles.map { captionCards(it.text, it.durationMs, null, it.display) }
    private val after = bubbles.indices.map { i -> i > 0 && captionText(bubbles[i - 1].text, bubbles[i - 1].display).isNotEmpty() }
    private val longestSpan = bubbles.flatMap { it.sounds }.maxOfOrNull { it.spanMs.toDouble() } ?: 0.0

    /** Total length in ms. */
    val durationMs: Double

    init {
        var acc = 0.0
        starts =
            DoubleArray(bubbles.size) { i ->
                val at = acc
                acc += bubbles[i].durationMs
                at
            }
        durationMs = acc
    }

    val beatCount: Int get() = bubbles.size

    val pageCount: Int get() = timeline.pages.size

    /** No voice, sound, bed or score at all: show "Silent" instead of a mute toggle. */
    val silent: Boolean =
        bubbles.none { !it.audioKey.isNullOrEmpty() || it.sounds.isNotEmpty() } && timeline.beds.isEmpty() && timeline.music.isEmpty()

    fun beatStartMs(beat: Int): Double = starts[beat.coerceIn(0, max(0, bubbles.size - 1))]

    /** The beat playing at [timeMs] (the last one at or after the end). */
    fun beatAt(timeMs: Double): Int {
        if (bubbles.isEmpty()) return 0
        var lo = 0
        var hi = bubbles.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= timeMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** 1-based position of the beat's page among the timeline's pages. */
    fun pageNumberOf(beat: Int): Int = (pagePositions[bubbles.getOrNull(beat)?.page] ?: 0) + 1

    /** The first beat of the next page, or null on the last page. */
    fun nextPageBeat(beat: Int): Int? {
        val page = bubbles.getOrNull(beat)?.page ?: return null
        return (beat + 1 until bubbles.size).firstOrNull { bubbles[it].page != page }
    }

    /** The first beat of this page, or of the previous page when already at its start. */
    fun previousPageBeat(beat: Int): Int {
        if (bubbles.isEmpty()) return 0
        val first = firstBeatOfPage(beat)
        return if (first < beat) first else firstBeatOfPage(max(0, first - 1))
    }

    private fun firstBeatOfPage(beat: Int): Int {
        var i = beat.coerceIn(0, bubbles.size - 1)
        while (i > 0 && bubbles[i - 1].page == bubbles[i].page) i -= 1
        return i
    }

    /** URLs worth warming now: the next beat's voice line and the next page's image. */
    fun prefetchUrls(beat: Int): List<String> {
        val next = bubbles.getOrNull(beat + 1) ?: return emptyList()
        return listOfNotNull(next.audioKey?.let { watch.audioUrls[it] }, pageUrl(next.page))
    }

    private fun pageUrl(page: Int): String? = pagePositions[page]?.let { watch.pageUrls.getOrNull(it) }

    private fun pageSize(page: Int): PageSize = pageSizes[page] ?: PageSize(1.0, 1.0)

    // Per aspect caches (the stage changes size rarely: rotation, split screen).
    private var cachedAspect = Double.NaN
    private var cachedTakes: List<Take> = emptyList()
    private val neededCache = HashMap<Int, Boolean>()
    private val ghostCache = HashMap<Int, Framing>()
    private val slotCache = HashMap<String, CaptionPlacement>()

    private fun takesFor(aspect: Double): List<Take> {
        if (aspect != cachedAspect) {
            cachedAspect = aspect
            cachedTakes = takes(bubbles, aspect, timeline.direction, CAPTION_BAND)
            neededCache.clear()
            ghostCache.clear()
            slotCache.clear()
        }
        return cachedTakes
    }

    /**
     * What to draw and play at [timeMs] on a stage of [stageWidth] × [stageHeight] points. Paused ([playing] false),
     * a beat shows where its transition settles, as the web player did. [reducedMotion]: the whole panel, held.
     */
    fun frame(
        timeMs: Double,
        stageWidth: Double,
        stageHeight: Double,
        playing: Boolean = true,
        reducedMotion: Boolean = false,
    ): PlayerFrame? {
        if (bubbles.isEmpty() || stageWidth <= 0 || stageHeight <= 0) return null
        val t = timeMs.coerceIn(0.0, durationMs)
        val aspect = stageWidth / stageHeight
        val list = takesFor(aspect)
        val i = beatAt(t)
        val b = bubbles[i]
        val prev = bubbles.getOrNull(i - 1)
        val elapsed = min(t - starts[i], b.durationMs.toDouble())
        val page = pageSize(b.page)
        val take = list.getOrNull(i)
        val before = list.getOrNull(i - 1)
        val settle = if (playing) 0.0 else transition(b, prev, page, aspect, timeline.direction, CAPTION_BAND, take, before).ms
        val shot = framingAt(b, prev, max(elapsed, settle), reducedMotion, page, aspect, timeline.direction, CAPTION_BAND, take, before)
        val ghost =
            if (shot.mix < 1 && prev != null) {
                val last =
                    ghostCache.getOrPut(i) {
                        framingAt(
                            prev,
                            bubbles.getOrNull(i - 2),
                            prev.durationMs.toDouble(),
                            false,
                            pageSize(prev.page),
                            aspect,
                            timeline.direction,
                            CAPTION_BAND,
                            before,
                            list.getOrNull(i - 2),
                        )
                    }
                pageShot(i - 1, last, stageWidth, stageHeight)
            } else {
                null
            }
        return PlayerFrame(
            timeMs = t,
            beat = i,
            bubbleId = b.id,
            shot = pageShot(i, shot, stageWidth, stageHeight),
            ghost = ghost,
            veil = 1 - shot.light,
            caption = caption(i, elapsed, aspect, take),
            audio = audio(t),
        )
    }

    private fun pageShot(
        index: Int,
        framing: Framing,
        w: Double,
        h: Double,
    ): PageShot {
        val b = bubbles[index]
        val page = pageSize(b.page)
        return PageShot(
            pageIndex = b.page,
            pageUrl = pageUrl(b.page),
            pageWidth = page.width,
            pageHeight = page.height,
            framing = framing,
            transform = fitTransform(framing.view, w, h),
            backdropTransform = backdropTransform(framing.backdrop, w, h),
            bubbleBox = if (cards[index].isNotEmpty() && !sameBox(b.bubbleBbox, b.panelBbox)) b.bubbleBbox else null,
            shout = b.shout,
        )
    }

    private fun caption(
        i: Int,
        elapsed: Double,
        aspect: Double,
        take: Take?,
    ): CaptionFrame? {
        val list = cards[i]
        if (list.isEmpty()) return null
        val b = bubbles[i]
        val card = cardAt(list, elapsed, b.durationMs.toDouble(), after[i])
        val needed =
            neededCache.getOrPut(i) { captionShown(b, bubbles.getOrNull(i - 1), aspect, timeline.direction, CAPTION_BAND, null, take) }
        return CaptionFrame(
            text = if (card >= 0) list[card] else "",
            cardIndex = card,
            cards = list,
            lang = captionLang(list.first()),
            tone = b.tone,
            small = list.any { it.length > LONG_CARD },
            needed = needed,
        )
    }

    /**
     * Where the caption block of [beat] goes on this stage. Measure the block the caption takes with its first card
     * (two lines at most, its padding of half the font size above and below included) and call this once per beat
     * and stage size; the answer is cached. Placed under the panel when it fits there, else in the lowest place clear
     * of the panel's lettering, else not drawn.
     */
    fun captionPlacement(
        beat: Int,
        stageWidth: Double,
        stageHeight: Double,
        blockHeight: Double,
        blockWidth: Double,
    ): CaptionPlacement {
        val hidden = CaptionPlacement(false, 0.0, false)
        val b = bubbles.getOrNull(beat) ?: return hidden
        if (stageWidth <= 0 || stageHeight <= 0 || cards[beat].isEmpty()) return hidden
        val aspect = stageWidth / stageHeight
        val list = takesFor(aspect)
        val key = "$beat:$stageWidth:$stageHeight:$blockHeight:$blockWidth"
        return slotCache.getOrPut(key) {
            val prev = bubbles.getOrNull(beat - 1)
            val needed =
                neededCache.getOrPut(beat) {
                    captionShown(b, prev, aspect, timeline.direction, CAPTION_BAND, null, list.getOrNull(beat))
                }
            if (!needed) return@getOrPut hidden
            val slot =
                captionSlot(
                    b,
                    prev,
                    panelLetters(bubbles, b),
                    pageSize(b.page),
                    aspect,
                    blockHeight / stageHeight,
                    blockWidth / stageWidth,
                    1 - CAPTION_FLOOR,
                    timeline.direction,
                    CAPTION_BAND,
                    list.getOrNull(beat),
                    list.getOrNull(beat - 1),
                ) ?: return@getOrPut hidden
            CaptionPlacement(true, slot.top, slot.overArt)
        }
    }

    /** The clips playing at [timeMs]: the beat's line, the panel sounds still heard, the beds and the score. */
    fun audio(timeMs: Double): List<AudioCue> {
        if (bubbles.isEmpty()) return emptyList()
        val t = timeMs.coerceIn(0.0, durationMs)
        val out = mutableListOf<AudioCue>()
        val i = beatAt(t)
        val b = bubbles[i]
        val elapsed = t - starts[i]
        val key = b.audioKey
        if (!key.isNullOrEmpty() && elapsed < b.durationMs) {
            out += AudioCue("voice:${b.id}:$i", AudioKind.Voice, key, watch.audioUrls[key], elapsed, 1.0, b.pan, false)
        }
        // Panel sounds start with their bubble and run across the panel (export.write_mix keeps the same ones).
        var j = i
        while (j >= 0 && t - starts[j] < longestSpan) {
            val since = t - starts[j]
            kept[j].forEachIndexed { k, x ->
                val heard = min(x.durationMs, x.spanMs).toDouble()
                if (since < heard) {
                    out +=
                        AudioCue(
                            "sound:$j:$k",
                            AudioKind.Sound,
                            x.audioKey,
                            watch.audioUrls[x.audioKey],
                            since,
                            min(1.0, max(0.0, soundGain(ducks, x.spanMs.toDouble(), since, t))),
                            panAt(x.pan, x.panEnd, heard, since),
                            false,
                        )
                }
            }
            j -= 1
        }
        for (live in bedsAt(timeline.beds, ducks, t, BEDS)) {
            val bed = timeline.beds[live.index]
            out +=
                AudioCue(
                    "bed:${live.index}",
                    AudioKind.Bed,
                    bed.audioKey,
                    watch.audioUrls[bed.audioKey],
                    live.offsetMs,
                    live.gain,
                    0.0,
                    true,
                )
        }
        for (live in bedsAt(timeline.music, ducks, t, SCORE)) {
            val cue = timeline.music[live.index]
            out +=
                AudioCue(
                    "music:${live.index}",
                    AudioKind.Music,
                    cue.audioKey,
                    watch.audioUrls[cue.audioKey],
                    live.offsetMs,
                    live.gain,
                    0.0,
                    true,
                )
        }
        return out
    }

    companion object {
        /** A caption keeps this much of the stage under it. */
        const val CAPTION_FLOOR = 0.045

        /** A caption over the art has a shade of its own this share of its font size above and below. */
        const val CAPTION_PAD = 0.5

        /** A caption card longer than this is set smaller to stay on two lines. */
        const val LONG_CARD = 52

        /** Fits `view` (page pixels, at the stage's aspect) into a `w` × `h` stage, centred. */
        fun fitTransform(
            view: Box,
            w: Double,
            h: Double,
        ): StageTransform {
            val s = min(w / view.w, h / view.h)
            return StageTransform(s, w / 2 - (view.x + view.w / 2) * s, h / 2 - (view.y + view.h / 2) * s)
        }

        /**
         * The backdrop (ADR-0040): the page image covering a `w` × `h` stage with `box` (the active panel) cropped to
         * the stage's aspect around its centre. Blur and darken it.
         */
        fun backdropTransform(
            box: Box,
            w: Double,
            h: Double,
        ): StageTransform {
            val aspect = w / h
            val cw = if (box.w / box.h > aspect) box.h * aspect else box.w
            val s = w / cw
            val ch = if (box.w / box.h > aspect) box.h else box.w / aspect
            val x0 = box.x + box.w / 2 - cw / 2
            val y0 = box.y + box.h / 2 - ch / 2
            return StageTransform(s, -x0 * s, -y0 * s)
        }
    }
}
