package com.vovoscan.feature.groups.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupRulesTest {
    @Test
    fun cleaningDropsControlAndBidiCharactersAndKeepsOneBlankLine() {
        assertEquals("ab\n\nc", GroupRules.clean(" a‮b\u0007\n\n\n\nc ", lines = true))
        assertEquals("a b", GroupRules.clean("a\nb", lines = false))
    }

    @Test
    fun handlesFollowTheServer() {
        assertEquals("ink-club", GroupRules.normalizeHandle(" @Ink  Club "))
        assertEquals("ink-club", GroupRules.normalizeHandle("ink__club"))
        assertTrue(GroupRules.handleOk("ink-club"))
        assertFalse(GroupRules.handleOk("ink--club"))
        assertFalse(GroupRules.handleOk("-ink"))
        assertFalse(GroupRules.handleOk("ab"))
        assertFalse(GroupRules.handleOk("admin"))
        assertFalse(GroupRules.handleOk("a".repeat(33)))
    }

    @Test
    fun aMessageNeedsWordsWithinTheLimits() {
        assertEquals("GROUP_EMPTY", GroupRules.textProblem(" ‏\n "))
        assertEquals("GROUP_TOO_LONG", GroupRules.textProblem("a".repeat(GroupRules.TEXT_MAX + 1)))
        assertEquals("GROUP_TOO_MANY_LINKS", GroupRules.textProblem("https://a.example http://b.example www.c.example HTTPS://d.example"))
        assertNull(GroupRules.textProblem("See https://a.example and www.b.example"))
    }

    @Test
    fun onlyTheAuthorEditsTheirOwnTextWithinFifteenMinutes() {
        val m = message("m1", at = 1_000, author = "u1")
        assertTrue(GroupRules.editable(m, "u1", 1_000 + GroupRules.EDIT_WINDOW_MS))
        assertFalse(GroupRules.editable(m, "u1", 1_001 + GroupRules.EDIT_WINDOW_MS))
        assertFalse(GroupRules.editable(m, "u2", 1_000))
        assertFalse(GroupRules.editable(m.copy(kind = MessageKind.Sticker), "u1", 1_000))
        assertFalse(GroupRules.editable(m.copy(status = MessageStatus.Held), "u1", 1_000))
    }

    @Test
    fun linksAndTypedHandlesLeadToAGroup() {
        assertEquals("ink-club", GroupLinks.handleOf("vovoscan://g/ink-club"))
        assertEquals("ink-club", GroupLinks.handleOf("vovoscan://g/ink-club?game=42"))
        assertEquals("ink-club", GroupLinks.handleOf("https://web.example/g/Ink-Club/"))
        assertEquals("ink-club", GroupLinks.handleOf(" @ink-club "))
        assertNull(GroupLinks.handleOf("ink club"))
        assertNull(GroupLinks.handleOf("https://web.example/x/ink-club"))
        assertNull(GroupLinks.handleOf("vovoscan://g/admin"))
        assertEquals("https://web.example/g/ink-club", GroupLinks.shareUrl("https://web.example/", "ink-club"))
        assertEquals("vovoscan://g/ink-club", GroupLinks.appUrl("ink-club"))
    }

    @Test
    fun theChatMergesByIdOldestFirst() {
        val kept = listOf(message("b", at = 2), message("a", at = 1))
        val merged = Chat.merge(kept, listOf(message("c", at = 3), message("b", at = 2, body = "edited")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("edited", merged[1].body)
        assertEquals(kept, Chat.merge(kept, emptyList()))
    }

    private fun message(
        id: String,
        at: Long,
        author: String = "u1",
        body: String = "hi",
    ) = Message(
        id = id,
        groupId = "g",
        authorId = author,
        authorName = "Aya",
        kind = MessageKind.Text,
        body = body,
        sticker = null,
        status = MessageStatus.Visible,
        createdAtMillis = at,
    )
}
