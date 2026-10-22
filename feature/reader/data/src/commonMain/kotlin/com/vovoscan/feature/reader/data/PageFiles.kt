package com.vovoscan.feature.reader.data

import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Downloaded pages on disk, under the app's downloads folder:
 * `<sha256(sourceKey)[0..24]>/<sha256(sourceKey/chapterId)[0..24]>/0007.jpg`. Ids from third-party sources never
 * become paths themselves; a source or a chapter is one folder, erased in one go. The database keeps paths relative
 * to the folder (the iOS container moves with app updates).
 */
class PageFiles(
    private val root: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    constructor(root: String) : this(root.toPath())

    /** Relative path of a page, its extension taken from the image's first bytes. */
    fun pagePath(
        sourceKey: String,
        chapterId: String,
        index: Int,
        bytes: ByteArray,
    ): String = chapterFolder(sourceKey, chapterId) + "/" + index.toString().padStart(4, '0') + "." + imageExtension(bytes)

    /** Written whole, then moved into place: a page on disk is never half written. */
    fun write(
        relative: String,
        bytes: ByteArray,
    ) {
        val target = root / relative
        target.parent?.let { fileSystem.createDirectories(it) }
        val partial = "$target.part".toPath()
        fileSystem.write(partial) { write(bytes) }
        fileSystem.atomicMove(partial, target)
    }

    fun exists(relative: String): Boolean = fileSystem.exists(root / relative)

    /** `file://` URL of a page, for the image loaders (Coil, AsyncImage). */
    fun url(relative: String): String = fileUrl((root / relative).toString())

    fun deleteChapter(
        sourceKey: String,
        chapterId: String,
    ) = fileSystem.deleteRecursively(root / chapterFolder(sourceKey, chapterId), mustExist = false)

    fun deleteSource(sourceKey: String) = fileSystem.deleteRecursively(root / hash(sourceKey), mustExist = false)

    fun deleteAll() = fileSystem.deleteRecursively(root, mustExist = false)

    private fun chapterFolder(
        sourceKey: String,
        chapterId: String,
    ) = hash(sourceKey) + "/" + hash(chapterKey(sourceKey, chapterId))

    private fun hash(value: String) =
        value
            .encodeUtf8()
            .sha256()
            .hex()
            .take(24)
}

/** "jpg", "png", "webp", "gif" from the file's signature; "img" when unknown (the image decoders sniff anyway). */
internal fun imageExtension(bytes: ByteArray): String {
    fun at(
        i: Int,
        value: Int,
    ) = bytes.size > i && (bytes[i].toInt() and 0xFF) == value
    return when {
        at(0, 0xFF) && at(1, 0xD8) -> "jpg"
        at(0, 0x89) && at(1, 0x50) && at(2, 0x4E) && at(3, 0x47) -> "png"
        at(0, 0x47) && at(1, 0x49) && at(2, 0x46) -> "gif"
        at(0, 0x52) && at(1, 0x49) && at(2, 0x46) && at(3, 0x46) && at(8, 0x57) && at(9, 0x45) && at(10, 0x42) && at(11, 0x50) -> "webp"
        else -> "img"
    }
}

/** `file://` URL of an absolute path, percent-encoded ("Application Support" has a space). */
internal fun fileUrl(path: String): String {
    val encoded = StringBuilder("file://")
    for (byte in path.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val keep = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~/"
        if (keep) encoded.append(c.toChar()) else encoded.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
    }
    return encoded.toString()
}

private const val HEX = "0123456789ABCDEF"
