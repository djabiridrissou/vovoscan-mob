package com.vovoscan.feature.reader.domain

import com.vovoscan.feature.reader.domain.NextChapterRules.endState
import com.vovoscan.feature.reader.domain.NextChapterRules.newlyReady
import com.vovoscan.feature.reader.domain.NextChapterRules.offer
import com.vovoscan.feature.reader.domain.NextChapterRules.preparing
import com.vovoscan.feature.reader.domain.NextChapterRules.priceOf
import com.vovoscan.feature.reader.domain.NextChapterRules.shouldAuto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same cases as the web's `next-logic.test.ts`. */
class NextChapterRulesTest {
    private fun up(
        n: Int,
        translation: UpcomingTranslation? = null,
        readable: Boolean = true,
        credits: Int = 12,
    ) = Upcoming("c$n", n.toDouble(), null, 20, readable, translation, credits)

    private fun t(
        status: TranslationStatus,
        cancellable: Boolean = false,
    ) = UpcomingTranslation(
        status,
        when (status) {
            TranslationStatus.Running -> 42
            TranslationStatus.Ready -> 100
            else -> 0
        },
        null,
        cancellable,
    )

    private fun dto(
        upcoming: List<Upcoming>,
        funding: Funding = Funding.Credits,
        auto: Boolean = false,
        language: String? = "ee",
        nextUrl: String? = null,
    ) = NextChapters(language, "s", funding, if (funding == Funding.OwnKey) null else 120, auto, upcoming, nextUrl)

    @Test
    fun theEndCardAlwaysHasAWayOn() {
        assertEquals(EndState.Go(up(2, t(TranslationStatus.Ready)), true), endState(dto(listOf(up(2, t(TranslationStatus.Ready)))), "ee"))
        assertEquals(EndState.Go(up(2), false), endState(dto(listOf(up(2))), null))
        assertEquals(EndState.Prepare(up(2), Price(12, Price.Kind.Credits)), endState(dto(listOf(up(2))), "ee"))
        assertEquals(42, (endState(dto(listOf(up(2, t(TranslationStatus.Running)))), "ee") as EndState.Preparing).progress)
        assertTrue(endState(dto(listOf(up(2, readable = false))), "ee") is EndState.Unread)
        assertEquals(EndState.Link("https://x.example/2"), endState(dto(emptyList(), nextUrl = "https://x.example/2"), "ee"))
        assertEquals(EndState.Latest, endState(dto(emptyList()), "ee"))
    }

    @Test
    fun thePriceSaysWhoPaysBeforeAnythingSpends() {
        assertEquals(Price(0, Price.Kind.OwnKey), priceOf(dto(emptyList(), Funding.OwnKey), listOf(up(2, credits = 0))))
        assertEquals(Price(0, Price.Kind.Free), priceOf(dto(emptyList()), listOf(up(2, t(TranslationStatus.Running), credits = 0))))
        val blocked = priceOf(dto(emptyList(), Funding.None), listOf(up(2)))
        assertEquals(Price.Kind.NeedsKey, blocked.kind)
        assertTrue(blocked.blocked)
        assertEquals(Price(1, Price.Kind.Credits), priceOf(dto(emptyList()), listOf(up(2, credits = 1))))
    }

    @Test
    fun theOfferEscalatesFromTheDataWithTheRealCountLeft() {
        val ten = (2..11).map { up(it) }
        assertEquals(Offer(1, true, Price(12, Price.Kind.Credits)), offer(dto(ten)))
        val nextAsked = ten.mapIndexed { k, u -> if (k == 0) u.copy(translation = t(TranslationStatus.Queued, true)) else u }
        assertEquals(Offer(5, false, Price(48, Price.Kind.Credits)), offer(dto(nextAsked)))
        val fiveAsked = ten.mapIndexed { k, u -> if (k < 5) u.copy(translation = t(TranslationStatus.Ready)) else u }
        assertEquals(Offer(10, false, Price(60, Price.Kind.Credits)), offer(dto(fiveAsked)))
        assertNull(offer(dto(ten.map { it.copy(translation = t(TranslationStatus.Ready)) })))
        assertEquals(2, offer(dto(listOf(up(2, t(TranslationStatus.Ready)), up(3))))?.count)
        assertNull(offer(dto(listOf(up(2, readable = false)))))
    }

    @Test
    fun theSeriesSettingPreparesOneMissingAffordableChapterAndNeverStacks() {
        assertTrue(shouldAuto(dto(listOf(up(2)), auto = true), "ee"))
        assertFalse(shouldAuto(dto(listOf(up(2))), "ee"))
        assertFalse(shouldAuto(dto(listOf(up(2)), auto = true), null))
        assertFalse(shouldAuto(dto(listOf(up(2)), Funding.None, auto = true), "ee"))
        assertFalse(shouldAuto(dto(listOf(up(2, t(TranslationStatus.Queued, true)), up(3)), auto = true), "ee"))
    }

    @Test
    fun newlyReadyFindsWhatWasBeingPreparedAndIsReadyNow() {
        val before = dto(listOf(up(2, t(TranslationStatus.Running)), up(3, t(TranslationStatus.Queued))))
        val now = dto(listOf(up(2, t(TranslationStatus.Ready)), up(3, t(TranslationStatus.Running))))
        assertEquals(listOf("c2"), newlyReady(before, now).map { it.id })
        assertEquals(listOf("c3"), preparing(now).map { it.id })
        assertEquals(emptyList(), newlyReady(null, now))
        assertEquals(emptyList(), newlyReady(before.copy(language = "fr"), now))
    }
}
