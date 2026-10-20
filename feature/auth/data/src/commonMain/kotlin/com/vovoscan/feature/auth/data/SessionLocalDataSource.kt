package com.vovoscan.feature.auth.data

import com.vovoscan.core.network.ApiJson
import com.vovoscan.core.security.SecureKeys
import com.vovoscan.core.security.SecureStore

/** Source locale : jeton et profil gardés dans le coffre de l'appareil (Keystore / Keychain). */
interface SessionLocalDataSource {
    suspend fun token(): String?

    suspend fun user(): UserDto?

    suspend fun save(session: SessionDto)

    suspend fun saveUser(user: UserDto)

    suspend fun clear()
}

class SecureSessionLocalDataSource(
    private val store: SecureStore,
) : SessionLocalDataSource {
    override suspend fun token(): String? = store.get(SecureKeys.SESSION_TOKEN)

    override suspend fun user(): UserDto? =
        store.get(USER)?.let { runCatching { ApiJson.decodeFromString(UserDto.serializer(), it) }.getOrNull() }

    override suspend fun save(session: SessionDto) {
        store.put(SecureKeys.SESSION_TOKEN, session.token)
        saveUser(session.user)
    }

    override suspend fun saveUser(user: UserDto) = store.put(USER, ApiJson.encodeToString(UserDto.serializer(), user))

    override suspend fun clear() = store.clear()

    private companion object {
        const val USER = "session_user"
    }
}
