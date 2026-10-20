package com.vovoscan.core.security

/**
 * Coffre pour le jeton de session : Keystore (EncryptedSharedPreferences) sur Android, Keychain sur iOS.
 * Jamais dans la base locale.
 */
interface SecureStore {
    suspend fun get(key: String): String?

    suspend fun put(
        key: String,
        value: String,
    )

    suspend fun remove(key: String)

    /** Tout effacer (déconnexion). */
    suspend fun clear()
}

object SecureKeys {
    const val SESSION_TOKEN = "session_token"
}
