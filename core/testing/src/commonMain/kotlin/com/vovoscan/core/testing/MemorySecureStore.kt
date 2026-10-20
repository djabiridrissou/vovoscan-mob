package com.vovoscan.core.testing

import com.vovoscan.core.security.SecureStore

/** In-memory secure store for tests. */
class MemorySecureStore(
    initial: Map<String, String> = emptyMap(),
) : SecureStore {
    val values = initial.toMutableMap()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun clear() = values.clear()
}
