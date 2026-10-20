package com.vovoscan.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Preferences encrypted with an Android Keystore key (AES-256). */
@Suppress("DEPRECATION")
class AndroidSecureStore(
    context: Context,
) : SecureStore {
    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "vovoscan_secure",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { prefs.getString(key, null) }

    override suspend fun put(
        key: String,
        value: String,
    ) = write { putString(key, value) }

    override suspend fun remove(key: String) = write { remove(key) }

    override suspend fun clear() = write { clear() }

    private suspend fun write(change: SharedPreferences.Editor.() -> Unit) {
        withContext(Dispatchers.IO) { prefs.edit().apply(change).commit() }
    }
}
