package com.vovoscan.feature.auth.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun passwordOfAtLeastTwelveCharacters() {
        assertNotNull(Credentials.passwordError("short"))
        assertNull(Credentials.passwordError("twelve-chars"))
    }

    @Test
    fun normalizedInvite() {
        assertEquals("VOVO-7K2D-QX9P", Credentials.normalizeInvite(" vovo-7k2d-qx9p "))
        assertNull(Credentials.inviteError("vovo-7k2d-qx9p"))
        assertNotNull(Credentials.inviteError("hello"))
    }

    @Test
    fun wildcardPermission() {
        val admin = Session("1", "a@b.c", "A", listOf("owner"), setOf("*"))
        assertTrue(admin.can("titles:publish"))
    }

    @Test
    fun featuresComingSoonUnlessGrantedOrSuperAdmin() {
        val member = Session("1", "a@b.c", "A", listOf("reader"), emptySet())
        assertFalse(member.has("video"))
        assertTrue(member.copy(features = setOf("video")).has("video"))
        assertTrue(member.copy(isSuperAdmin = true).has("video"))
        assertFalse(member.isCreator)
        assertTrue(member.copy(permissions = setOf("titles:create")).isCreator)
    }
}
