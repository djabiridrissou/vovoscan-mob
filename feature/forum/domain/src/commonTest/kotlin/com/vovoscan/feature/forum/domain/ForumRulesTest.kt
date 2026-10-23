package com.vovoscan.feature.forum.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForumRulesTest {
    @Test
    fun stickersKeepTheirKeysAndShowLatinOnomatopoeia() {
        // The forum contract: the keys never change on the wire, the glyphs are never Japanese.
        assertEquals(
            listOf("doki", "don", "zawa", "gogogo", "kusu", "gaan", "kira", "shiin"),
            Sticker.entries.map { it.key },
        )
        assertEquals(
            listOf("DOKI", "BOOM", "BZZZ", "GRRR", "HAHA", "GASP", "BLING", "SHHH"),
            Sticker.entries.map { it.glyph },
        )
    }

    @Test
    fun textIsKeptPlain() {
        assertEquals("a\n\nb", ForumRules.clean("  a\u202E\u0007\n\n\n\nb  "))
        assertEquals("line one\nline two", ForumRules.clean("line one   \r\nline two"))
    }

    @Test
    fun theComposerSaysWhatTheServerWouldRefuse() {
        assertEquals("FORUM_EMPTY", ForumRules.problem(PostKind.Note, "   ", hasMark = false))
        // A sticker or a stroke alone is a note.
        assertNull(ForumRules.problem(PostKind.Note, "", hasMark = true))
        assertEquals("FORUM_TOO_LONG", ForumRules.problem(PostKind.Note, "a".repeat(501), hasMark = false))
        assertNull(ForumRules.problem(PostKind.Theory, "a".repeat(1500), hasMark = false))
        assertEquals("FORUM_TOO_LONG", ForumRules.problem(PostKind.Salon, "a".repeat(1001), hasMark = false))
        assertEquals(
            "FORUM_TOO_MANY_LINKS",
            ForumRules.problem(PostKind.Note, "https://a.example www.b.example http://c.example", hasMark = false),
        )
        assertNull(ForumRules.problem(PostKind.Note, "https://a.example and www.b.example", hasMark = false))
    }

    @Test
    fun anchorsAreFractionsWithFourDecimals() {
        assertEquals(Anchor.Point(0.1235, 1.0), Anchors.point(0.123456, 1.7))
        assertEquals(Anchor.Frame(0.1, 0.2, 0.3, 0.4), Anchors.frame(0.4, 0.6, 0.1, 0.2))
        // Too small to be a frame: a point where the drag started.
        assertEquals(Anchor.Point(0.5, 0.5), Anchors.frame(0.5, 0.5, 0.51, 0.7))
    }

    @Test
    fun strokesAreThinnedTo64PointsKeepingBothEnds() {
        val wave = (0..400).map { i -> i / 400.0 to 0.5 + 0.2 * kotlin.math.sin(i / 20.0) }
        val stroke = Anchors.stroke(wave)
        assertIs<Anchor.Stroke>(stroke)
        assertTrue(stroke.points.size in 2..Anchors.MAX_POINTS, "${stroke.points.size}")
        assertEquals(0.0 to 0.5, stroke.points.first())
        assertEquals(wave.last().let { Anchors.round(it.first) to Anchors.round(it.second) }, stroke.points.last())
        // A straight line needs no more than its ends; a tap is no stroke.
        assertEquals(2, Anchors.stroke((0..100).map { it / 100.0 to it / 100.0 })!!.points.size)
        assertNull(Anchors.stroke(listOf(0.2 to 0.2, 0.2 to 0.2)))
    }

    @Test
    fun stickersAreTheEightOnomatopoeia() {
        assertEquals(listOf("doki", "don", "zawa", "gogogo", "kusu", "gaan", "kira", "shiin"), Sticker.entries.map { it.key })
        assertEquals(Sticker.Gogogo, Sticker.of("gogogo"))
        assertNull(Sticker.of("boom"))
    }
}
