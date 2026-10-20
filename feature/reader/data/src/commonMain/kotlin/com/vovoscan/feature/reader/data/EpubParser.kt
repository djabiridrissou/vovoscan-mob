package com.vovoscan.feature.reader.data

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.safety.Safelist
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/** A book section, ready to display: title and sanitized HTML (images inlined). */
data class ParsedSection(
    val title: String,
    val html: String,
)

class InvalidEpub(
    message: String,
) : Exception(message)

/**
 * Opens an EPUB on the device: ZIP archive (Okio), `container.xml` → OPF → reading order (spine) and table of
 * contents (EPUB 3 nav or EPUB 2 NCX). Each section is sanitized: no script, no style, no event handler, no iframe,
 * no outgoing link; images from the archive are inlined (data:), nothing is loaded from the web.
 */
class EpubParser(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val tempDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY,
) {
    private val safelist =
        Safelist
            .relaxed()
            .addTags("section", "article", "figure", "figcaption", "aside", "hr", "small", "sup", "sub")
            .addProtocols("img", "src", "data")

    fun parse(bytes: ByteArray): List<ParsedSection> {
        val file = tempDir / "vovoscan-${Random.nextLong().toULong()}.epub"
        fileSystem.write(file) { write(bytes) }
        try {
            val zip = runCatching { fileSystem.openZip(file) }.getOrElse { throw InvalidEpub("Not an EPUB file.") }

            fun text(path: String): String? = runCatching { zip.read(path.toZipPath()) { readUtf8() } }.getOrNull()

            fun binary(path: String): ByteArray? = runCatching { zip.read(path.toZipPath()) { readByteArray() } }.getOrNull()

            val opfPath =
                text("META-INF/container.xml")?.let { Ksoup.parseXml(it).selectFirst("rootfile")?.attr("full-path") }
                    ?: throw InvalidEpub("EPUB without container.")
            val opf = Ksoup.parseXml(text(opfPath) ?: throw InvalidEpub("EPUB without package."))
            val base = opfPath.substringBeforeLast('/', "")
            val items = opf.select("manifest > item").associate { it.attr("id") to it }
            val spine =
                opf
                    .select("spine > itemref")
                    .filter { it.attr("linear") != "no" }
                    .mapNotNull { items[it.attr("idref")] }
                    .filter { it.attr("media-type").contains("html") }
            if (spine.isEmpty()) throw InvalidEpub("EPUB without readable sections.")
            val titles = tableOfContents(opf, items, base, ::text)
            return spine.mapIndexed { index, item ->
                val path = resolve(base, item.attr("href"))
                val document = Ksoup.parse(text(path).orEmpty())
                inlineImages(document, path.substringBeforeLast('/', ""), ::binary)
                ParsedSection(
                    title =
                        titles[path] ?: document.selectFirst("h1, h2, title")?.text()?.takeIf { it.isNotBlank() } ?: "Section ${index + 1}",
                    html = clean(document),
                )
            }
        } finally {
            runCatching { fileSystem.delete(file) }
        }
    }

    /** Titles by section path: EPUB 3 nav, otherwise EPUB 2 NCX. */
    private fun tableOfContents(
        opf: Document,
        items: Map<String, com.fleeksoft.ksoup.nodes.Element>,
        base: String,
        text: (String) -> String?,
    ): Map<String, String> {
        items.values.firstOrNull { "nav" in it.attr("properties").split(" ") }?.let { nav ->
            val navPath = resolve(base, nav.attr("href"))
            val document = Ksoup.parse(text(navPath).orEmpty())
            val toc = document.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: document.selectFirst("nav")
            val navDir = navPath.substringBeforeLast('/', "")
            return toc?.select("a")?.associate { resolve(navDir, it.attr("href").substringBefore('#')) to it.text().trim() }.orEmpty()
        }
        val ncxId = opf.selectFirst("spine")?.attr("toc").orEmpty()
        val ncx = items[ncxId] ?: items.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" } ?: return emptyMap()
        val ncxPath = resolve(base, ncx.attr("href"))
        val document = Ksoup.parseXml(text(ncxPath).orEmpty())
        val ncxDir = ncxPath.substringBeforeLast('/', "")
        return document.select("navPoint").associate { point ->
            resolve(
                ncxDir,
                point
                    .selectFirst("content")
                    ?.attr("src")
                    .orEmpty()
                    .substringBefore('#'),
            ) to
                point
                    .selectFirst("navLabel text")
                    ?.text()
                    ?.trim()
                    .orEmpty()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun inlineImages(
        document: Document,
        dir: String,
        binary: (String) -> ByteArray?,
    ) {
        for (img in document.select("img")) {
            val src = img.attr("src")
            val type = imageType(src)
            val data = if (type != null && !src.contains("://")) binary(resolve(dir, src)) else null
            if (data == null || type == null || data.size > MAX_IMAGE_BYTES) {
                img.remove()
            } else {
                img.attr("src", "data:$type;base64,${Base64.encode(data)}")
            }
        }
    }

    private fun clean(document: Document): String {
        val cleaned = Ksoup.parse(Ksoup.clean(document.body().html(), safelist))
        // No navigation out of a book: links stay plain text.
        cleaned.select("a[href]").forEach { it.removeAttr("href") }
        return cleaned.body().html()
    }

    private fun imageType(src: String): String? =
        when (src.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null
        }

    private companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}

/** Path inside the archive, with ".." and "." resolved and no leading slash. */
internal fun resolve(
    dir: String,
    href: String,
): String {
    val parts = mutableListOf<String>()
    ("$dir/$href").split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}

private fun String.toZipPath(): Path = ("/" + this).toPath()
