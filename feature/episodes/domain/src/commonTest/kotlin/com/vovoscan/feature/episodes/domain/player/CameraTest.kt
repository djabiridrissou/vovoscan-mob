package com.vovoscan.feature.episodes.domain.player

import com.vovoscan.feature.episodes.domain.Box
import com.vovoscan.feature.episodes.domain.BubbleSound
import com.vovoscan.feature.episodes.domain.Direction
import com.vovoscan.feature.episodes.domain.PageSize
import com.vovoscan.feature.episodes.domain.TimelineBubble
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** camera.test.ts, ported: the shared vectors (checked against export.py by the worker's tests too) and the QA gates. */
class CameraTest {
    private val vectors = VectorJson.parse(Vectors.framing)

    @Test
    fun frameViewMatchesTheExport() {
        for (c in vectors.cases("frameView")) {
            val name = c.str("name")!!
            val aspect = c.num("aspect")!!
            val view =
                frameView(
                    c.box("panel")!!,
                    c.box("bubble")!!,
                    aspect,
                    c.num("progress")!!,
                    c.direction(),
                    c.box("detail"),
                    c.num("seconds"),
                    c.num("band") ?: CAPTION_BAND,
                    c.box("keep"),
                    c.num("push") ?: 1.0,
                    c.flag("wide") ?: false,
                )
            assertClose(c.box("view")!!, view, 4, name)
            assertClose(aspect, view.w / view.h, 9, "$name aspect")
        }
    }

    @Test
    fun framingAtMatchesTheExport() {
        for (c in vectors.cases("framingAt")) {
            val name = c.str("name")!!
            val f =
                framingAt(
                    c.bubble("bubble")!!,
                    c.bubble("prev"),
                    c.num("elapsed")!!,
                    c.flag("still")!!,
                    c.page(),
                    c.num("aspect")!!,
                    c.direction(),
                    c.num("band") ?: CAPTION_BAND,
                    c.take("take"),
                    c.take("prevTake"),
                )
            assertClose(c.box("view")!!, f.view, 4, "$name view")
            assertClose(c.box("mask")!!, f.mask, 4, "$name mask")
            assertClose(c.num("light")!!, f.light, 6, "$name light")
            assertClose(c.num("mix")!!, f.mix, 6, "$name mix")
            assertClose(c.getValue("shape").quad(), f.shape, 4, "$name shape")
            assertClose(c.box("backdrop")!!, f.backdrop, 4, "$name backdrop")
        }
    }

    @Test
    fun takesMatchTheExport() {
        for (c in vectors.cases("takes")) {
            val bubbles = c.getValue("bubbles").jsonArray.map { it.bubble() }
            val expected = c.getValue("takes").jsonArray.map { it.take() }
            assertEquals(expected, takes(bubbles, c.num("aspect")!!, Direction.Rtl, c.num("band")!!), c.str("name"))
        }
    }

    @Test
    fun aPanelReadWholeDriftsInSlightlyAndOneWayOnly() {
        val c = vectors.cases("takes").first()
        val all = c.getValue("bubbles").jsonArray.map { it.bubble() }
        val list = c.getValue("takes").jsonArray.map { it.take() }
        val runIdx = all.indices.filter { list[it].wide }
        val run = runIdx.map { all[it] }
        assertTrue(run.size >= 2)
        val aspect = c.num("aspect")!!
        val band = c.num("band")!!
        val panel = run[0].panelBbox
        val whole = fitBox(panel, aspect / (1 - band))
        val zoom = mutableListOf<Double>()
        run.forEachIndexed { i, b ->
            val prev = run.getOrNull(i - 1)
            var t = if (i > 0) 0.0 else b.leadMs.toDouble()
            while (t < b.durationMs) {
                val view =
                    framingAt(
                        b,
                        prev,
                        t,
                        false,
                        c.page(),
                        aspect,
                        Direction.Rtl,
                        band,
                        list[i],
                        if (i >
                            0
                        ) {
                            list[i - 1]
                        } else {
                            null
                        },
                    ).view
                val x0 = max(view.x, panel.x)
                val y0 = max(view.y, panel.y)
                val x1 = min(view.x + view.w, panel.x + panel.w)
                val y1 = min(view.y + view.h, panel.y + panel.h)
                assertAtLeast(WIDE_VISIBLE - 1e-9, ((x1 - x0) * (y1 - y0)) / (panel.w * panel.h), "in frame at $t")
                zoom += whole.w / view.w
                t += 20
            }
        }
        zoom.zipWithNext().forEach { (a, b) -> assertAtLeast(a - 1e-9, b, "zoom never goes back") }
        assertAtMost(1.06, zoom.max())
        assertTrue(zoom.last() > 1.02)
    }

    @Test
    fun transitionsAndCaptionAnchorsMatchTheExport() {
        for (c in vectors.cases("transition")) {
            val t = transition(c.bubble("bubble")!!, c.bubble("prev"), c.page(), c.num("aspect")!!)
            assertEquals(c.str("kind"), t.kind.name.lowercase(), c.str("name"))
            assertClose(c.num("ms")!!, t.ms, 4, c.str("name")!!)
        }
        for (c in vectors.cases("captionAnchor")) {
            val a = captionAnchor(c.bubble("bubble")!!, c.bubble("prev"), c.page(), c.num("aspect")!!, Direction.Rtl, c.num("band")!!)
            assertClose(c.num("anchor")!!, a, 4, c.str("name")!!)
        }
    }

    @Test
    fun captionOnlyForABalloonThatCannotBeRead() {
        for (c in vectors.cases("captionShown")) {
            val b = c.bubble("bubble")!!
            assertClose(c.num("lettering")!!, lettering(b.bubbleBbox, b.text), 4, c.str("name")!!)
            assertEquals(
                c.flag("shown"),
                captionShown(b, c.bubble("prev"), c.num("aspect")!!, Direction.Rtl, c.num("band")!!),
                c.str("name"),
            )
        }
    }

    @Test
    fun captionSlotNeverOverTheLettering() {
        for (c in vectors.cases("captionSlot")) {
            val slot =
                captionSlot(
                    c.bubble("bubble")!!,
                    c.bubble("prev"),
                    c.getValue("letters").jsonArray.map { it.box() },
                    c.page(),
                    c.num("aspect")!!,
                    c.num("height")!!,
                    c.num("width")!!,
                    c.num("floor")!!,
                    Direction.Rtl,
                    c.num("band")!!,
                )
            val expected = c["slot"]?.takeIf { !it.isNull() }?.jsonObject
            if (expected == null) {
                assertNull(slot, c.str("name"))
            } else {
                assertNotNull(slot, c.str("name"))
                assertClose(expected.num("top")!!, slot.top, 6, c.str("name")!!)
                assertEquals(expected.flag("overArt"), slot.overArt, c.str("name"))
            }
        }
    }

    @Test
    fun captionBlockStaysClearOfEveryTextBoxAllThroughTheBubble() {
        val c = vectors.cases("captionSlot").first { it["slot"]?.takeIf { s -> !s.isNull() }?.jsonObject?.flag("overArt") == true }
        val b = c.bubble("bubble")!!
        val letters = c.getValue("letters").jsonArray.map { it.box() }
        assertTrue(panelLetters(listOf(b) + letters.map { b.copy(bubbleBbox = it) }, b).size >= letters.size)
        val slot = c.getValue("slot").jsonObject
        val top = slot.num("top")!!
        val height = c.num("height")!!
        val width = c.num("width")!!
        var t = 0.0
        while (t <= b.durationMs) {
            val (vx, vy, vw, vh) = framingAt(b, null, t, false, c.page(), c.num("aspect")!!, Direction.Rtl, c.num("band")!!).view
            for (q in letters) {
                val qTop = (q.y - vy) / vh
                val qBottom = (q.y + q.h - vy) / vh
                val left = (q.x - vx) / vw
                val right = (q.x + q.w - vx) / vw
                val across = left < 0.5 + width / 2 && right > 0.5 - width / 2
                if (across) assertTrue(qBottom <= top || qTop >= top + height, "lettering under the caption at $t")
            }
            t += 1000.0 / 30
        }
    }

    private val page = PageSize(800.0, 1200.0)
    private val wide = Box(24.0, 24.0, 752.0, 368.0)
    private val far = Box(24.0, 1000.0, 200.0, 180.0)
    private val a = TimelineBubble(page = 0, panelBbox = wide, bubbleBbox = Box(546.0, 54.0, 200.0, 90.0), durationMs = 6000, leadMs = 450)
    private val b = TimelineBubble(page = 0, panelBbox = far, bubbleBbox = far, durationMs = 3000, leadMs = 500)

    @Test
    fun neverHoldsStillOnALongBeat() {
        var last = framingAt(a, null, 450.0, false, page, 0.5625).view.w
        var t = 450 + 1000.0 / 30
        while (t <= 6000) {
            val w = framingAt(a, null, t, false, page, 0.5625).view.w
            assertTrue(abs(w - last) > 0.01, "still at $t")
            last = w
            t += 1000.0 / 30
        }
    }

    @Test
    fun dissolvesToAFarPanelInsteadOfWhippingNeverDimmingIt() {
        val move = transition(b, a, page, 0.5625)
        assertEquals(TransitionKind.Dissolve, move.kind)
        assertEquals(DISSOLVE_MS, move.ms)
        var mix = -1.0
        var t = 0.0
        while (t <= DISSOLVE_MS + 100) {
            val f = framingAt(b, a, t, false, page, 0.5625)
            assertEquals(1.0, f.light)
            assertAtLeast(mix, f.mix)
            mix = f.mix
            t += 10
        }
        assertEquals(0.0, framingAt(b, a, 0.0, false, page, 0.5625).mix)
        assertEquals(1.0, mix)
        // A new page dissolves in over the last one too; only the episode's first shot fades in.
        val next = b.copy(page = 1)
        assertEquals(TransitionKind.Page, transition(next, b, page, 0.5625).kind)
        assertEquals(1.0, framingAt(next, b, 100.0, false, page, 0.5625).light)
        assertTrue(framingAt(next, b, 100.0, false, page, 0.5625).mix < 1)
        assertTrue(framingAt(a, null, 100.0, false, page, 0.5625).light < 1)
        // A lead too short for the glide it needs: a dissolve, never a squeezed glide.
        val rushed = a.copy(bubbleBbox = Box(40.0, 224.0, 210.0, 100.0), leadMs = 100)
        assertEquals(TransitionKind.Dissolve, transition(rushed, b, page, 0.5625).kind)
    }

    @Test
    fun glidesAPanelReadWholeByAtLeastOnePixelAFrame() {
        val c = vectors.cases("takes").first()
        val all = c.getValue("bubbles").jsonArray.map { it.bubble() }
        val list = c.getValue("takes").jsonArray.map { it.take() }
        val idx = all.indices.filter { list[it].wide }
        val run = idx.map { all[it] }
        val runTakes = idx.map { list[it] }
        val aspect = c.num("aspect")!!
        val band = c.num("band")!!
        val panel = run[0].panelBbox
        val whole = fitBox(panel, aspect / (1 - band))
        val corners = listOf(0, 1).flatMap { i -> listOf(0, 1).map { j -> (panel.x + i * panel.w) to (panel.y + j * panel.h) } }
        val width = 1080.0
        val height = 1080.0 / aspect
        val total = run.sumOf { it.durationMs }
        val steps = mutableListOf<Double>()
        var last: List<Pair<Double, Double>>? = null
        var g = run[0].leadMs.toDouble()
        while (g < total) {
            var i = 0
            var t = g
            while (t >= run[i].durationMs) t -= run[i++].durationMs
            val v =
                framingAt(
                    run[i],
                    run.getOrNull(i - 1),
                    t,
                    false,
                    c.page(),
                    aspect,
                    Direction.Rtl,
                    band,
                    runTakes[i],
                    runTakes.getOrNull(
                        i - 1,
                    ),
                ).view
            assertAtMost(WIDE_PUSH + 1e-9, whole.w / v.w)
            val px = corners.map { (x, y) -> ((x - v.x) / v.w) * width to ((y - v.y) / v.h) * height }
            last?.let { was -> steps += px.indices.maxOf { k -> abs(px[k].first - was[k].first) + abs(px[k].second - was[k].second) } }
            last = px
            g += 1000.0 / 30
        }
        assertTrue(steps.size > 100)
        assertAtLeast(1.0, steps.min())
    }

    @Test
    fun shakesBrieflyOnAnImpactNeverInReducedMotion() {
        val hit = a.copy(onomatopoeia = listOf("DOKA"))
        var moved = 0.0
        var t = 0.0
        while (t < 1000) {
            val calm = framingAt(a, null, t, false, page, 0.5625).view
            val shaken = framingAt(hit, null, t, false, page, 0.5625).view
            val off = max(abs(shaken.x - calm.x), abs(shaken.y - calm.y))
            assertAtMost(IMPACT_SHAKE * calm.w + 1e-9, off)
            if (off > 1e-9) moved = t
            t += 20
        }
        assertTrue(moved > 0)
        assertTrue(moved < IMPACT_MS)
        assertEquals(framingAt(a, null, 100.0, true, page, 0.5625), framingAt(hit, null, 100.0, true, page, 0.5625))
    }

    @Test
    fun pansAWordlessPanoramaRightToLeftOrLeftToRightOnRequest() {
        val pano = Box(20.0, 20.0, 1560.0, 380.0)

        fun at(
            p: Double,
            d: Direction,
        ) = frameView(pano, pano, 0.5625, p, d, null, 30.0).x
        assertTrue(at(0.0, Direction.Rtl) > at(1.0, Direction.Rtl))
        assertClose(at(1.0, Direction.Rtl), at(0.0, Direction.Ltr), 9)
    }

    @Test
    fun showsAWidePanelWholeOnAPhoneTheBubbleAboveTheCaptionBand() {
        val bubble = Box(546.0, 54.0, 200.0, 90.0)
        for (step in 0..10) {
            val (x, y, w, h) = frameView(wide, bubble, 0.5625, 0.5, Direction.Rtl, null, null, 0.2, null, step / 10.0)
            assertAtLeast(wide.w / MAX_PUSH - 1e-6, w)
            assertAtMost(bubble.x + 1e-6, x)
            assertAtLeast(bubble.x + bubble.w - 1e-6, x + w)
            assertAtMost(wide.y + 1e-6, y)
            assertAtLeast(wide.y + wide.h - 1e-6, y + h * 0.8)
        }
    }

    @Test
    fun neverPushesPastTheDrawnCharacters() {
        val keep = Box(40.0, 30.0, 720.0, 350.0)
        for (step in 0..10) {
            val (x, _, w) = frameView(wide, Box(546.0, 54.0, 200.0, 90.0), 0.5625, 0.5, Direction.Rtl, null, null, 0.2, keep, step / 10.0)
            assertAtMost(keep.x + 1e-6, x)
            assertAtLeast(keep.x + keep.w - 1e-6, x + w)
        }
    }

    @Test
    fun keepsTheCoverWholeInEveryStage() {
        val cover = Box(0.0, 0.0, 1600.0, 1100.0)
        val title = Box(380.0, 420.0, 840.0, 220.0)
        for (aspect in listOf(0.5625, 1.0, 16.0 / 9)) {
            for (step in 0..20) {
                val (x, y, w, h) = frameView(cover, cover, aspect, 0.5, Direction.Rtl, title, null, 0.2, null, step / 20.0)
                assertAtMost(title.x + 1e-6, x)
                assertAtMost(title.y + 1e-6, y)
                assertAtLeast(title.x + title.w - 1e-6, x + w)
                assertAtLeast(title.y + title.h - 1e-6, y + h)
            }
        }
    }

    @Test
    fun coversTheStageWithThePanelForTheBackdrop() {
        // A 400×300 panel cropped to 9:16 around its centre: 168.75 wide, scaled ×6.4 (camera.test.ts backdropStyle).
        val bg = PlaybackPlan.backdropTransform(Box(100.0, 200.0, 400.0, 300.0), 1080.0, 1920.0)
        assertClose(5120.0, 800 * bg.scale, 6)
        assertClose(7680.0, 1200 * bg.scale, 6)
        assertClose(-1380.0, bg.translateX, 6)
        assertClose(-1280.0, bg.translateY, 6)
    }

    @Test
    fun opensOnImpactMatchesTheExport() {
        for (c in vectors.cases("opensOnImpact")) {
            val sounds = c.getValue("sounds").jsonArray.map { it.sound() }
            val bubble =
                TimelineBubble(
                    page = 0,
                    panelBbox = Box(0.0, 0.0, 400.0, 300.0),
                    bubbleBbox = Box(0.0, 0.0, 400.0, 300.0),
                    durationMs = 2000,
                    onomatopoeia = c.getValue("onomatopoeia").jsonArray.map { it.jsonPrimitive.content },
                    sounds = sounds,
                )
            assertEquals(c.flag("shakes"), opensOnImpact(bubble), c.str("name"))
        }
    }

    @Test
    fun aCalmEffectDoesNotShakeTheCamera() {
        val base = TimelineBubble(page = 0, panelBbox = wide, bubbleBbox = wide, durationMs = 3000, leadMs = 450)
        val sound = BubbleSound("audio/aa/x.ogg", 1000, "sfx", 3000)
        val calm = base.copy(sounds = listOf(sound.copy(impact = false)))
        val hit = base.copy(sounds = listOf(sound.copy(impact = true)))

        fun moves(x: TimelineBubble): Int {
            var n = 0
            var t = 0.0
            while (t < 1500) {
                val still = framingAt(base, null, t, false, page, 0.5625).view
                val view = framingAt(x, null, t, false, page, 0.5625).view
                if ((0..3).any { abs(view[it] - still[it]) > 1e-9 }) n += 1
                t += 20
            }
            return n
        }
        assertEquals(0, moves(calm))
        assertTrue(moves(hit) > 0)
    }
}
