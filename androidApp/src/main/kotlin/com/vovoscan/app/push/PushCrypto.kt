package com.vovoscan.app.push

import com.google.crypto.tink.subtle.X25519
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens a push sealed by the server (backend `core/push.py`, docs/NOTIFICATIONS.md §Push): X25519 between the sender's
 * ephemeral key and this device's, HKDF-SHA256 (salt = ephemeral public || device public, info `vovoscan-push-v1`),
 * AES-256-GCM with the device id as additional data. Firebase only ever carries `{v, k, e, n, c}`.
 */
object PushCrypto {
    private const val VERSION = "1"
    private val INFO = "vovoscan-push-v1".toByteArray()

    class Keys(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    fun newKeys(): Keys {
        val private = X25519.generatePrivateKey()
        return Keys(private, X25519.publicFromPrivate(private))
    }

    fun publicOf(privateKey: ByteArray): ByteArray = X25519.publicFromPrivate(privateKey)

    /**
     * The message's JSON, or null when it is not for this key or does not open (another version, another key id, a
     * tampered payload).
     */
    fun open(
        data: Map<String, String>,
        keyId: String,
        deviceId: String,
        privateKey: ByteArray,
        exchange: (ByteArray, ByteArray) -> ByteArray = X25519::computeSharedSecret,
    ): String? {
        if (data["v"] != VERSION || data["k"] != keyId) return null
        return runCatching {
            val ephemeral = unb64(data.getValue("e"))
            val nonce = unb64(data.getValue("n"))
            val sealed = unb64(data.getValue("c"))
            val device = publicOf(privateKey)
            val key = hkdf(exchange(privateKey, ephemeral), ephemeral + device, INFO, 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(deviceId.toByteArray())
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        }.getOrNull()
    }

    /** RFC 5869, SHA-256. */
    internal fun hkdf(
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val n = minOf(block.size, length - done)
            block.copyInto(out, done, 0, n)
            done += n
            counter += 1
        }
        return out
    }

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
