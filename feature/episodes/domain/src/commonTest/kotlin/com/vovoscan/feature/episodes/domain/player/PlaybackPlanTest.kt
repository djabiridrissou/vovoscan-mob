package com.vovoscan.feature.episodes.domain.player

import com.vovoscan.feature.episodes.domain.Box
import com.vovoscan.feature.episodes.domain.PageSize
import com.vovoscan.feature.episodes.domain.Timeline
import com.vovoscan.feature.episodes.domain.TimelineBubble
import com.vovoscan.feature.episodes.domain.TimelinePage
import com.vovoscan.feature.episodes.domain.Watch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackPlanTest {
    /** The backend's valid timeline fixture: two bubbles on one page, a voiced line, a sweeping effect, a bed and a cue. */
    private val timeline: Timeline =
        Json
            .parseToJsonElement(Vectors.timeline)
            .jsonArray[0]
            .jsonObject
            .timeline()

    private fun watch(t: Timeline = timeline) =
        Watch(
            chapterId = "c1",
            renderKey = t.renderKey,
            timeline = t,
            pageUrls = t.pages.map { "https://api.test/media/pages/${it.index}?sig=x" },
            audioUrls =
                (
                    t.bubbles.flatMap { b -> listOfNotNull(b.audioKey) + b.sounds.map { it.audioKey } } + t.beds.map { it.audioKey } +
                        t.music.map { it.audioKey }
                ).associateWith { "https://api.test/media/$it?sig=x" },
            expiresAtMillis = 0,
        )

    private val plan = PlaybackPlan(watch())

    @Test
    fun timeMapsToBeats() {
        assertEquals(3600.0, plan.durationMs)
        assertEquals(2, plan.beatCount)
        assertEquals(0, plan.beatAt(0.0))
        assertEquals(0, plan.beatAt(1799.9))
        assertEquals(1, plan.beatAt(1800.0))
        assertEquals(1, plan.beatAt(99_999.0))
        assertEquals(1, plan.pageNumberOf(1))
        assertNull(plan.nextPageBeat(0))
        assertEquals(0, plan.previousPageBeat(1))
        assertFalse(plan.silent)
    }

    @Test
    fun theFrameIsTheCameraOfTheBeat() {
        val (w, h) = 390.0 to 693.0
        val aspect = w / h
        val list = takes(timeline.bubbles, aspect, timeline.direction)
        val page = PageSize(800.0, 1200.0)
        val frame = assertNotNull(plan.frame(2500.0, w, h))
        val b = timeline.bubbles[1]
        assertEquals(1, frame.beat)
        assertEquals("b0002", frame.bubbleId)
        assertEquals(
            framingAt(b, timeline.bubbles[0], 700.0, false, page, aspect, timeline.direction, CAPTION_BAND, list[1], list[0]),
            frame.shot.framing,
        )
        assertEquals(PlaybackPlan.fitTransform(frame.shot.framing.view, w, h), frame.shot.transform)
        assertEquals("https://api.test/media/pages/0?sig=x", frame.shot.pageUrl)
        assertEquals(b.bubbleBbox, frame.shot.bubbleBox)
        // Paused at a beat's start: the camera shows where its transition settles.
        val paused = assertNotNull(plan.frame(1800.0, w, h, playing = false))
        val settle = transition(b, timeline.bubbles[0], page, aspect, timeline.direction, CAPTION_BAND, list[1], list[0]).ms
        assertEquals(
            framingAt(b, timeline.bubbles[0], settle, false, page, aspect, timeline.direction, CAPTION_BAND, list[1], list[0]),
            paused.shot.framing,
        )
        // The episode opens from black.
        assertTrue(assertNotNull(plan.frame(0.0, w, h)).veil > 0.99)
        assertNull(plan.frame(10.0, 0.0, 0.0))
    }

    @Test
    fun theCaptionFollowsItsCards() {
        val frame = assertNotNull(plan.frame(500.0, 390.0, 693.0))
        val caption = assertNotNull(frame.caption)
        assertEquals("We're not done yet!", caption.text)
        assertEquals(listOf("We're not done yet!"), caption.cards)
        assertNull(caption.lang)
        assertFalse(caption.small)
        // The second line follows a caption: it waits for the short blank first.
        assertEquals("", assertNotNull(plan.frame(1820.0, 390.0, 693.0)).caption?.text)
        // Big lettering, legible in frame: not drawn (kept for screen readers).
        assertFalse(caption.needed)
        assertFalse(plan.captionPlacement(0, 390.0, 693.0, 60.0, 280.0).visible)
        assertFalse(plan.captionPlacement(5, 390.0, 693.0, 60.0, 280.0).visible)
        // Tiny lettering: the caption is needed, and goes under the panel.
        val tiny = timeline.copy(bubbles = timeline.bubbles.map { it.copy(bubbleBbox = Box(40.0, 40.0, 60.0, 20.0)) })
        val small = PlaybackPlan(watch(tiny))
        assertTrue(assertNotNull(assertNotNull(small.frame(500.0, 390.0, 693.0)).caption).needed)
        val placement = small.captionPlacement(0, 390.0, 693.0, 60.0, 280.0)
        assertTrue(placement.visible)
        assertTrue(placement.top in 0.0..1.0)
    }

    @Test
    fun theMixSaysWhatPlaysAndHowLoud() {
        val ducks = duckIntervals(timeline.bubbles)
        val atLine = plan.audio(2000.0)
        val voice = atLine.single { it.kind == AudioKind.Voice }
        assertEquals("chapters/x/audio/y.ogg", voice.audioKey)
        assertEquals("https://api.test/media/chapters/x/audio/y.ogg?sig=x", voice.url)
        assertEquals(200.0, voice.offsetMs)
        val sound = atLine.single { it.kind == AudioKind.Sound }
        assertClose(soundGain(ducks, 1800.0, 200.0, 2000.0), sound.gain, 9)
        assertClose(panAt(0.8, -0.6, 1500.0, 200.0), sound.pan, 9)
        assertEquals("sound:1:0", sound.id)
        val bed = atLine.single { it.kind == AudioKind.Bed }
        assertTrue(bed.loop)
        assertClose(bedsAt(timeline.beds, ducks, 2000.0, BEDS)[0].gain, bed.gain, 9)
        val cue = atLine.single { it.kind == AudioKind.Music }
        assertClose(bedsAt(timeline.music, ducks, 2000.0, SCORE)[0].gain, cue.gain, 9)
        // Before the line: no voice; the effect is over once its clip has been heard.
        assertTrue(plan.audio(1000.0).none { it.kind == AudioKind.Voice || it.kind == AudioKind.Sound })
        assertTrue(plan.audio(1800.0 + 1600).none { it.kind == AudioKind.Sound })
        assertEquals(
            listOf("https://api.test/media/chapters/x/audio/y.ogg?sig=x", "https://api.test/media/pages/0?sig=x"),
            plan.prefetchUrls(0),
        )
    }

    @Test
    fun aTimelineWithoutSoundIsSilentAndPagesNavigate() {
        val box = Box(10.0, 10.0, 780.0, 500.0)
        val quiet =
            Timeline(
                chapterHash = "",
                renderKey = "r",
                pages = listOf(TimelinePage(0, 800, 1200), TimelinePage(1, 800, 1200)),
                bubbles =
                    listOf(
                        TimelineBubble(id = "b0001", page = 0, panelBbox = box, bubbleBbox = box, durationMs = 1000),
                        TimelineBubble(id = "b0002", page = 0, panelBbox = box, bubbleBbox = box, durationMs = 1000),
                        TimelineBubble(id = "b0003", page = 1, panelBbox = box, bubbleBbox = box, durationMs = 1000),
                    ),
            )
        val p = PlaybackPlan(watch(quiet))
        assertTrue(p.silent)
        assertTrue(p.audio(500.0).isEmpty())
        assertEquals(2, p.nextPageBeat(0))
        assertEquals(2, p.pageNumberOf(2))
        assertEquals(0, p.previousPageBeat(2))
        assertEquals(2, p.previousPageBeat(2).let { p.nextPageBeat(it) })
        assertNull(assertNotNull(p.frame(500.0, 390.0, 693.0)).caption)
    }
}
