package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookTypographyTest {
    @Test
    fun defaultsAreThePageAsItWas() {
        val default = BookTypography()
        // 18 px at the device's default text size, the brand's dark page, sans, not justified.
        assertEquals(18f, default.textPx(1f))
        assertEquals(BookTheme.Dark, default.theme)
        assertEquals(BookFont.Sans, default.font)
        assertEquals(BookLineSpacing.Normal, default.lineSpacing)
        assertEquals(BookMargins.Normal, default.margins)
        assertFalse(default.justify)
    }

    @Test
    fun textFollowsTheDeviceThenTheUsersScale() {
        // Larger device text (Android font scale 1.3, iOS Dynamic Type): the page follows.
        assertEquals(23.4f, BookTypography().textPx(1.3f), 0.001f)
        // The user's own scale comes on top.
        assertEquals(23.4f * 1.2f, BookTypography(textPercent = 120).textPx(1.3f), 0.001f)
        // A scale the device can't give is ignored or clamped.
        assertEquals(18f, BookTypography().textPx(0f))
        assertEquals(18f, BookTypography().textPx(Float.NaN))
        assertEquals(18f * BookTypography.MAX_SYSTEM_SCALE, BookTypography().textPx(9f), 0.001f)
    }

    @Test
    fun stepsStayWithinTheRange() {
        var typography = BookTypography()
        repeat(20) { typography = typography.bigger() }
        assertEquals(BookTypography.MAX_PERCENT, typography.textPercent)
        assertFalse(typography.canGrow)
        assertTrue(typography.canShrink)
        repeat(30) { typography = typography.smaller() }
        assertEquals(BookTypography.MIN_PERCENT, typography.textPercent)
        assertFalse(typography.canShrink)
        assertEquals(110, BookTypography().bigger().textPercent)
        assertEquals(90, BookTypography().smaller().textPercent)
    }
}
