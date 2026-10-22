package com.vovoscan.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaleReasonTest {
    @Test
    fun noNetworkIsOffline() {
        val reason = StaleReason.of(AppError.Network)
        assertEquals(StaleReason.Offline, reason)
        assertEquals("Offline: showing what's saved on this device.", reason?.notice(hasSaved = true))
        assertEquals("You're offline.", reason?.notice(hasSaved = false))
        assertFalse(reason!!.canRetry)
    }

    @Test
    fun aSourceThatDoesNotAnswerIsNamedAndCanBeRetried() {
        val reason = StaleReason.of(AppError.SourceUnavailable("Project Gutenberg"))!!
        assertEquals("Project Gutenberg isn't answering right now. Showing what's saved.", reason.notice(hasSaved = true))
        assertEquals("Project Gutenberg isn't answering right now.", reason.notice(hasSaved = false))
        assertTrue(reason.canRetry)
        assertEquals("Project Gutenberg isn't answering right now.", AppError.SourceUnavailable("Project Gutenberg").userMessage())
    }

    @Test
    fun ruleErrorsMayNeedAnUpdate() {
        val error = AppError.Rejected(SOURCE_RULES, "No node matched div.reader img")
        assertEquals(StaleReason.SourceUnreadable, StaleReason.of(error))
        assertEquals("This source couldn't be read. It may need an update.", StaleReason.of(error)?.notice(true))
        assertEquals("This source couldn't be read. It may need an update.", error.userMessage())
    }

    @Test
    fun otherErrorsAreNotAboutReachingTheSource() {
        assertNull(StaleReason.of(AppError.Unauthorized))
        assertNull(StaleReason.of(AppError.Rejected("CHAPTER_NOT_FOUND", "Gone.")))
        assertNull(StaleReason.of(AppError.Unknown("HTTP 500")))
    }
}
