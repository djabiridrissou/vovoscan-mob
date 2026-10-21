package com.vovoscan.feature.episodes.domain.player

import com.vovoscan.feature.episodes.domain.Box
import com.vovoscan.feature.episodes.domain.Direction
import com.vovoscan.feature.episodes.domain.PageSize
import com.vovoscan.feature.episodes.domain.Point
import com.vovoscan.feature.episodes.domain.Quad
import com.vovoscan.feature.episodes.domain.TimelineBubble
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Camera: panel first (ADR-0040) over the portrait framing (ADR-0033) and the episode look (ADR-0037): the active
 * panel is always shown whole, with a gentle push-in that never cuts its characters or lettering and drifts on for
 * the whole beat; long panels pan along their long axis; a panel read whole glides slowly (ADR-0043); impacts
 * shake; panel outlines, distance-scaled glides, dissolves instead of whips (never through black), the caption
 * band, which balloons need a caption at all and where it may sit. A line-for-line port of the old web player's
 * camera.ts (itself a port of the worker's export.py), so every player matches the MP4 export; the shared vectors
 * (fixtures/framing.json) are checked by every test suite. Boxes are page pixels.
 */

/** A new page dissolves in over the last one (the episode's first fades in from black). */
internal const val FADE_MS = 450.0

/** A whole-page shot (the cover) arrives slower. */
internal const val COVER_FADE_MS = 900.0

/** The push-in toward the bubble scales the whole-panel view by at most this. */
internal const val MAX_PUSH = 1.15

/** ... and always keeps this share of the panel's area in the frame. */
internal const val MIN_VISIBLE = 0.85

/** A panel read whole (one take for its bubbles) keeps this share of its area in frame. */
internal const val WIDE_VISIBLE = 0.95

/** ... and its slow push-in (Ken Burns) over the whole take scales by at most this. */
internal const val WIDE_PUSH = 1.05

/** ... while the panel glides along the frame's free axis at WIDE_GLIDE of the frame's width a second, ≤ WIDE_GLIDE_MAX. */
internal const val WIDE_GLIDE = 0.045
internal const val WIDE_GLIDE_MAX = 0.4

/** Share of the drift (the push-in over a whole beat) at an even speed: never a dead hold. */
internal const val DRIFT_LINEAR = 0.5

/** The next bubble of the same panel starts its push-in this far along. */
internal const val PUSH_RESTART = 0.5

/** A panel with too little room to push in, and a long panel's piece, drift out instead. */
internal const val DRIFT_SPEED = 0.015
internal const val MIN_DRIFT = 0.04
internal const val MAX_DRIFT = 0.12

/** A long panel's piece moves along the panel at least this share of itself each way. */
internal const val LONG_NUDGE = 0.06

/** An impact (a sound effect drawn or heard) shakes the camera this long... */
internal const val IMPACT_MS = 400.0

/** ... by at most this share of the view's width, damped. */
internal const val IMPACT_SHAKE = 0.012

/** A panel wider than this (w/h) shows a WIDE_MAX:1 piece, panning across it. */
internal const val WIDE_MAX = 3.0

/** A panel taller than this (h/w) shows its full width, panning down it. */
internal const val TALL_MAX = 2.5

/** A rectangular panel's mask is inset this share of its shorter side (gutter slivers). */
internal const val MASK_INSET = 0.01

/** Glides scale with distance: clamp(MIN, MAX, BASE + PER_VIEW × viewport heights). */
internal const val GLIDE_MIN_MS = 500.0
internal const val GLIDE_MAX_MS = 1000.0
internal const val GLIDE_BASE_MS = 400.0
internal const val GLIDE_PER_VIEW_MS = 350.0

/** A glide never takes more than this share of its bubble. */
internal const val GLIDE_SHARE = 0.6

/** Viewport heights beyond which a dissolve replaces the glide. */
internal const val CUT_DISTANCE = 1.5

/** A dissolve: the new shot fades in over the old one, never through black (ADR-0043). */
internal const val DISSOLVE_MS = 500.0

/** A glide squeezed into less than this share of its time is a dissolve (never a whip). */
internal const val GLIDE_SHORTEN = 0.8

/** A long panel's pan peaks at this many window extents per second (ADR-0039). */
internal const val PAN_SPEED = 0.25

/** Average / peak speed of the cosine ease the pans use. */
internal const val PEAK = 2 / PI

/** Bottom share of the stage kept for the caption: a bubble's panel is framed above it. */
const val CAPTION_BAND = 0.2

/** A caption repeats a balloon only when its lettering is smaller than LEGIBLE of a 9:16 frame's width, cut, or in another script. */
internal const val LEGIBLE = 22.0 / 1080
internal const val REF_ASPECT = 9.0 / 16

/** Gap between the panel's bottom and a caption set under it (stage heights). */
internal const val CAPTION_GAP = 0.02

/** Where a caption may sit (ADR-0043): never within CAPTION_CLEAR of the lettering, never above CAPTION_TOP. */
internal const val CAPTION_CLEAR = 0.012
internal const val CAPTION_TOP = 0.06
internal const val CAPTION_STEP = 0.005
internal const val SLOT_SAMPLES = 12

/** Area of one lettered character (em squared) and a capital's or ideograph's height (em). */
private const val LETTER_CELL_LATIN = 0.72
private const val LETTER_CELL_CJK = 1.1
private const val LETTER_HEIGHT_LATIN = 0.72
private const val LETTER_HEIGHT_CJK = 0.88

private fun clamp01(t: Double) = min(1.0, max(0.0, t))

private fun ease(t: Double) = 0.5 - 0.5 * cos(PI * clamp01(t))

/** The push-in's curve over a settled beat: mostly eased, partly even, never still. */
internal fun drift(t: Double) = (1 - DRIFT_LINEAR) * ease(t) + DRIFT_LINEAR * clamp01(t)

private fun easeInOut(t: Double) = if (t < 0.5) 2 * t * t else 1 - (-2 * t + 2).pow(2) / 2

private fun lerp(
    a: Double,
    b: Double,
    t: Double,
) = a + (b - a) * t

/** Value equality (`-0.0 == 0.0`, as in JavaScript). */
internal fun sameBox(
    a: Box,
    b: Box,
): Boolean = a.x == b.x && a.y == b.y && a.w == b.w && a.h == b.h

private fun lerpBox(
    a: Box,
    b: Box,
    t: Double,
) = Box(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.w, b.w, t), lerp(a.h, b.h, t))

internal fun samePanel(
    a: TimelineBubble?,
    b: TimelineBubble,
): Boolean = a != null && a.page == b.page && sameBox(a.panelBbox, b.panelBbox)

/** The smallest box with the output aspect (w/h) that contains `box`, same centre. */
internal fun fitBox(
    box: Box,
    aspect: Double,
): Box {
    val (x, y, w, h) = box
    if (w / h > aspect) {
        val nh = w / aspect
        return Box(x, y - (nh - h) / 2, w, nh)
    }
    val nw = h * aspect
    return Box(x - (nw - w) / 2, y, nw, h)
}

/** Slide [pos, pos + size] inside [lo, lo + span] (it is never larger). */
private fun keepInside(
    pos: Double,
    size: Double,
    lo: Double,
    span: Double,
): Double = min(max(pos, lo), lo + span - size)

/**
 * Where a window of `size` starts and ends to sweep the detail [d0, d1] inside [lo, lo + span]: from its low edge to
 * its high edge, or held on its middle when it fits. Over `seconds` the sweep is shortened around its middle so its
 * peak speed stays within PAN_SPEED.
 */
private fun travel(
    size: Double,
    d0: Double,
    d1: Double,
    lo: Double,
    span: Double,
    seconds: Double?,
): Pair<Double, Double> {
    var start = keepInside(d0, size, lo, span)
    var end = keepInside(d1 - size, size, lo, span)
    if (end < start) {
        start = keepInside((d0 + d1) / 2 - size / 2, size, lo, span)
        end = start
    }
    if (seconds != null && end - start > PAN_SPEED * PEAK * size * seconds) {
        val mid = (start + end) / 2
        val half = (PAN_SPEED * PEAK * size * seconds) / 2
        start = mid - half
        end = mid + half
    }
    return start to end
}

/**
 * Where a window of `size` sits along one axis of the panel [lo, lo + span]: as near `want` as it can while it stays
 * inside the panel (when smaller than it) or around it (when larger), and around `keep` (k0, k1).
 */
private fun place(
    want: Double,
    size: Double,
    lo: Double,
    span: Double,
    keep: Pair<Double, Double>?,
): Double {
    var a = min(lo, lo + span - size)
    var b = max(lo, lo + span - size)
    if (keep != null) {
        val na = max(a, keep.second - size)
        val nb = min(b, keep.first)
        a = na
        b = nb
    }
    if (b < a) return (a + b) / 2
    return min(max(want, a), b)
}

/** `box` cut to `panel`; the panel itself when they do not meet. */
private fun clip(
    box: Box,
    panel: Box,
): Box {
    val x0 = max(box.x, panel.x)
    val y0 = max(box.y, panel.y)
    val x1 = min(box.x + box.w, panel.x + panel.w)
    val y1 = min(box.y + box.h, panel.y + panel.h)
    if (x1 <= x0 || y1 <= y0) return panel
    return Box(x0, y0, x1 - x0, y1 - y0)
}

internal fun union(
    a: Box,
    b: Box,
): Box {
    val x0 = min(a.x, b.x)
    val y0 = min(a.y, b.y)
    val x1 = max(a.x + a.w, b.x + b.w)
    val y1 = max(a.y + a.h, b.y + b.h)
    return Box(x0, y0, x1 - x0, y1 - y0)
}

/** How much the push-in may scale `base` (the whole panel fitted to the frame), keeping `keep` whole and `visible` of the panel. */
internal fun pushScale(
    base: Box,
    panel: Box,
    keep: Box,
    visible: Double = MIN_VISIBLE,
): Double {
    val w = base.w
    val h = base.h
    val big = max(w / panel.w, h / panel.h)
    val area = if (big >= 1 / visible) 1 / visible else sqrt(big / visible)
    val hold = if (keep.w > 0 && keep.h > 0) min(w / keep.w, h / keep.h) else MAX_PUSH
    return max(1.0, minOf(MAX_PUSH, area, hold))
}

/** A panel too long to fit whole and stay readable: a webtoon strip panel, a panorama. */
internal fun isLong(panel: Box): Boolean = panel.w > WIDE_MAX * panel.h || panel.h > TALL_MAX * panel.w

/**
 * A long panel keeps its short axis whole and shows a piece of it, fitted to a frame of `aspect`. A wordless beat
 * pans the piece across the panel's detail at the comfort speed (sideways in the reading direction, downwards in a
 * tall panel); a bubble is centred.
 */
private fun longView(
    panel: Box,
    bubble: Box,
    aspect: Double,
    progress: Double,
    direction: Direction,
    detail: Box?,
    seconds: Double?,
): Box {
    val (px, py, pw, ph) = panel
    val wide = pw > WIDE_MAX * ph
    val wordless = sameBox(bubble, panel)
    val d = if (wordless && detail != null) detail else panel
    val size = if (wide) WIDE_MAX * ph else min(TALL_MAX * pw, max(pw, pw / aspect))
    val lo = if (wide) px else py
    val span = if (wide) pw else ph
    val d0 = if (wide) d.x else d.y
    val d1 = if (wide) d.x + d.w else d.y + d.h
    val centre = if (wide) bubble.x + bubble.w / 2 - size / 2 else bubble.y + bubble.h / 2 - size / 2
    var a: Double
    var b: Double
    if (wordless) {
        val t = travel(size, d0, d1, lo, span, seconds)
        a = t.first
        b = t.second
    } else {
        a = keepInside(centre, size, lo, span)
        b = a
    }
    if (b - a < 2 * LONG_NUDGE * size) {
        // Never a still piece: a slow nudge along the panel, the bubble always in it.
        var half = LONG_NUDGE * size
        if (seconds != null) half = min(half, (PAN_SPEED * PEAK * size * seconds) / 2)
        var loPos = lo
        var hiPos = lo + span - size
        if (!wordless) {
            val b0 = if (wide) bubble.x else bubble.y
            val b1 = if (wide) bubble.x + bubble.w else bubble.y + bubble.h
            val nlo = max(loPos, b1 - size)
            val nhi = min(hiPos, b0)
            loPos = nlo
            hiPos = nhi
        }
        a = max(loPos, min((a + b) / 2 - half, hiPos - 2 * half)) // at an end: one way
        val na = min(a, hiPos)
        val nb = min(hiPos, a + 2 * half)
        a = na
        b = nb
    }
    if (wide && direction == Direction.Rtl) {
        val swap = a
        a = b
        b = swap
    }
    val pos = a + (b - a) * drift(progress)
    val piece = if (wide) Box(pos, py, size, ph) else Box(px, pos, pw, size)
    return fitBox(piece, aspect)
}

/** How much a drift out scales the view over a beat of `seconds` (unknown: MIN_DRIFT). */
internal fun driftOut(seconds: Double?): Double {
    if (seconds == null) return MIN_DRIFT
    return min(MAX_DRIFT, max(MIN_DRIFT, DRIFT_SPEED * seconds))
}

private fun panelView(
    panel: Box,
    focus: Box,
    keep: Box?,
    aspect: Double,
    push: Double,
    seconds: Double?,
    wide: Boolean = false,
    progress: Double = 0.5,
    direction: Direction = Direction.Rtl,
): Box {
    val view = pushView(panel, focus, keep, aspect, push, seconds, wide)
    val end = pushView(panel, focus, keep, aspect, 1.0, seconds, wide)
    // Any take glides while its push-in keeps the panel whole along the glide's axis.
    return wideGlide(panel, fitBox(panel, aspect), end, view, seconds, progress, direction, !wide)
}

/**
 * `view` (between the whole panel `base` and the take's push-in `end`) moved along the axis where the frame has room
 * around the panel (ADR-0043): sideways for a panel narrower than the frame (in the reading direction), downwards for
 * a wider one, at an even WIDE_GLIDE of the frame's width a second, WIDE_GLIDE_MAX at most, and only as far as both
 * ends keep the panel whole along that axis. With `whole`, only when `end` still shows the panel whole along it.
 */
private fun wideGlide(
    panel: Box,
    base: Box,
    end: Box,
    view: Box,
    seconds: Double?,
    progress: Double,
    direction: Direction,
    whole: Boolean = false,
): Box {
    if (seconds == null || seconds <= 0) return view
    val (px, py, pw, ph) = panel
    val sideways = base.w - pw > base.h - ph
    val axis = if (sideways) 0 else 1
    val lo = if (sideways) px else py
    val size = if (sideways) pw else ph
    if (whole && end[axis + 2] < size - 1e-9) return view

    fun room(v: Box): Pair<Double, Double> {
        val a = lo - v[axis]
        val b = lo + size - v[axis] - v[axis + 2]
        return min(a, b) to max(a, b)
    }
    val (lo0, hi0) = room(base)
    val (lo1, hi1) = room(end)
    val low = max(lo0, lo1)
    val high = min(hi0, hi1)
    val sign = if (sideways && direction == Direction.Rtl) -1 else 1
    var half = 0.5 * min(WIDE_GLIDE * seconds, WIDE_GLIDE_MAX) * base.w
    half = max(0.0, minOf(half, high, -low)) // the same bound whichever way it glides
    val shift = sign * half * (2 * clamp01(progress) - 1)
    return if (sideways) view.copy(x = view.x + shift) else view.copy(y = view.y + shift)
}

/** `panelView` before a wide take's glide. */
private fun pushView(
    panel: Box,
    focus: Box,
    keep: Box?,
    aspect: Double,
    push: Double,
    seconds: Double?,
    wide: Boolean,
): Box {
    val (px, py, pw, ph) = panel
    val base = fitBox(panel, aspect)
    if (push <= 0) return base
    val (x, y, w, h) = base
    var k = clip(if (keep != null) union(keep, focus) else focus, panel)
    val s: Double
    if (wide) {
        // Toward `keep` kept whole; without one, toward the panel's centre (WIDE_VISIBLE of its area is the bound).
        if (keep == null) k = Box(focus.x + focus.w / 2, focus.y + focus.h / 2, 0.0, 0.0)
        s = min(WIDE_PUSH, pushScale(base, panel, k, WIDE_VISIBLE))
    } else {
        s = pushScale(base, panel, k)
    }
    val outBy = if (wide) MIN_DRIFT else driftOut(seconds)
    if (s - 1 < outBy / 2) {
        // Too little room to push in (its characters fill it): drift out, still whole.
        val z = 1 + outBy
        val out = Box(x + w / 2 - (w * z) / 2, y + h / 2 - (h * z) / 2, w * z, h * z)
        return lerpBox(base, out, min(1.0, push))
    }
    val tw = w / s
    val th = h / s
    val fx = focus.x + focus.w / 2
    val fy = focus.y + focus.h / 2
    val wantX = if (tw < pw) fx - tw / 2 else px + pw / 2 - tw / 2
    val wantY = if (th < ph) fy - th / 2 else py + ph / 2 - th / 2
    val tx = place(wantX, tw, px, pw, k.x to k.x + k.w)
    val ty = place(wantY, th, py, ph, k.y to k.y + k.h)
    return lerpBox(base, Box(tx, ty, tw, th), min(1.0, push))
}

/**
 * The viewport (page pixels, aspect `aspect` = w/h) of a beat on `panel` (ADR-0040: panel first). The panel is shown
 * whole, with a gentle push-in toward the bubble (on a wordless beat toward its `detail`; without one, none) that
 * never cuts `keep`. A `wide` beat (one take for all the panel's bubbles) pushes slowly toward `keep` (else the
 * panel's centre), by at most WIDE_PUSH. A long panel shows its short axis whole and pans along the other. A beat with
 * a bubble is framed above the caption `band` and the view extends under it; a wordless beat uses the whole frame.
 */
internal fun frameView(
    panel: Box,
    bubble: Box,
    aspect: Double,
    progress: Double = 0.5,
    direction: Direction = Direction.Rtl,
    detail: Box? = null,
    seconds: Double? = null,
    band: Double = CAPTION_BAND,
    keep: Box? = null,
    push: Double = 1.0,
    wide: Boolean = false,
): Box {
    val wordless = sameBox(bubble, panel)
    val room = if (wordless) 0.0 else band
    val upper = aspect / (1 - room)
    val view: Box =
        if (isLong(panel)) {
            val (x, y, w, h) = longView(panel, bubble, upper, progress, direction, detail, seconds)
            val z = 1 + driftOut(seconds) * clamp01(push) // drifts out, its short axis whole
            Box(x + w / 2 - (w * z) / 2, y + h / 2 - (h * z) / 2, w * z, h * z)
        } else {
            val broad = wide && !wordless
            val focus =
                if (broad) {
                    keep ?: panel
                } else if (wordless) {
                    detail ?: panel
                } else {
                    bubble
                }
            panelView(panel, focus, keep, upper, push, seconds, broad, progress, direction)
        }
    return Box(view.x, view.y, view.w, view.h / (1 - room))
}

/** Centre moves linearly, size geometrically, so the zoom speed stays even. */
internal fun lerpView(
    a: Box,
    b: Box,
    t: Double,
): Box {
    val w = a.w * (b.w / a.w).pow(t)
    val h = a.h * (b.h / a.h).pow(t)
    val cx = a.x + a.w / 2 + (b.x + b.w / 2 - a.x - a.w / 2) * t
    val cy = a.y + a.h / 2 + (b.y + b.h / 2 - a.y - a.h / 2) * t
    return Box(cx - w / 2, cy - h / 2, w, h)
}

/**
 * What the stage shows at a moment.
 * - [view]: the part of the page (page pixels, at the stage's aspect) that fills the stage.
 * - [mask]: the only part of the page shown sharp (page pixels).
 * - [light]: 0 = black (only the episode's opening fade), 1 = full.
 * - [mix]: share of this shot in the picture (ADR-0043): under 1 during a dissolve or a page turn, the rest being
 *   the previous bubble's last framing (never black: the panel is never dimmed).
 * - [shape]: the panel's outline inside [mask] (its corners when it is a rectangle): clip the page image to it.
 * - [backdrop]: the panel whose blurred, darkened extension fills the rest of the stage.
 */
data class Framing(
    val view: Box,
    val mask: Box,
    val light: Double,
    val mix: Double,
    val shape: Quad,
    val backdrop: Box,
)

internal fun rectQuad(box: Box): Quad {
    val (x, y, w, h) = box
    return Quad(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
}

private fun lerpQuad(
    a: Quad,
    b: Quad,
    t: Double,
): Quad {
    val p = a.points.zip(b.points) { u, v -> Point(lerp(u.x, v.x, t), lerp(u.y, v.y, t)) }
    return Quad(p[0], p[1], p[2], p[3])
}

internal fun isWholePage(
    b: TimelineBubble,
    page: PageSize,
): Boolean = sameBox(b.bubbleBbox, b.panelBbox) && sameBox(b.panelBbox, Box(0.0, 0.0, page.width, page.height))

/** The part of the page shown sharp: the panel box, inset by MASK_INSET of its shorter side when it is a plain box inside the page. */
internal fun panelMask(
    b: TimelineBubble,
    page: PageSize,
): Box {
    val (x, y, w, h) = b.panelBbox
    if (b.panelShape != null || sameBox(b.panelBbox, Box(0.0, 0.0, page.width, page.height))) return Box(x, y, w, h)
    val d = MASK_INSET * min(w, h)
    return Box(x + d, y + d, w - 2 * d, h - 2 * d)
}

internal fun shapeOf(
    b: TimelineBubble,
    page: PageSize,
): Quad = b.panelShape ?: rectQuad(panelMask(b, page))

/**
 * One camera move (ADR-0040): a bubble's own, or a whole panel's shared by its bubbles. `startMs`: where the bubble
 * starts in it; `totalMs`: how long it lasts; `leadMs`: the camera's arrival at its start; `wide`: the whole panel for
 * all its bubbles, one slow drift, never re-targeted bubble by bubble.
 */
internal data class Take(
    val startMs: Double,
    val totalMs: Double,
    val leadMs: Double,
    val wide: Boolean,
)

/** `b`'s own take: its own move toward it (the default). */
internal fun single(b: TimelineBubble): Take = Take(0.0, b.durationMs.toDouble(), b.leadMs.toDouble(), false)

/**
 * Whether `b` may be read on its whole panel, with no push toward it (ADR-0040: one camera move per panel). Not when
 * the push adds something: a shout or an impact, lettering under LEGIBLE with the whole panel in frame; a wordless
 * beat or a long panel keeps its own move.
 */
internal fun wideBeat(
    b: TimelineBubble,
    aspect: Double,
    direction: Direction = Direction.Rtl,
    band: Double = CAPTION_BAND,
): Boolean {
    val words = jsTrim(b.text)
    if (words.isEmpty() || sameBox(b.bubbleBbox, b.panelBbox) || isLong(b.panelBbox)) return false
    if (b.shout || b.tone == "shout" || b.onomatopoeia.isNotEmpty()) return false
    val whole = frameView(b.panelBbox, b.bubbleBbox, aspect, 0.5, direction, null, null, band)
    return lettering(b.bubbleBbox, words) >= LEGIBLE * min(whole.w, whole.h * REF_ASPECT)
}

/**
 * Each bubble's `Take`: consecutive bubbles of one panel that are all `wideBeat` share one wide take (the camera
 * arrives once and drifts over the panel until its last bubble ends); every other bubble has its own (`single`).
 */
internal fun takes(
    bubbles: List<TimelineBubble>,
    aspect: Double,
    direction: Direction = Direction.Rtl,
    band: Double = CAPTION_BAND,
): List<Take> {
    val wide = bubbles.map { wideBeat(it, aspect, direction, band) }
    val out = mutableListOf<Take>()
    var i = 0
    while (i < bubbles.size) {
        var j = i
        while (j + 1 < bubbles.size && wide[j] && wide[j + 1]) {
            if (!samePanel(bubbles[j], bubbles[j + 1])) break
            j += 1
        }
        if (j == i) {
            out += single(bubbles[i])
        } else {
            val run = bubbles.subList(i, j + 1)
            val totalMs = run.sumOf { it.durationMs }.toDouble()
            val leadMs = bubbles[i].leadMs.toDouble()
            var startMs = 0.0
            for (b in run) {
                out += Take(startMs, totalMs, leadMs, true)
                startMs += b.durationMs
            }
        }
        i = j + 1
    }
    return out
}

private fun viewOf(
    b: TimelineBubble,
    progress: Double,
    push: Double,
    aspect: Double,
    direction: Direction,
    band: Double,
    take: Take,
): Box =
    frameView(
        b.panelBbox,
        b.bubbleBbox,
        aspect,
        progress,
        direction,
        b.detailBbox,
        settledMs(take) / 1000,
        band,
        b.keepBbox,
        push,
        take.wide,
    )

/** How long the take shows once the camera has arrived (after the lead the timeline added for the transition). */
private fun settledMs(take: Take): Double = max(1.0, take.totalMs - take.leadMs)

/** Where the take's move is at `at` ms into its bubble (0..1): it starts once the lead is over. */
private fun progressAt(
    take: Take,
    at: Double,
): Double = clamp01((take.startMs + at - take.leadMs) / settledMs(take))

private fun pushStart(
    b: TimelineBubble,
    prev: TimelineBubble?,
    take: Take,
): Double = if (samePanel(prev, b) && !take.wide) PUSH_RESTART else 0.0

/** How far the push-in is at `at` ms into `b` (0..1): it drifts in over the whole time the take is settled. */
private fun pushAt(
    b: TimelineBubble,
    prev: TimelineBubble?,
    take: Take,
    at: Double,
): Double {
    val p0 = pushStart(b, prev, take)
    return p0 + (1 - p0) * drift((take.startMs + at - take.leadMs) / settledMs(take))
}

/** The view of the take at `at` ms into `b` once the camera has arrived. */
private fun viewAt(
    b: TimelineBubble,
    prev: TimelineBubble?,
    take: Take,
    at: Double,
    aspect: Double,
    direction: Direction,
    band: Double,
): Box = viewOf(b, progressAt(take, at), pushAt(b, prev, take, at), aspect, direction, band, take)

/**
 * A beat that opens on an impact (ADR-0040): lettered sound effects, or a sound effect flagged as an impact
 * (ADR-0042). A sound without the flag (older timelines) counts, as it did.
 */
internal fun opensOnImpact(b: TimelineBubble): Boolean =
    b.onomatopoeia.isNotEmpty() || b.sounds.any { it.kind == "sfx" && it.impact != false }

/** `view` shaken `s` (0..1) of the way through an impact: a damped jolt, never a move. */
internal fun shake(
    view: Box,
    s: Double,
): Box {
    if (!(s >= 0 && s < 1)) return view
    val a = IMPACT_SHAKE * view.w * (1 - s).pow(2)
    return Box(view.x + a * sin(6 * PI * s), view.y + 0.6 * a * sin(4 * PI * s), view.w, view.h)
}

internal enum class TransitionKind {
    /** A new page dissolves in (the episode's first fades in from black). */
    Page,

    /** Ease from the last view. */
    Glide,

    /** The new shot fades in over the old one. */
    Dissolve,

    /** The take goes on. */
    None,
}

internal data class Transition(
    val kind: TransitionKind,
    val ms: Double,
    /** Where the previous bubble ended (glide and dissolve). */
    val last: Box?,
)

/**
 * How the camera would naturally arrive on `b` from `prev` (ADR-0037). A bubble that goes on with the previous one's
 * wide take does not move the camera. A new page fades in (slower for a whole-page shot); on the same page the
 * viewport glides for a time growing with the distance; a jump of more than CUT_DISTANCE heights is a dissolve.
 */
internal fun arrival(
    b: TimelineBubble,
    prev: TimelineBubble?,
    page: PageSize,
    aspect: Double,
    direction: Direction = Direction.Rtl,
    band: Double = CAPTION_BAND,
    take: Take? = null,
    prevTake: Take? = null,
): Transition {
    val own = take ?: single(b)
    if (own.startMs > 0) return Transition(TransitionKind.None, 0.0, null)
    if (prev == null || prev.page != b.page) {
        return Transition(TransitionKind.Page, if (isWholePage(b, page)) COVER_FADE_MS else FADE_MS, null)
    }
    val before = prevTake ?: single(prev)
    val last = viewAt(prev, null, before, prev.durationMs.toDouble(), aspect, direction, band)
    val first = viewOf(b, 0.0, pushStart(b, prev, own), aspect, direction, band, own)
    val dx = first.x + first.w / 2 - last.x - last.w / 2
    val dy = first.y + first.h / 2 - last.y - last.h / 2
    val d = hypot(dx, dy) / sqrt(first.h * last.h)
    if (d > CUT_DISTANCE) return Transition(TransitionKind.Dissolve, DISSOLVE_MS, last)
    val ms = min(GLIDE_MAX_MS, max(GLIDE_MIN_MS, GLIDE_BASE_MS + GLIDE_PER_VIEW_MS * d))
    return Transition(TransitionKind.Glide, ms, last)
}

/**
 * How `b` takes over from `prev`: its `arrival`, never longer than the lead the timeline gave the bubble (ADR-0039).
 * A glide may be shortened to GLIDE_SHORTEN of its time; one that would have to go faster (a whip) is a dissolve.
 */
internal fun transition(
    b: TimelineBubble,
    prev: TimelineBubble?,
    page: PageSize,
    aspect: Double,
    direction: Direction = Direction.Rtl,
    band: Double = CAPTION_BAND,
    take: Take? = null,
    prevTake: Take? = null,
): Transition {
    val move = arrival(b, prev, page, aspect, direction, band, take, prevTake)
    if (move.kind != TransitionKind.Glide) return move
    val lead = b.leadMs.toDouble()
    val budget = if (lead > 0) lead else GLIDE_SHARE * b.durationMs
    if (budget >= GLIDE_SHORTEN * move.ms) return move.copy(ms = min(move.ms, budget))
    val ms = min(DISSOLVE_MS, GLIDE_SHARE * b.durationMs)
    return Transition(TransitionKind.Dissolve, ms, move.last)
}

/**
 * What to show at `elapsed` ms into `b` (ADR-0033, ADR-0037, ADR-0040): after the `transition` (fade, glide or
 * dissolve), the take's move, which drifts on until the take ends. During a glide the viewport, the panel mask and
 * the backdrop ease from where `prev` ended; a dissolve or a new page shows the new shot at full light, its share of
 * the picture (`mix`) easing from 0 to 1 over the previous bubble's last framing; only the episode's first shot fades
 * in from black (`light`). A beat that opens on an impact shakes for IMPACT_MS. `still` (reduced motion): the whole
 * panel, held, never shaken.
 */
internal fun framingAt(
    b: TimelineBubble,
    prev: TimelineBubble?,
    elapsed: Double,
    still: Boolean,
    page: PageSize,
    aspect: Double,
    direction: Direction = Direction.Rtl,
    band: Double = CAPTION_BAND,
    take: Take? = null,
    prevTake: Take? = null,
): Framing {
    val own = take ?: single(b)
    val mask = panelMask(b, page)
    val shape = shapeOf(b, page)
    val panel = b.panelBbox
    if (still) {
        val view = viewOf(b, 0.5, 0.0, aspect, direction, band, own)
        return Framing(view, mask, 1.0, 1.0, shape, panel)
    }
    val move = transition(b, prev, page, aspect, direction, band, own, prevTake)
    if (move.kind == TransitionKind.None) {
        val view = viewAt(b, prev, own, elapsed, aspect, direction, band) // the take goes on
        return Framing(view, mask, 1.0, 1.0, shape, panel)
    }
    // During a glide the target is where the bubble's own move starts, so it is one clean ease.
    val at = if (move.kind == TransitionKind.Glide) max(elapsed, move.ms) else elapsed
    var view = viewAt(b, prev, own, at, aspect, direction, band)
    val t = clamp01(elapsed / move.ms)
    val jolt = opensOnImpact(b)
    if (move.kind == TransitionKind.Page && prev == null) {
        // The episode opens: a fade from black.
        if (jolt) view = shake(view, elapsed / IMPACT_MS)
        return Framing(view, mask, t * (2 - t), 1.0, shape, panel)
    }
    if (move.kind != TransitionKind.Glide || prev == null || move.last == null) {
        // A new page or a dissolve: the new shot at full light over the old one.
        if (jolt) view = shake(view, elapsed / IMPACT_MS)
        return Framing(view, mask, 1.0, ease(t), shape, panel)
    }
    val k = easeInOut(t)
    val glide = lerpView(move.last, view, k)
    return Framing(
        view = if (jolt) shake(glide, elapsed / IMPACT_MS) else glide,
        mask = lerpBox(panelMask(prev, page), mask, k),
        light = 1.0,
        mix = 1.0,
        shape = lerpQuad(shapeOf(prev, page), shape, k),
        backdrop = lerpBox(prev.panelBbox, panel, k),
    )
}

/** Where the panel ends in the frame (0 = top, 1 = bottom) once `b` has settled. */
internal fun captionAnchor(
    b: TimelineBubble,
    prev: TimelineBubble?,
    page: PageSize,
    aspect: Double,
    direction: Direction = Direction.Rtl,
    band: Double = CAPTION_BAND,
    take: Take? = null,
    prevTake: Take? = null,
): Double {
    val shot = framingAt(b, prev, b.durationMs.toDouble(), false, page, aspect, direction, band, take, prevTake)
    val bottom = shot.shape.points.maxOf { it.y }
    return (bottom - shot.view.y) / shot.view.h
}

/**
 * How tall (page pixels) the lettering of `text` in its text `box` is: the box's area shared among its characters
 * (Latin by its spaced letters, CJK by its square glyphs), a capital's or an ideograph's height of that em.
 */
internal fun lettering(
    box: Box,
    text: String,
): Double {
    val cjk = captionLang(text) != null
    val words = jsWords(text)
    val count = chars(if (cjk) words.joinToString("") else words.joinToString(" "))
    if (count == 0 || box.w <= 0 || box.h <= 0) return 0.0
    val cell = if (cjk) LETTER_CELL_CJK else LETTER_CELL_LATIN
    val em = minOf(sqrt((box.w * box.h) / (count * cell)), box.w, box.h)
    return em * if (cjk) LETTER_HEIGHT_CJK else LETTER_HEIGHT_LATIN
}

private fun within(
    box: Box,
    area: Box,
    slack: Double,
): Boolean =
    box.x >= area.x - slack &&
        box.y >= area.y - slack &&
        box.x + box.w <= area.x + area.w + slack &&
        box.y + box.h <= area.y + area.h + slack

/**
 * Whether `b`'s caption is shown (ADR-0040): only when its balloon cannot be read in frame, so a legible balloon is
 * not read twice. A beat without a balloon is not concerned (true).
 */
internal fun captionShown(
    b: TimelineBubble,
    prev: TimelineBubble?,
    aspect: Double,
    direction: Direction = Direction.Rtl,
    band: Double = CAPTION_BAND,
    caption: String? = null,
    take: Take? = null,
): Boolean {
    val own = take ?: single(b)
    val words = jsTrim(b.text)
    val box = b.bubbleBbox
    if (words.isEmpty() || sameBox(box, b.panelBbox)) return true
    val line = caption ?: (if (!b.display.isNullOrEmpty()) b.display else b.text)
    if (captionLang(line) != captionLang(words)) return true
    val tall = lettering(box, words)
    val slack = 0.01 * min(box.w, box.h)
    for (at in listOf(b.leadMs.toDouble(), b.durationMs.toDouble())) {
        val view = viewAt(b, prev, own, at, aspect, direction, band)
        if (!within(box, view, slack) || !within(box, b.panelBbox, slack)) return true
        if (tall < LEGIBLE * min(view.w, view.h * REF_ASPECT)) return true
    }
    return false
}

/** Every text box of `b`'s panel (its own balloon, the others, narration boxes): what a caption must never cover. */
internal fun panelLetters(
    bubbles: List<TimelineBubble>,
    b: TimelineBubble,
): List<Box> =
    bubbles
        .filter { o -> samePanel(o, b) && jsTrim(o.text).isNotEmpty() && !sameBox(o.bubbleBbox, o.panelBbox) }
        .map { it.bubbleBbox }

/** A caption's place: its block's top (stage heights) and whether it may cross the panel (then it gets a shade). */
internal data class Slot(
    val top: Double,
    val overArt: Boolean,
)

/**
 * Where a caption block `height` stage heights tall (shade included) and `width` stage widths wide (centred) sits for
 * `b` (ADR-0043), or null when no place is clear of the panel's lettering. Under the panel's lowest bottom when the
 * block fits above `limit`; else the lowest clear place between `limit` and CAPTION_TOP, scanning up by CAPTION_STEP.
 */
internal fun captionSlot(
    b: TimelineBubble,
    prev: TimelineBubble?,
    letters: List<Box>,
    page: PageSize,
    aspect: Double,
    height: Double,
    width: Double,
    limit: Double,
    direction: Direction = Direction.Rtl,
    band: Double = CAPTION_BAND,
    take: Take? = null,
    prevTake: Take? = null,
): Slot? {
    val panels = mutableListOf<Pair<Double, Double>>()
    val boxes = mutableListOf<DoubleArray>()
    for (k in 0..SLOT_SAMPLES) {
        val at = (b.durationMs.toDouble() * k) / SLOT_SAMPLES
        val shot = framingAt(b, prev, at, false, page, aspect, direction, band, take, prevTake)
        val (vx, vy, vw, vh) = shot.view
        val ys = shot.shape.points.map { (it.y - vy) / vh }
        panels += ys.min() to ys.max()
        for (q in letters) {
            boxes += doubleArrayOf((q.x - vx) / vw, (q.y - vy) / vh, (q.x + q.w - vx) / vw, (q.y + q.h - vy) / vh)
        }
    }
    val x0 = 0.5 - width / 2
    val x1 = 0.5 + width / 2
    val clear = CAPTION_CLEAR
    val under = panels.maxOf { it.second } + CAPTION_GAP
    if (under >= 0 && under + height <= limit) return Slot(under, false)
    val steps = floor((limit - height - CAPTION_TOP) / CAPTION_STEP + 1e-6).toInt()
    for (i in 0..steps) {
        val top = limit - height - i * CAPTION_STEP
        val bottom = top + height
        val hit = boxes.any { q -> q[0] - clear < x1 && q[2] + clear > x0 && q[1] - clear < bottom && q[3] + clear > top }
        if (!hit) return Slot(top, panels.any { it.first < bottom && it.second > top })
    }
    return null
}
