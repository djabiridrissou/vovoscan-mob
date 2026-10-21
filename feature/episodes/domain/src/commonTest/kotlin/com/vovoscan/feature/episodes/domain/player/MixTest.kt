package com.vovoscan.feature.episodes.domain.player

import com.vovoscan.feature.episodes.domain.Box
import com.vovoscan.feature.episodes.domain.BubbleSound
import com.vovoscan.feature.episodes.domain.LoopCue
import com.vovoscan.feature.episodes.domain.TimelineBubble
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** mix.test.ts, ported: the shared vectors (checked against soundbed.py by the worker's tests too) and the curves. */
class MixTest {
    private val vectors = VectorJson.parse(Vectors.mix)

    private fun ducks(o: JsonObject) =
        o.getValue("ducks").jsonArray.map { d ->
            d.jsonArray.let { Duck(it[0].jsonPrimitive.content.toDouble(), it[1].jsonPrimitive.content.toDouble()) }
        }

    private fun constants(o: JsonObject) =
        o.getValue("constants").jsonObject.mapValues {
            it.value.jsonPrimitive.content
                .toDouble()
        }

    @Test
    fun usesTheSameConstants() {
        assertEquals(
            mapOf(
                "BED_XFADE_MS" to BED_XFADE_MS,
                "BED_FADE_IN_MS" to BED_FADE_IN_MS,
                "BED_FADE_OUT_MS" to BED_FADE_OUT_MS,
                "DUCK_GAIN" to DUCK_GAIN,
                "DUCK_ATTACK_MS" to DUCK_ATTACK_MS,
                "DUCK_RELEASE_MS" to DUCK_RELEASE_MS,
                "SOUND_FADE_MS" to SOUND_FADE_MS,
                "VOICE_TAIL_MS" to VOICE_TAIL_MS,
            ),
            constants(vectors),
        )
        assertEquals(
            mapOf(
                "MUSIC_XFADE_MS" to MUSIC_XFADE_MS,
                "MUSIC_FADE_IN_MS" to MUSIC_FADE_IN_MS,
                "MUSIC_FADE_OUT_MS" to MUSIC_FADE_OUT_MS,
                "MUSIC_DUCK_GAIN" to MUSIC_DUCK_GAIN,
            ),
            constants(vectors.getValue("music").jsonObject),
        )
    }

    @Test
    fun bedsAndDuckingMatchTheExport() {
        val beds = vectors.getValue("beds").jsonArray.map { it.loop() }
        val ducks = ducks(vectors)
        for (c in vectors.cases("cases")) {
            val t = c.num("t")!!
            assertClose(c.num("duck")!!, duckAt(ducks, t), 9, "duck at $t")
            c.getValue("gains").jsonArray.forEachIndexed {
                i,
                g,
                ->
                assertClose(g.jsonPrimitive.content.toDouble(), bedGain(beds, i, t), 9, "bed $i at $t")
            }
        }
    }

    @Test
    fun scoreCuesAndDuckingMatchTheExport() {
        val music = vectors.getValue("music").jsonObject
        val cues = music.getValue("cues").jsonArray.map { it.loop() }
        val ducks = ducks(music)
        for (c in music.cases("cases")) {
            val t = c.num("t")!!
            assertClose(c.num("duck")!!, duckAt(ducks, t, MUSIC_DUCK_GAIN), 9, "duck at $t")
            c.getValue("gains").jsonArray.forEachIndexed { i, g ->
                assertClose(g.jsonPrimitive.content.toDouble(), bedGain(cues, i, t, SCORE), 9, "cue $i at $t")
            }
        }
    }

    @Test
    fun pansSweepsAndPanelSoundsMatchTheExport() {
        for (c in vectors.cases("pans")) {
            val (l, r) = panGains(c.num("pan")!!)
            val gains = c.getValue("gains").jsonArray.map { it.jsonPrimitive.content.toDouble() }
            assertClose(gains[0], l, 9, "left at ${c.num("pan")}")
            assertClose(gains[1], r, 9, "right at ${c.num("pan")}")
        }
        for (c in vectors.cases("sweeps")) {
            assertClose(c.num("at")!!, panAt(c.num("pan")!!, c.num("panEnd"), c.num("length")!!, c.num("t")!!), 9, "sweep")
        }
        val ducks = ducks(vectors)
        for (c in vectors.cases("sounds")) {
            assertClose(c.num("gain")!!, soundGain(ducks, c.num("span")!!, c.num("t")!!, c.num("at")!!), 9, "sound")
        }
    }

    @Test
    fun panGainsKeepThePowerConstantAndMatchTheStereoPannerLaw() {
        var p = -1.0
        while (p <= 1) {
            val (l, r) = panGains(p)
            assertClose(1.0, l * l + r * r, 12)
            val x = (p + 1) / 2
            assertClose(cos(x * PI / 2), l, 12)
            assertClose(sin(x * PI / 2), r, 12)
            p += 0.25
        }
        assertClose(0.0, panGains(-1.0).second, 12)
    }

    private val beds = listOf(LoopCue("a", 4_000, 0, 10_000), LoopCue("b", 3_000, 10_000, 20_000))

    @Test
    fun bedsLoopTheClipAndNeverLeaveAGap() {
        var t = 0.0
        while (t < 20_000) {
            val live = bedsAt(beds, emptyList(), t)
            assertTrue(live.isNotEmpty(), "silence at $t")
            if (t >= 600 && t < 18_000) assertClose(1.0, live.sumOf { it.gain * it.gain }, 6, "power at $t")
            t += 250
        }
        assertEquals(listOf(LiveBed(0, 1.0, 1_000.0)), bedsAt(beds, emptyList(), 9_000.0))
        // Crossfade: both beds, the new one from the top of its clip.
        assertEquals(listOf(0 to 10_750.0 % 4_000, 1 to 750.0), bedsAt(beds, emptyList(), 10_750.0).map { it.index to it.offsetMs })
        assertEquals(emptyList(), bedsAt(beds, emptyList(), 20_000.0))
    }

    @Test
    fun bedsDuckUnderAVoiceLine() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        val ducks =
            duckIntervals(
                listOf(
                    TimelineBubble(panelBbox = box, bubbleBbox = box, durationMs = 2_000),
                    TimelineBubble(panelBbox = box, bubbleBbox = box, durationMs = 2_500, audioKey = "audio/x.ogg"),
                    TimelineBubble(panelBbox = box, bubbleBbox = box, durationMs = 3_000),
                ),
            )
        assertEquals(listOf(Duck(2_000.0, 4_000.0)), ducks)
        assertClose(DUCK_GAIN, bedsAt(beds, ducks, 3_000.0)[0].gain, 9)
        assertEquals(1.0, bedsAt(beds, ducks, 4_000 + DUCK_RELEASE_MS)[0].gain)
    }

    @Test
    fun theScorePlaysWithoutAGapAndCrossfadesOnAChangeOfMood() {
        val cues = listOf(LoopCue("m1", 60_000, 0, 30_000), LoopCue("m2", 45_000, 30_000, 60_000))
        var t = MUSIC_FADE_IN_MS
        while (t < 60_000 - MUSIC_FADE_OUT_MS) {
            assertClose(1.0, bedsAt(cues, emptyList(), t, SCORE).sumOf { it.gain * it.gain }, 6, "power at $t")
            t += 250
        }
        assertEquals(listOf(0, 1), bedsAt(cues, emptyList(), 31_500.0, SCORE).map { it.index })
        assertEquals(listOf(1), bedsAt(cues, emptyList(), 30_000 + MUSIC_XFADE_MS, SCORE).map { it.index })
        // The cue keeps its place in the loop: it never restarts on a page turn.
        assertEquals(20_000.0, bedsAt(cues, emptyList(), 20_000.0, SCORE)[0].offsetMs)
        // ... and dips less than the beds under a line.
        assertClose(MUSIC_DUCK_GAIN, bedsAt(cues, listOf(Duck(10_000.0, 12_000.0)), 11_000.0, SCORE)[0].gain, 9)
        assertTrue(MUSIC_DUCK_GAIN > DUCK_GAIN)
    }

    @Test
    fun neverStartsTheSameSampleAThirdTimeWithinTheRepeatWindow() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        val hit = BubbleSound("sfx/hit", 500, "sfx", 600)
        val other = BubbleSound("sfx/other", 500, "sfx", 600)
        val bubbles =
            (0..7).map {
                TimelineBubble(
                    panelBbox = box,
                    bubbleBbox = box,
                    durationMs = 600,
                    sounds =
                        if (it ==
                            3
                        ) {
                            listOf(hit, other)
                        } else {
                            listOf(hit)
                        },
                )
            }
        val kept = keptSounds(bubbles)
        val starts = kept.flatMapIndexed { i, xs -> xs.filter { it.audioKey == hit.audioKey }.map { i * 600.0 } }
        for (t in starts) assertTrue(starts.count { t - REPEAT_WINDOW_MS < it && it <= t } <= REPEAT_MAX)
        assertEquals(listOf(0.0, 600.0, 3000.0, 3600.0), starts)
        assertEquals(listOf(other), kept[3])
    }
}
