package com.vovoscan.feature.reader.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LetteringLayoutTest {
    private fun page(vararg texts: TranslatedText) = TranslatedPage(0, 1000, 2000, texts.toList())

    private fun text(
        box: PixelBox,
        area: PixelBox? = null,
        round: Boolean = false,
    ) = TranslatedText("t1", box, "Bonjour", "#ffffff", "#000000", round, area)

    private fun near(
        expected: Double,
        actual: Double,
    ) = assertTrue(abs(expected - actual) < 1e-9, "expected $expected, was $actual")

    @Test
    fun aBoxWithoutAreaIsInflatedAndPlacedAsFractions() {
        val l = LetteringLayout.page(page(text(PixelBox(100.0, 200.0, 200.0, 100.0)))).single()
        // pad = 0.12 * min(200, 100) = 12 px on each side.
        near(88.0 / 1000, l.textRect.x)
        near(188.0 / 2000, l.textRect.y)
        near(224.0 / 1000, l.textRect.w)
        near(124.0 / 2000, l.textRect.h)
        assertEquals(2, l.patches.size)
    }

    @Test
    fun aRoundAreaIsAnEllipsePatchAndTheTextUsesItsInterior() {
        val balloon = text(PixelBox(400.0, 400.0, 50.0, 40.0), PixelBox(300.0, 300.0, 300.0, 200.0), round = true)
        val l = LetteringLayout.page(page(balloon)).single()
        assertTrue(l.patches.first().ellipse)
        // Usable part of the ellipse: 74 % of the area, centred: x = 300 + 300 * 0.13 = 339.
        near(339.0 / 1000, l.textRect.x)
        near(222.0 / 1000, l.textRect.w)
    }

    @Test
    fun verticalLetteringIsWidenedForAHorizontalScript() {
        val l = LetteringLayout.page(page(text(PixelBox(500.0, 100.0, 40.0, 400.0)))).single()
        assertTrue(l.textRect.w * 1000 > 40.0)
    }

    @Test
    fun rectanglesStayOnThePage() {
        val l = LetteringLayout.page(page(text(PixelBox(0.0, 0.0, 30.0, 30.0)))).single()
        assertTrue(l.patches.all { it.rect.x >= 0.0 && it.rect.y >= 0.0 })
    }

    @Test
    fun blankTextsAndUnknownSizesDrawNothing() {
        assertTrue(LetteringLayout.page(page(text(PixelBox(1.0, 1.0, 10.0, 10.0)).copy(text = " "))).isEmpty())
        assertTrue(LetteringLayout.page(TranslatedPage(0, 0, 0, listOf(text(PixelBox(1.0, 1.0, 10.0, 10.0))))).isEmpty())
    }
}
