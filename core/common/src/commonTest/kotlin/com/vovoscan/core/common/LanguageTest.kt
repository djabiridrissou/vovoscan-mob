package com.vovoscan.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageTest {
    @Test
    fun languagesMirrorTheServerTable() {
        assertEquals(48, Language.ALL.size)
        assertEquals(
            Language.ALL.size,
            Language.ALL
                .map { it.code }
                .toSet()
                .size,
        )
        assertNull(Language.of("xx"))
        val ewe = Language.of("ee")!!
        assertFalse(ewe.dub)
        assertTrue(ewe.beta)
        val arabic = Language.of("ar")!!
        assertTrue(arabic.dub && arabic.rtl && !arabic.mp4)
        assertTrue(Language.of("fr")!!.dub)
    }
}
