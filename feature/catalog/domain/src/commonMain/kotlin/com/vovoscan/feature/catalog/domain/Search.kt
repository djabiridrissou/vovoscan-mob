package com.vovoscan.feature.catalog.domain

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.ContentRating

/** Why a title is suggested: this account opened it before, or readers who searched the same opened it. */
enum class SuggestReason {
    Yours,
    Popular,
    ;

    companion object {
        fun fromApi(value: String?): SuggestReason? =
            when (value) {
                "yours" -> Yours
                "popular" -> Popular
                else -> null
            }
    }
}

/** A title the server suggests for what is typed. Its cover comes only for our own source (signed). */
data class SuggestedTitle(
    val sourceKey: String,
    val titleRef: String,
    val name: String,
    val contentRating: ContentRating? = null,
    /** null: nobody knows yet how it reads. */
    val format: ReadingFormat? = null,
    val coverUrl: String? = null,
    /** null: found on the device (offline), not learned. */
    val reason: SuggestReason? = null,
) {
    /** A card for a title no source returned for this spelling (the server's typo tolerance). */
    fun toSummary(): TitleSummary =
        TitleSummary(
            sourceKey = sourceKey,
            id = titleRef,
            name = name,
            coverUrl = coverUrl,
            kind = if (format == ReadingFormat.Text) ContentKind.Book else ContentKind.Comic,
            format = format ?: ReadingFormat.Manga,
            status = "",
            contentRating = contentRating,
        )
}

/** What the search field offers: this account's searches, others' searches, titles. */
data class Suggestions(
    val recent: List<String> = emptyList(),
    val queries: List<String> = emptyList(),
    val titles: List<SuggestedTitle> = emptyList(),
    /** Made on the device (no network, or the server didn't answer): recent searches and saved titles. */
    val offline: Boolean = false,
)

/** The user opened a title from search results or a suggested title, with what was typed. */
data class SearchPick(
    val query: String,
    val sourceKey: String,
    val titleRef: String,
    val titleName: String,
    val contentRating: ContentRating? = null,
    /** How it reads as the device knows it; null when unknown (never a guess). */
    val format: ReadingFormat? = null,
)

/**
 * Search learned from picks (server `search`): suggestions as the user types, the picks that teach it, and the
 * account's choice to take part. Picks are fire and forget: offline they wait on the device (7 days at most).
 */
interface SearchRepository {
    /** [format]: the catalog's format filter, so suggestions follow it. Offline: what the device has. */
    suspend fun suggest(
        query: String,
        limit: Int = SUGGEST_LIMIT,
        format: ReadingFormat? = null,
    ): Suggestions

    suspend fun recordPick(pick: SearchPick)

    /** Sends the picks made offline; the ones refused or older than 7 days are dropped. */
    suspend fun sendPending()

    /** Whether the account learns from its searches (online only). */
    suspend fun learning(): AppResult<Boolean>

    /** Off deletes the account's picks on the server and keeps none, here too. */
    suspend fun setLearning(on: Boolean): AppResult<Boolean>

    /** Clears the account's searches and picks; the count removed on the server. */
    suspend fun clearHistory(): AppResult<Int>

    companion object {
        const val SUGGEST_LIMIT = 8

        /** Suggested titles asked for to order a search's results. */
        const val RESULTS_LIMIT = 50

        /** Nothing learned, nothing suggested (tests, a server without search). */
        val None: SearchRepository =
            object : SearchRepository {
                override suspend fun suggest(
                    query: String,
                    limit: Int,
                    format: ReadingFormat?,
                ) = Suggestions()

                override suspend fun recordPick(pick: SearchPick) = Unit

                override suspend fun sendPending() = Unit

                override suspend fun learning(): AppResult<Boolean> = AppResult.Ok(false)

                override suspend fun setLearning(on: Boolean): AppResult<Boolean> = AppResult.Ok(false)

                override suspend fun clearHistory(): AppResult<Int> = AppResult.Ok(0)
            }
    }
}

object SearchRules {
    /** A pick teaches something only with at least 2 characters typed (the server's rule after normalising). */
    const val MIN_PICK_QUERY = 2

    /** Searches kept on the device for offline suggestions. */
    const val RECENT_KEPT = 10

    /** Picks waiting on the device. */
    const val PENDING_KEPT = 100

    /** A pick older than this is dropped rather than sent. */
    const val PENDING_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Lowercase, accents and spaces folded: "Kingdom", " kíngdom " and "KINGDOM" are one search. */
    fun normalise(text: String): String =
        text
            .trim()
            .lowercase()
            .map { ACCENTS[it] ?: it }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** [query] first, an earlier spelling of the same search removed, [RECENT_KEPT] at most. */
    fun remember(
        recent: List<String>,
        query: String,
    ): List<String> {
        val typed = query.trim()
        if (normalise(typed).length < MIN_PICK_QUERY) return recent
        val key = normalise(typed)
        return (listOf(typed) + recent.filter { normalise(it) != key }).take(RECENT_KEPT)
    }

    /** Recent searches that start with (or contain) what is typed; all of them for an empty field. */
    fun matching(
        recent: List<String>,
        query: String,
    ): List<String> {
        val key = normalise(query)
        if (key.isEmpty()) return recent
        return recent.filter { normalise(it).let { r -> r.startsWith(key) || r.contains(key) } && normalise(it) != key }
    }

    /**
     * A search's results: the suggested titles first, in their order (one no source returned for this spelling too,
     * when [extras] allows it), then the sources' mix; each title once.
     */
    fun order(
        suggested: List<SuggestedTitle>,
        found: List<TitleSummary>,
        extras: Boolean = true,
    ): List<TitleSummary> {
        val byKey = found.associateBy { it.sourceKey to it.id }
        val first =
            suggested
                .distinctBy { it.sourceKey to it.titleRef }
                .mapNotNull { s -> byKey[s.sourceKey to s.titleRef] ?: s.takeIf { extras }?.toSummary() }
        val seen = first.mapTo(HashSet()) { it.sourceKey to it.id }
        return first + found.filter { seen.add(it.sourceKey to it.id) }
    }

    private val ACCENTS: Map<Char, Char> =
        buildMap {
            "àáâãäåāă".forEach { put(it, 'a') }
            "çćč".forEach { put(it, 'c') }
            "èéêëēėę".forEach { put(it, 'e') }
            "ìíîïī".forEach { put(it, 'i') }
            "ñń".forEach { put(it, 'n') }
            "òóôõöøō".forEach { put(it, 'o') }
            "ùúûüū".forEach { put(it, 'u') }
            "ýÿ".forEach { put(it, 'y') }
            "šś".forEach { put(it, 's') }
            "žźż".forEach { put(it, 'z') }
        }
}
