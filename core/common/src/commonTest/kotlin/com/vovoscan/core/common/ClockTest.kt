package com.vovoscan.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class ClockTest {
    @Test
    fun isoRoundTrip() {
        val ms = parseIsoMillis("2026-10-20T10:00:00Z")!!
        assertEquals("2026-10-20T10:00:00Z", isoUtc(ms))
    }

    @Test
    fun noTimeZoneMeansUtc() {
        assertEquals(parseIsoMillis("2026-10-20T10:00:00Z"), parseIsoMillis("2026-10-20T10:00:00"))
        assertEquals(parseIsoMillis("2026-10-20T10:00:00Z"), parseIsoMillis("2026-10-20T10:00:00+00:00"))
    }
}
