package com.vovoscan.core.testing

/** Writes a small zip (stored entries, no compression) for the tests. */
object TestZip {
    fun of(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = mutableListOf<Byte>()
        val central = mutableListOf<Byte>()
        entries.forEach { (name, data) ->
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)
            val offset = out.size
            out.u32(0x04034b50)
            out.u16(20)
            out.u16(0)
            out.u16(0)
            out.u16(0)
            out.u16(0)
            out.u32(crc)
            out.u32(data.size.toLong())
            out.u32(data.size.toLong())
            out.u16(nameBytes.size)
            out.u16(0)
            out.addAll(nameBytes.toList())
            out.addAll(data.toList())
            central.u32(0x02014b50)
            central.u16(20)
            central.u16(20)
            central.u16(0)
            central.u16(0)
            central.u16(0)
            central.u16(0)
            central.u32(crc)
            central.u32(data.size.toLong())
            central.u32(data.size.toLong())
            central.u16(nameBytes.size)
            central.u16(0)
            central.u16(0)
            central.u16(0)
            central.u16(0)
            central.u32(0)
            central.u32(offset.toLong())
            central.addAll(nameBytes.toList())
        }
        val centralOffset = out.size
        out.addAll(central)
        out.u32(0x06054b50)
        out.u16(0)
        out.u16(0)
        out.u16(entries.size)
        out.u16(entries.size)
        out.u32(central.size.toLong())
        out.u32(centralOffset.toLong())
        out.u16(0)
        return out.toByteArray()
    }

    private fun MutableList<Byte>.u16(value: Int) {
        add((value and 0xFF).toByte())
        add(((value shr 8) and 0xFF).toByte())
    }

    private fun MutableList<Byte>.u32(value: Long) {
        u16((value and 0xFFFF).toInt())
        u16(((value shr 16) and 0xFFFF).toInt())
    }

    private fun crc32(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (b in data) {
            crc = crc xor (b.toLong() and 0xFF)
            repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
        }
        return crc xor 0xFFFFFFFFL
    }
}
