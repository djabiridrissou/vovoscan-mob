package com.vovoscan.feature.episodes.domain

/** A box in page pixels: left, top, width, height. */
data class Box(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
) {
    operator fun get(index: Int): Double =
        when (index) {
            0 -> x
            1 -> y
            2 -> w
            3 -> h
            else -> throw IndexOutOfBoundsException("Box has 4 values, not $index")
        }

    val right: Double get() = x + w
    val bottom: Double get() = y + h
}

data class Point(
    val x: Double,
    val y: Double,
)

/** A panel outline: its four corners, clockwise from the top left (slanted gutters included). */
data class Quad(
    val p0: Point,
    val p1: Point,
    val p2: Point,
    val p3: Point,
) {
    val points: List<Point> get() = listOf(p0, p1, p2, p3)
}

/** Page size in pixels. */
data class PageSize(
    val width: Double,
    val height: Double,
)

/** Reading direction of the chapter (from the analysis panel order): manga read right to left. */
enum class Direction { Rtl, Ltr }

/** A sound placed on a bubble's panel: a sound effect or an ambience, heard for at most [spanMs]. */
data class BubbleSound(
    val audioKey: String,
    val durationMs: Int,
    /** "sfx" or "ambience". */
    val kind: String,
    val spanMs: Int,
    /** −1 left … +1 right. */
    val pan: Double = 0.0,
    /** A moving source sweeps from [pan] to here over the sound. */
    val panEnd: Double? = null,
    /** An impact (hit, explosion…) shakes the camera; null on older timelines, which counts as one. */
    val impact: Boolean? = true,
)

/**
 * One beat of the episode: a bubble (or a wordless panel when [bubbleBbox] equals [panelBbox]), its line and how long
 * it lasts. Boxes in page pixels. Field for field the Timeline contract (v1, the backend's `timeline.schema.json`).
 */
data class TimelineBubble(
    val id: String = "",
    val page: Int = 0,
    val panelBbox: Box,
    val bubbleBbox: Box,
    val text: String = "",
    /** The natural-case line read by the OCR, for captions; null without one. */
    val display: String? = null,
    val shout: Boolean = false,
    /** normal, shout, whisper, thought, trembling, electronic, narration. */
    val tone: String = "normal",
    /** The voiced line; null when silent. */
    val audioKey: String? = null,
    val durationMs: Int,
    /** The camera's arrival time, included in [durationMs]. */
    val leadMs: Int = 0,
    val onomatopoeia: List<String> = emptyList(),
    val panelShape: Quad? = null,
    val detailBbox: Box? = null,
    val keepBbox: Box? = null,
    val sounds: List<BubbleSound> = emptyList(),
    /** The speaker's place in the panel, −1 … +1. */
    val pan: Double = 0.0,
)

data class TimelinePage(
    val index: Int,
    val width: Int,
    val height: Int,
)

/** A looping layer: an ambience bed or a cue of the score, from [startMs] until the next one or the end. */
data class LoopCue(
    val audioKey: String,
    /** Length of the clip (it loops). */
    val durationMs: Int,
    val startMs: Long,
    val endMs: Long,
    /** Score cues only. */
    val mood: String? = null,
)

data class Timeline(
    val chapterHash: String,
    val renderKey: String,
    val pages: List<TimelinePage>,
    val bubbles: List<TimelineBubble>,
    val direction: Direction = Direction.Rtl,
    val beds: List<LoopCue> = emptyList(),
    val music: List<LoopCue> = emptyList(),
)
