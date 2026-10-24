package com.vovoscan.core.common

/**
 * Genres or tags that say a comic is one vertical strip (manhwa, most manhua): read top to bottom, as a webtoon (the
 * server's `STRIP_TAGS`). Only for a title whose source names no format.
 */
object StripGenres {
    private val TAGS = setOf("long strip", "longstrip", "webtoon", "webtoons", "manhwa", "manhua", "vertical", "vertical scroll")

    fun named(genres: List<String>): Boolean = genres.any { it.trim().lowercase() in TAGS }
}
