package com.vovoscan.feature.together.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TogetherRulesTest {
    @Test
    fun codesAreTypedForgivingly() {
        assertEquals("VK3Q9TZM", InviteCode.normalize("vk3q-9tzm"))
        assertEquals("VK3Q9TZM", InviteCode.normalize(" VK3Q 9TZM "))
        // O is 0, I and L are 1.
        assertEquals("10010000", InviteCode.normalize("IOOLOOOO"))
        assertNull(InviteCode.normalize("VK3Q9TZ"))
        assertNull(InviteCode.normalize("VK3Q9TZMX"))
        // U is not in the alphabet.
        assertNull(InviteCode.normalize("VK3Q9TZU"))
        assertEquals("VK3Q-9TZM", InviteCode.pretty("VK3Q9TZM"))
    }

    @Test
    fun linksCarryTheCode() {
        assertEquals("VK3Q9TZM", InviteCode.fromLink("vovoscan://together/VK3Q9TZM"))
        assertEquals("VK3Q9TZM", InviteCode.fromLink("https://example.org/together/vk3q-9tzm/?x=1"))
        assertEquals("VK3Q9TZM", InviteCode.fromLink("VK3Q-9TZM"))
        assertNull(InviteCode.fromLink("javascript:together/VK3Q9TZM"))
        assertNull(InviteCode.fromLink("https://example.org/together/"))
        assertEquals("vovoscan://together/VK3Q9TZM", InviteCode.appLink("VK3Q9TZM"))
    }

    @Test
    fun linesAreOneCleanLineWithoutLinks() {
        assertEquals(Lines.Check.Ok("on y va ?"), Lines.check("  on\ny   va ?\t"))
        assertEquals(Lines.Check.Empty, Lines.check(" \n "))
        assertEquals(Lines.Check.TooLong, Lines.check("a".repeat(281)))
        assertEquals(Lines.Check.Ok("a".repeat(280)), Lines.check("a".repeat(280)))
        assertEquals(Lines.Check.Link, Lines.check("look https://x.example"))
        assertEquals(Lines.Check.Link, Lines.check("see WWW.example.org"))
    }

    @Test
    fun presenceFollowsTheServerClock() {
        // The device is 10 s behind the server.
        assertTrue(Presence.here(lastSeenAtMillis = 100_000, nowMillis = 120_000, offsetMs = 10_000))
        assertFalse(Presence.here(lastSeenAtMillis = 100_000, nowMillis = 136_000, offsetMs = 10_000))
    }

    @Test
    fun placesAreRoundedAndPlacedOnTheRail() {
        assertEquals(Place.Comic(3, 0.1235, 2), Place.comic(3, 0.123456, 2))
        assertEquals(Place.Comic(0, 1.0, null), Place.comic(-1, 1.7, -1))
        assertEquals(0.5, Rail.share(Place.Comic(4, 0.5), 9))
        assertEquals(0.25, Rail.share(Place.Book(1), 6))
        assertNull(Rail.share(null, 9))
        assertNull(Rail.share(Place.Comic(1, 0.0), 0))
    }
}
