package com.vovoscan.core.common.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlatJsonTest {
    @Test
    fun readsAFlatObjectOfStrings() {
        assertEquals(
            mapOf("a.b" to "x \"y\"\n ", "c" to ""),
            FlatJson.parse("{\n  \"a.b\": \"x \\\"y\\\"\\n\\u202f\",\n  \"c\": \"\"\n}\n"),
        )
        assertEquals(emptyMap(), FlatJson.parse("{ }"))
    }

    @Test
    fun refusesAnythingElse() {
        assertFailsWith<IllegalArgumentException> { FlatJson.parse("{\"a\": 1}") }
        assertFailsWith<IllegalArgumentException> { FlatJson.parse("[]") }
    }
}
