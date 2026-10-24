package com.vovoscan.feature.notifications.presentation

import com.vovoscan.core.common.i18n.I18n
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationTextTest {
    @AfterTest
    fun reset() = I18n.use("en", null)

    @Test
    fun sentencesAreWrittenFromTheReferences() {
        I18n.use("en", null)
        val group = mapOf("groupName" to "Ink Club", "name" to "Aya")
        assertEquals(Phrased("Ink Club", "Aya asks to join Ink Club."), NotificationText.of("group.request", group))
        assertEquals("3 people ask to join Ink Club.", NotificationText.of("group.request", group + ("count" to "3")).body)
        assertEquals("Aya replied: see you", NotificationText.of("group.reply", group + ("excerpt" to "see you")).body)
        assertEquals("2 replies to you", NotificationText.of("group.reply", group + ("count" to "2")).body)
        assertEquals("Aya invites you to Ink Four.", NotificationText.of("game.invite", group + ("game" to "four")).body)
        assertEquals(
            "Ink Four is over: Aya, Ren won.",
            NotificationText
                .of(
                    "game.over",
                    group + mapOf("game" to "four", "winners" to "Aya, Ren"),
                ).body,
        )
        assertEquals("Ink Four is over: a draw.", NotificationText.of("game.over", group + ("game" to "four")).body)
        assertEquals(Phrased("Notifications", "Something new for you."), NotificationText.of("group.future", emptyMap()))
    }

    @Test
    fun frenchFollowsTheUiLanguage() {
        I18n.use("fr", null)
        assertEquals("À vous de jouer à Duel d’onomatopées.", NotificationText.of("game.turn", mapOf("game" to "clash")).body)
    }

    @Test
    fun aTapOpensTheGroupOrItsGame() {
        val target = NotificationText.target(mapOf("groupId" to "g1", "handle" to "ink-club", "gameId" to "42"))
        assertEquals("vovoscan://g/ink-club?game=42", target.link)
        assertNull(NotificationText.target(emptyMap()).link)
    }
}
