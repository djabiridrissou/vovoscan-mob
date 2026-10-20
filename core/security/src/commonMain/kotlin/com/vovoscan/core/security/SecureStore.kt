package com.vovoscan.core.security

/**
 * Secure store for the session token: Keystore (EncryptedSharedPreferences) on Android, Keychain on iOS.
 * Never in the local database.
 */
interface SecureStore {
    suspend fun get(key: String): String?

    suspend fun put(
        key: String,
        value: String,
    )

    suspend fun remove(key: String)

    /** Erases everything (sign-out). */
    suspend fun clear()
}

object SecureKeys {
    const val SESSION_TOKEN = "session_token"
}
