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

/** A book section, ready to display: title and sanitized HTML (images inlined), and the contents entries in it. */
data class ParsedSection(
    val title: String,
    val html: String,
    val toc: List<ParsedTocEntry> = emptyList(),
)

/** A table-of-contents entry inside a section: [anchor] is the element's id (kept in the HTML), null for its start. */
data class ParsedTocEntry(
    val title: String,
    val depth: Int,
    val anchor: String?,
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
            // Ids stay: a contents entry inside a section (a Gutenberg file holds several chapters) points at one.
            .addAttributes(":all", "id")

    /**
     * `checkpoint` runs before each section: a big book takes seconds to read, and the download queue must be able to
     * stop it (it throws to stop).
     */
    fun parse(
        bytes: ByteArray,
        checkpoint: () -> Unit = {},
    ): List<ParsedSection> {
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
            val toc = tableOfContents(opf, items, base, ::text)
            val titles = toc.filter { it.linked }.map { it.path to it.title }.firstPerFile()
            return spine.mapIndexed { index, item ->
                checkpoint()
                val path = resolve(base, item.attr("href"))
                val document = Ksoup.parse(text(path).orEmpty())
                inlineImages(document, path.substringBeforeLast('/', ""), ::binary)
                val cleaned = clean(document)
                // An anchor the section doesn't hold (any more) opens the section at its start.
                val ids = cleaned.select("[id]").map { it.id() }.toSet()
                ParsedSection(
                    title =
                        titles[path] ?: document.selectFirst("h1, h2, title")?.text()?.takeIf { it.isNotBlank() } ?: "Section ${index + 1}",
                    html = cleaned.body().html(),
                    toc =
                        toc
                            .filter { it.path == path && it.title.isNotBlank() }
                            .map { ParsedTocEntry(it.title, it.depth, it.anchor?.takeIf { anchor -> anchor in ids }) },
                )
            }
        } finally {
            runCatching { fileSystem.delete(file) }
        }
    }

    /** A contents entry as the book lists it: the file and the anchor it opens, how deep it is nested. */
    private class RawTocEntry(
        val path: String,
        val anchor: String?,
        val title: String,
        val depth: Int,
        /** Its own link (else a label whose target is its first nested entry's). */
        val linked: Boolean,
    )

    /** The table of contents in reading order, nested: EPUB 3 nav, otherwise EPUB 2 NCX. */
    private fun tableOfContents(
        opf: Document,
        items: Map<String, com.fleeksoft.ksoup.nodes.Element>,
        base: String,
        text: (String) -> String?,
    ): List<RawTocEntry> {
        items.values.firstOrNull { "nav" in it.attr("properties").split(" ") }?.let { nav ->
            val navPath = resolve(base, nav.attr("href"))
            val document = Ksoup.parse(text(navPath).orEmpty())
            val toc =
                document.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: document.selectFirst("nav") ?: return emptyList()
            val navDir = navPath.substringBeforeLast('/', "")
            return toc.select("li").mapNotNull { li ->
                // The entry's label (a link, or a heading such as a span) comes before its nested list.
                val label = li.children().firstOrNull { it.normalName() != "ol" && it.normalName() != "ul" }
                val own = label?.let { if (it.normalName() == "a") it else it.selectFirst("a[href]") }
                val href = (own ?: li.selectFirst("a[href]"))?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RawTocEntry(
                    path = resolve(navDir, href.substringBefore('#')),
                    anchor = href.substringAfter('#', "").takeIf { it.isNotBlank() },
                    title = label?.text()?.trim().orEmpty(),
                    depth = li.parents().takeWhile { it != toc }.count { it.normalName() == "li" },
                    linked = own != null,
                )
            }
        }
        val ncxId = opf.selectFirst("spine")?.attr("toc").orEmpty()
        val ncx = items[ncxId] ?: items.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" } ?: return emptyList()
        val ncxPath = resolve(base, ncx.attr("href"))
        val document = Ksoup.parseXml(text(ncxPath).orEmpty())
        val ncxDir = ncxPath.substringBeforeLast('/', "")
        return document.select("navPoint").map { point ->
            val src =
                point
                    .children()
                    .firstOrNull { it.normalName() == "content" }
                    ?.attr("src")
                    .orEmpty()
            RawTocEntry(
                path = resolve(ncxDir, src.substringBefore('#')),
                anchor = src.substringAfter('#', "").takeIf { it.isNotBlank() },
                title =
                    point
                        .children()
                        .firstOrNull { it.normalName() == "navlabel" }
                        ?.selectFirst("text")
                        ?.text()
                        ?.trim()
                        .orEmpty(),
                depth = point.parents().count { it.normalName() == "navpoint" },
                linked = true,
            )
        }
    }

    /**
     * A file holding several chapters (Gutenberg splits its books every few chapters) is named after the first one:
     * the section starts there. The last one used to win ("CHAPTER 3. The Spouter-Inn." for chapters 1 to 3).
     */
    private fun List<Pair<String, String>>.firstPerFile(): Map<String, String> =
        filter { it.second.isNotBlank() }.distinctBy { it.first }.toMap()

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

    private fun clean(document: Document): Document {
        val cleaned = Ksoup.parse(Ksoup.clean(document.body().html(), safelist))
        // No navigation out of a book: links stay plain text.
        cleaned.select("a[href]").forEach { it.removeAttr("href") }
        return cleaned
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
