package com.vovoscan.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDatesTest {
    @Test
    fun civilDatesRoundTrip() {
        assertEquals(CivilDate(1970, 1, 1), civilDate(0))
        assertEquals(CivilDate(2026, 10, 21), civilDate(epochDay(CivilDate(2026, 10, 21))))
        assertEquals(CivilDate(2024, 2, 29), civilDate(epochDay(CivilDate(2024, 3, 1)) - 1))
        assertEquals(CivilDate(1969, 12, 31), civilDate(-1))
    }

    @Test
    fun localDayFollowsTheOffset() {
        val paris = UtcOffset { 2 * 3_600_000L }
        // 2026-10-20T23:30:00Z is already the 21st in Paris (UTC+2).
        val late = parseIsoMillis("2026-10-20T23:30:00Z")!!
        assertEquals(CivilDate(2026, 10, 21), localDate(late, paris))
        assertEquals(parseIsoMillis("2026-10-20T22:00:00Z"), startOfLocalDay(CivilDate(2026, 10, 21), paris))
        assertEquals(parseIsoMillis("2026-10-01T00:00:00Z"), startOfLocalDay(CivilDate(2026, 10, 1)) { 0 })
    }
}
