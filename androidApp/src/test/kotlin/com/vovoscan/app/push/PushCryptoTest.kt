package com.vovoscan.app.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A push sealed by the backend's `core/push.py: seal` with fixed keys and nonce (Python `cryptography`). */
class PushCryptoTest {
    private val privateKey = PushCrypto.unb64("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA")
    private val sealed =
        mapOf(
            "v" to "1",
            "k" to "k1",
            "e" to "VxR2nRFr92Q2rnS8eT0sMK0ZA8WaxSc4BcfiaYtBDDY",
            "n" to "yMnKy8zNzs_Q0dLT",
            "c" to
                "Jpcre9wUxRfmGDIgSi3AnrTC2HebqvWUFOvdTRX0872GSa3ZKxO1jVQJYBC9PmVpVw6KpGlELGw3EvEMNfg8UIyBla6Kc94mG08w3cQK_mZQ9GJxv3pH" +
                "25nQ75ZA_j5Nvl3BIHWZEAffJ8OTvkzYoboejC8rbxPDCTxbObFwTFFvImDsX7DqB-rYc32ufdF3zgPHjvlK603sv2t2nBgjAsTSD1zv-A",
        )

    @Test
    fun theDevicePublicKeyIsTheServersExpectation() {
        assertEquals("B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9_AsrhtHHw", PushCrypto.b64(PushCrypto.publicOf(privateKey)))
    }

    @Test
    fun aSealedPushOpensWithTheDeviceKey() {
        assertEquals(
            """{"id":"n1","kind":"game.turn","data":{"groupName":"Ink Club","handle":"ink-club","gameId":"42","game":"four"},""" +
                """"createdAt":"2026-10-24T10:00:00+00:00"}""",
            PushCrypto.open(sealed, "k1", "dev-1", privateKey),
        )
    }

    @Test
    fun anythingElseStaysClosed() {
        assertNull(PushCrypto.open(sealed, "k1", "dev-2", privateKey), "another device id")
        assertNull(PushCrypto.open(sealed, "k2", "dev-1", privateKey), "another key id")
        assertNull(PushCrypto.open(sealed + ("v" to "2"), "k1", "dev-1", privateKey), "another version")
        assertNull(PushCrypto.open(sealed + ("c" to sealed.getValue("c").replaceFirst('J', 'K')), "k1", "dev-1", privateKey), "tampered")
        assertNull(PushCrypto.open(sealed - "n", "k1", "dev-1", privateKey), "incomplete")
    }

    @Test
    fun hkdfMatchesRfc5869() {
        // RFC 5869, test case 1.
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = PushCrypto.hkdf(ikm, salt, info, 42).joinToString("") { "%02x".format(it) }
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm)
    }
}
