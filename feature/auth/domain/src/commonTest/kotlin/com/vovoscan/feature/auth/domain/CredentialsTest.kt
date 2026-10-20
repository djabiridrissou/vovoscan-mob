package com.vovoscan.feature.auth.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialsTest {
    @Test
    fun email() {
        assertNull(Credentials.emailError(" reader@vovoscan.app "))
        assertNotNull(Credentials.emailError("reader@"))
    }

    @Test
    fun motDePasseDeDouzeCaracteresAuMoins() {
        assertNotNull(Credentials.passwordError("short"))
        assertNull(Credentials.passwordError("twelve-chars"))
    }

    @Test
    fun invitationNormalisee() {
        assertEquals("VOVO-7K2D-QX9P", Credentials.normalizeInvite(" vovo-7k2d-qx9p "))
        assertNull(Credentials.inviteError("vovo-7k2d-qx9p"))
        assertNotNull(Credentials.inviteError("hello"))
    }

    @Test
    fun permissionJoker() {
        val admin = Session("1", "a@b.c", "A", listOf("owner"), setOf("*"))
        assertTrue(admin.can("titles:publish"))
    }
}
