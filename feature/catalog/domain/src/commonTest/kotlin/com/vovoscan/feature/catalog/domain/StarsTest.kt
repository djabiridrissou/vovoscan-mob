package com.vovoscan.feature.catalog.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StarsTest {
    @Test
    fun theHalfUnderTheFinger() {
        assertEquals(1.0, Stars.at(0.0))
        assertEquals(1.0, Stars.at(0.15))
        // The left half of the third star.
        assertEquals(2.5, Stars.at(0.46))
        assertEquals(3.0, Stars.at(0.55))
        assertEquals(5.0, Stars.at(1.0))
        assertEquals(5.0, Stars.at(1.4))
    }

    @Test
    fun onlyHalfStepsFromOneToFive() {
        assertTrue(Stars.valid(1.0) && Stars.valid(4.5) && Stars.valid(5.0))
        assertFalse(Stars.valid(0.5) || Stars.valid(4.25) || Stars.valid(5.5))
    }

    @Test
    fun fillsAndSteps() {
        assertEquals(listOf(1.0, 1.0, 0.5, 0.0, 0.0), Stars.fills(2.5))
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0), Stars.fills(null))
        assertEquals(1.0, Stars.step(null, up = true))
        assertNull(Stars.step(null, up = false))
        assertEquals(5.0, Stars.step(5.0, up = true))
        assertEquals(4.5, Stars.step(5.0, up = false))
        assertNull(Stars.step(1.0, up = false))
    }
}
