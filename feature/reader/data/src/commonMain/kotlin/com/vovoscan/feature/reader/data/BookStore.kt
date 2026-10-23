package com.vovoscan.feature.reader.data

import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.TocEntry

/**
 * A parsed book written in place of what was kept for [key]: its sections and its table of contents, numbered in
 * reading order. Runs inside the caller's transaction (opening a book, or the download queue saving one).
 */
internal fun ReaderDatabase.putBook(
    key: String,
    sections: List<ParsedSection>,
) {
    readerQueries.deleteSections(key)
    bookQueries.deleteToc(key)
    var entry = 0L
    sections.forEachIndexed { index, section ->
        readerQueries.putSection(key, index.toLong(), section.title, section.html)
        section.toc.forEach { bookQueries.putTocEntry(key, entry++, index.toLong(), it.depth.toLong(), it.title, it.anchor) }
    }
}

/** A book's sections and contents go together (removed from the device). */
internal fun ReaderDatabase.deleteBook(key: String) {
    readerQueries.deleteSections(key)
    bookQueries.deleteToc(key)
}

internal fun ReaderDatabase.toc(key: String): List<TocEntry> =
    bookQueries.toc(key).executeAsList().map { TocEntry(it.title, it.depth.toInt(), it.section_index.toInt(), it.anchor) }
