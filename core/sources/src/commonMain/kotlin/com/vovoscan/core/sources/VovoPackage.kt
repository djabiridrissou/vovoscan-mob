package com.vovoscan.core.sources

import com.vovoscan.core.common.AppResult
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import kotlin.random.Random

/** Lowercase hex SHA-256 of [bytes]. */
fun sha256Hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

/** The content of a `.vovo` file: its manifest, and optionally an icon and a README. */
class VovoPackage(
    val manifest: ByteArray,
    val icon: ByteArray?,
    val readme: String?,
)

/**
 * Reads a `.vovo` package (a zip): `source.json` at the root (required), `icon.png` and `README.md` (optional),
 * nothing else. The central directory is checked before anything is decompressed: size, number of entries,
 * names (no folder, no `..`, no absolute path), declared sizes.
 */
class VovoPackageReader(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val tempDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY,
) {
    fun read(bytes: ByteArray): AppResult<VovoPackage> {
        if (bytes.size > PluginLimits.PACKAGE_MAX_BYTES) return PluginErrors.tooLarge
        val entries = ZipDirectory.entries(bytes) ?: return invalid
        if (entries.size > PluginLimits.PACKAGE_MAX_ENTRIES) return invalid
        val names = entries.map { it.name }
        if (names.toSet().size != names.size || names.any { it !in limits }) return invalid
        if (entries.any { it.uncompressedSize > limits.getValue(it.name) }) return PluginErrors.tooLarge
        if (MANIFEST !in names) return invalid
        val file = tempDir / "vovoscan-${Random.nextLong().toULong()}.vovo"
        return try {
            fileSystem.write(file) { write(bytes) }
            val zip = fileSystem.openZip(file)

            fun entry(name: String): ByteArray? = if (name in names) zip.read("/$name".toPath()) { readByteArray() } else null
            AppResult.Ok(VovoPackage(entry(MANIFEST)!!, entry(ICON), entry(README)?.decodeToString()))
        } catch (e: Exception) {
            invalid
        } finally {
            runCatching { fileSystem.delete(file) }
        }
    }

    private val invalid get() = PluginErrors.rejected("PLUGIN_PACKAGE", "This file isn't a valid .vovo package.")

    private companion object {
        const val MANIFEST = "source.json"
        const val ICON = "icon.png"
        const val README = "README.md"
        val limits =
            mapOf(
                MANIFEST to PluginLimits.MANIFEST_MAX_BYTES.toLong(),
                ICON to PluginLimits.ICON_MAX_BYTES.toLong(),
                README to PluginLimits.MANIFEST_MAX_BYTES.toLong(),
            )
    }
}

/** Raw entries of a zip's central directory (names exactly as stored), or null when it cannot be read. */
internal object ZipDirectory {
    class Entry(
        val name: String,
        val compressedSize: Long,
        val uncompressedSize: Long,
    )

    private const val END_SIGNATURE = 0x06054b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val END_SIZE = 22
    private const val CENTRAL_SIZE = 46
    private const val ZIP64_MARKER = 0xFFFFFFFFL

    fun entries(bytes: ByteArray): List<Entry>? {
        if (bytes.size < END_SIZE) return null
        val end =
            (bytes.size - END_SIZE downTo maxOf(0, bytes.size - END_SIZE - 0xFFFF)).firstOrNull { u32(bytes, it) == END_SIGNATURE }
                ?: return null
        val count = u16(bytes, end + 10)
        val directoryOffset = u32(bytes, end + 16)
        if (directoryOffset == ZIP64_MARKER || directoryOffset >= bytes.size) return null
        var at = directoryOffset.toInt()
        val result = mutableListOf<Entry>()
        repeat(count) {
            if (at + CENTRAL_SIZE > bytes.size || u32(bytes, at) != CENTRAL_SIGNATURE) return null
            val compressed = u32(bytes, at + 20)
            val uncompressed = u32(bytes, at + 24)
            val nameLength = u16(bytes, at + 28)
            val extraLength = u16(bytes, at + 30)
            val commentLength = u16(bytes, at + 32)
            if (compressed == ZIP64_MARKER || uncompressed == ZIP64_MARKER) return null
            val nameEnd = at + CENTRAL_SIZE + nameLength
            if (nameEnd > bytes.size) return null
            val name = bytes.decodeToString(at + CENTRAL_SIZE, nameEnd)
            result += Entry(name, compressed, uncompressed)
            at = nameEnd + extraLength + commentLength
        }
        return result
    }

    private fun u16(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(
        bytes: ByteArray,
        at: Int,
    ): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
}
