package com.vovoscan.feature.episodes.domain.player

import com.vovoscan.feature.episodes.domain.BubbleSound
import com.vovoscan.feature.episodes.domain.LoopCue
import com.vovoscan.feature.episodes.domain.TimelineBubble
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The episode mix (ADR-0036, ADR-0041): a line-for-line port of the old web player's mix.ts (itself a port of the
 * worker's soundbed.py): bed and score cue fades and crossfades, ducking under lines, constant-power pan, pan sweeps,
 * panel sound gain, checked against the shared vectors (fixtures/mix.json). These are the only gains: every player
 * sounds like the MP4.
 */

internal const val BED_XFADE_MS = 1_500.0
internal const val BED_FADE_IN_MS = 500.0
internal const val BED_FADE_OUT_MS = 2_000.0

/** About −6 dB under a voice line. */
internal const val DUCK_GAIN = 0.5
internal const val DUCK_ATTACK_MS = 120.0
internal const val DUCK_RELEASE_MS = 450.0

/** A panel sound fades out over the end of its panel instead of being cut. */
internal const val SOUND_FADE_MS = 250.0

/** The breath after a voiced line (timeline.py VOICE_TAIL_MS): not part of the line. */
internal const val VOICE_TAIL_MS = 500.0

/** The score (ADR-0041): slower crossfades than the beds, ducked less (about −4 dB). */
internal const val MUSIC_XFADE_MS = 3_000.0
internal const val MUSIC_FADE_IN_MS = 1_500.0
internal const val MUSIC_FADE_OUT_MS = 3_000.0
internal const val MUSIC_DUCK_GAIN = 0.63

/** The same sample starts at most REPEAT_MAX times in any REPEAT_WINDOW_MS (ADR-0043). */
internal const val REPEAT_WINDOW_MS = 3_000.0
internal const val REPEAT_MAX = 2

/** The curves of one looping layer: ambience beds or the score's cues (soundbed.Layer). */
internal data class Layer(
    val xfadeMs: Double,
    val fadeInMs: Double,
    val fadeOutMs: Double,
    val duckGain: Double,
)

internal val BEDS = Layer(BED_XFADE_MS, BED_FADE_IN_MS, BED_FADE_OUT_MS, DUCK_GAIN)
internal val SCORE = Layer(MUSIC_XFADE_MS, MUSIC_FADE_IN_MS, MUSIC_FADE_OUT_MS, MUSIC_DUCK_GAIN)

/** A voice line, [start, end] in episode ms. */
internal data class Duck(
    val start: Double,
    val end: Double,
)

private fun clamp(x: Double) = min(1.0, max(0.0, x))

/**
 * [left, right] gains of a mono source at `pan` (−1 left, +1 right): constant power, the centre at −3 dB a side. The
 * same law as Web Audio's StereoPannerNode and AVAudioPlayer's `pan` for a mono input.
 */
internal fun panGains(pan: Double): Pair<Double, Double> {
    val a = ((min(1.0, max(-1.0, pan)) + 1) * PI) / 4
    return cos(a) to sin(a)
}

/** Position `t` ms into a sound heard for `length` ms: a moving source travels linearly from `pan` to `panEnd`. */
internal fun panAt(
    pan: Double,
    panEnd: Double?,
    length: Double,
    t: Double,
): Double {
    if (panEnd == null || length <= 0) return pan
    return pan + (panEnd - pan) * clamp(t / length)
}

/**
 * The panel sounds each bubble actually starts: every one, except a clip that would start a REPEAT_MAX + 1-th time
 * within REPEAT_WINDOW_MS (export.write_mix skips the same ones).
 */
internal fun keptSounds(bubbles: List<TimelineBubble>): List<List<BubbleSound>> {
    val started = mutableListOf<Pair<Double, String>>()
    var t = 0.0
    return bubbles.map { b ->
        val at = t
        t += b.durationMs
        b.sounds.filter { x ->
            val recent = started.count { (s0, k) -> k == x.audioKey && at - REPEAT_WINDOW_MS < s0 && s0 <= at }
            if (recent >= REPEAT_MAX) {
                false
            } else {
                started += at to x.audioKey
                true
            }
        }
    }
}

/** Gain of a panel sound `t` ms after it started (episode time `at`): ducked like the bed, faded out at its panel's end. */
internal fun soundGain(
    ducks: List<Duck>,
    span: Double,
    t: Double,
    at: Double,
): Double = duckAt(ducks, at) * clamp((span - t) / SOUND_FADE_MS)

/** [start, end] in ms of every voice line: a voiced bubble without its closing breath. */
internal fun duckIntervals(bubbles: List<TimelineBubble>): List<Duck> {
    val out = mutableListOf<Duck>()
    var t = 0.0
    for (b in bubbles) {
        if (!b.audioKey.isNullOrEmpty()) out += Duck(t, t + max(1.0, b.durationMs - VOICE_TAIL_MS))
        t += b.durationMs
    }
    return out
}

/**
 * Bed gain from the voice lines at `t`: 1, down to `gain` during a line, with a linear attack before it and a
 * release after.
 */
internal fun duckAt(
    ducks: List<Duck>,
    t: Double,
    gain: Double = DUCK_GAIN,
): Double {
    // Last line whose attack has begun (bisect_right on the starts).
    var lo = 0
    var hi = ducks.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (ducks[mid].start <= t + DUCK_ATTACK_MS) lo = mid + 1 else hi = mid
    }
    var depth = 0.0
    for (j in lo - 1 downTo 0) {
        val (a, b) = ducks[j]
        val d =
            when {
                t < a -> 1 - (a - t) / DUCK_ATTACK_MS
                t <= b -> 1.0
                else -> 1 - (t - b) / DUCK_RELEASE_MS
            }
        depth = max(depth, d)
        if (b + DUCK_RELEASE_MS <= t) break // earlier lines ended earlier still
    }
    return 1 - (1 - gain) * clamp(depth)
}

/** Where bed (or cue) `i` falls silent: after the crossfade into the next one, or at the end. */
internal fun bedStop(
    beds: List<LoopCue>,
    i: Int,
    layer: Layer = BEDS,
): Double {
    val bed = beds[i]
    return if (i + 1 == beds.size) bed.endMs.toDouble() else bed.endMs + layer.xfadeMs
}

/** Gain of bed (or cue) `i` at `t` before ducking: equal-power fade in from its start, out after its end. */
internal fun bedGain(
    beds: List<LoopCue>,
    i: Int,
    t: Double,
    layer: Layer = BEDS,
): Double {
    val bed = beds[i]
    if (t < bed.startMs || t >= bedStop(beds, i, layer)) return 0.0
    val fadeIn = if (i == 0) layer.fadeInMs else layer.xfadeMs
    var g = sin((PI / 2) * clamp((t - bed.startMs) / fadeIn))
    if (i + 1 == beds.size) {
        val out = layer.fadeOutMs
        g *= cos((PI / 2) * clamp((t - (bed.endMs - out)) / out))
    } else {
        g *= cos((PI / 2) * clamp((t - bed.endMs) / layer.xfadeMs))
    }
    return g
}

internal data class LiveBed(
    val index: Int,
    /** Volume factor 0..1: fades, crossfade and ducking. */
    val gain: Double,
    /** Position in the (looping) clip, ms. */
    val offsetMs: Double,
)

/** The beds (or the score's cues, with `SCORE`) audible at `t`: at most two, during a crossfade. */
internal fun bedsAt(
    beds: List<LoopCue>,
    ducks: List<Duck>,
    t: Double,
    layer: Layer = BEDS,
): List<LiveBed> {
    val out = mutableListOf<LiveBed>()
    val duck = duckAt(ducks, t, layer.duckGain)
    beds.forEachIndexed { index, bed ->
        if (t < bed.startMs || t >= bedStop(beds, index, layer)) return@forEachIndexed
        out += LiveBed(index, bedGain(beds, index, t, layer) * duck, (t - bed.startMs) % bed.durationMs)
    }
    return out
}
