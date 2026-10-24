package com.vovoscan.app.push

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vovoscan.feature.notifications.domain.DeviceKey
import com.vovoscan.feature.notifications.domain.PushDevice
import java.security.MessageDigest
import java.util.UUID

/**
 * This install's push key: an X25519 pair kept in Keystore-protected preferences of their own (`vovoscan_push`, not
 * the session's store), a random device id, and a key id (the first 16 hex of the public key's SHA-256). Made on first
 * use, erased on sign-out. Any storage failure means no sealed pushes (null), never a crash.
 */
class AndroidPushDevice(
    private val context: Context,
) : PushDevice {
    override val platform = "android"

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            EncryptedSharedPreferences.create(
                context,
                FILE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    @Synchronized
    override fun key(): DeviceKey? {
        val p = prefs ?: return null
        val private = p.getString(PRIVATE, null)?.let { runCatching { PushCrypto.unb64(it) }.getOrNull() }
        val id = p.getString(DEVICE_ID, null)
        return if (private != null && id != null) keyOf(id, private) else rotate()
    }

    @Synchronized
    override fun rotate(): DeviceKey? {
        val p = prefs ?: return null
        val keys = runCatching { PushCrypto.newKeys() }.getOrNull() ?: return null
        val id = p.getString(DEVICE_ID, null) ?: UUID.randomUUID().toString()
        val saved =
            p
                .edit()
                .putString(DEVICE_ID, id)
                .putString(PRIVATE, PushCrypto.b64(keys.privateKey))
                .commit()
        return if (saved) keyOf(id, keys.privateKey) else null
    }

    @Synchronized
    override fun forget() {
        prefs?.edit()?.clear()?.commit()
    }

    /** For the messaging service: this device's id and private key, to open a push (null: none). */
    @Synchronized
    fun secret(): Pair<DeviceKey, ByteArray>? {
        val p = prefs ?: return null
        val private = p.getString(PRIVATE, null)?.let { runCatching { PushCrypto.unb64(it) }.getOrNull() } ?: return null
        val id = p.getString(DEVICE_ID, null) ?: return null
        return keyOf(id, private) to private
    }

    private fun keyOf(
        deviceId: String,
        private: ByteArray,
    ): DeviceKey {
        val public = PushCrypto.publicOf(private)
        val digest = MessageDigest.getInstance("SHA-256").digest(public)
        val keyId = digest.take(8).joinToString("") { "%02x".format(it) }
        return DeviceKey(deviceId = deviceId, keyId = keyId, publicKey = PushCrypto.b64(public))
    }

    private companion object {
        const val FILE = "vovoscan_push"
        const val DEVICE_ID = "device_id"
        const val PRIVATE = "private_key"
    }
}
