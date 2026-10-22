package com.vovoscan.core.sources

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * MD5 digest as lowercase hex (RFC 1321), implemented in pure Kotlin so every platform hashes identically.
 * Used by `then: ["md5"]` for sources that sign their requests (e.g. a token of the current time).
 */
fun md5Hex(input: String): String {
    val msg = input.encodeToByteArray()
    val bitLen = msg.size.toLong() * 8

    // Padding: 0x80 then zeros until length ≡ 56 (mod 64), then the original bit length, little-endian.
    val paddedLen = ((msg.size + 8) / 64 + 1) * 64
    val padded = ByteArray(paddedLen)
    msg.copyInto(padded)
    padded[msg.size] = 0x80.toByte()
    for (i in 0 until 8) padded[paddedLen - 8 + i] = (bitLen ushr (8 * i)).toByte()

    var a0 = 0x67452301.toInt()
    var b0 = 0xEFCDAB89.toInt()
    var c0 = 0x98BADCFE.toInt()
    var d0 = 0x10325476.toInt()

    val s = intArrayOf(7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21)
    val k = IntArray(64) { i -> floor(4294967296.0 * abs(sin(i + 1.0))).toLong().toInt() }

    for (blockStart in padded.indices step 64) {
        val m = IntArray(16) { i ->
            val p = blockStart + i * 4
            (padded[p].toInt() and 0xFF) or
                ((padded[p + 1].toInt() and 0xFF) shl 8) or
                ((padded[p + 2].toInt() and 0xFF) shl 16) or
                ((padded[p + 3].toInt() and 0xFF) shl 24)
        }

        var a = a0
        var b = b0
        var c = c0
        var d = d0

        for (i in 0 until 64) {
            val (f, g) =
                when {
                    i < 16 -> ((b and c) or (b.inv() and d)) to i
                    i < 32 -> ((d and b) or (d.inv() and c)) to ((5 * i + 1) % 16)
                    i < 48 -> (b xor c xor d) to ((3 * i + 5) % 16)
                    else -> (c xor (b or d.inv())) to ((7 * i) % 16)
                }
            val tmp = d
            d = c
            c = b
            b =
                b +
                    (
                        a +
                            f +
                            k[i] +
                            m[g]
                    ).rotateLeft(s[i])
            a = tmp
        }

        a0 += a
        b0 += b
        c0 += c
        d0 += d
    }

    fun le(x: Int) =
        buildString {
            for (i in 0 until 4) append(((x ushr (8 * i)) and 0xFF).toString(16).padStart(2, '0'))
        }

    return le(a0) + le(b0) + le(c0) + le(d0)
}
