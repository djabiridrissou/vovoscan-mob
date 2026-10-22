package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.ContentRating
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.sources.sourceLanguage
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.INTERNAL_SOURCE
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SearchPick
import com.vovoscan.feature.catalog.domain.SearchRepository
import com.vovoscan.feature.catalog.domain.SearchRules
import com.vovoscan.feature.catalog.domain.SuggestReason
import com.vovoscan.feature.catalog.domain.SuggestedTitle
import com.vovoscan.feature.catalog.domain.Suggestions
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class SuggestedTitleDto(
    val sourceKey: String,
    val titleRef: String,
    val name: String,
    val contentRating: String? = null,
    val lang: String? = null,
    val format: String? = null,
    val coverUrl: String? = null,
    val reason: String? = null,
    val episodeCount: Int = 0,
)

@Serializable
data class SuggestionsDto(
    val recent: List<String> = emptyList(),
    val queries: List<String> = emptyList(),
    val titles: List<SuggestedTitleDto> = emptyList(),
)

/** `POST search/picks`, and a pick waiting on the device (`at`: when it was made, never sent). */
@Serializable
data class PickDto(
    val query: String,
    val sourceKey: String,
    val titleRef: String,
    val titleName: String? = null,
    val lang: String? = null,
    val contentRating: String? = null,
    val format: String? = null,
    val at: Long = 0,
)

@Serializable
data class PickedDto(
    val recorded: Boolean = false,
)

@Serializable
data class SearchPreferencesDto(
    val learn: Boolean,
)

@Serializable
data class ClearedDto(
    val removed: Int = 0,
)

/** `api/v1/search/…`: suggestions learned from picks (the server's `search` module). */
interface SearchRemoteDataSource {
    suspend fun suggest(
        query: String,
        lang: String,
        limit: Int,
        format: String?,
        episodes: Boolean = false,
    ): AppResult<SuggestionsDto>

    suspend fun pick(pick: PickDto): AppResult<Boolean>

    suspend fun preferences(): AppResult<Boolean>

    suspend fun setPreferences(learn: Boolean): AppResult<Boolean>

    suspend fun clearHistory(): AppResult<Int>
}

class ApiSearchRemoteDataSource(
    private val http: HttpClient,
) : SearchRemoteDataSource {
    override suspend fun suggest(
        query: String,
        lang: String,
        limit: Int,
        format: String?,
        episodes: Boolean,
    ): AppResult<SuggestionsDto> =
        http
            .apiCall<ApiData<SuggestionsDto>> {
                get("api/v1/search/suggest") {
                    parameter("q", query)
                    parameter("lang", lang)
                    parameter("limit", limit)
                    format?.let { parameter("format", it) }
                    if (episodes) parameter("has", "episode")
                }
            }.map { it.data }

    // `at` stays on the device.
    override suspend fun pick(pick: PickDto): AppResult<Boolean> =
        http
            .apiCall<ApiData<PickedDto>> {
                post("api/v1/search/picks") {
                    contentType(ContentType.Application.Json)
                    setBody(PickBody(pick.query, pick.sourceKey, pick.titleRef, pick.titleName, pick.lang, pick.contentRating, pick.format))
                }
            }.map { it.data.recorded }

    override suspend fun preferences(): AppResult<Boolean> =
        http.apiCall<ApiData<SearchPreferencesDto>> { get("api/v1/search/preferences") }.map { it.data.learn }

    override suspend fun setPreferences(learn: Boolean): AppResult<Boolean> =
        http
            .apiCall<ApiData<SearchPreferencesDto>> {
                put("api/v1/search/preferences") {
                    contentType(ContentType.Application.Json)
                    setBody(SearchPreferencesDto(learn))
                }
            }.map { it.data.learn }

    override suspend fun clearHistory(): AppResult<Int> =
        http.apiCall<ApiData<ClearedDto>> { delete("api/v1/search/history") }.map { it.data.removed }

    @Serializable
    private data class PickBody(
        val query: String,
        val sourceKey: String,
        val titleRef: String,
        val titleName: String? = null,
        val lang: String? = null,
        val contentRating: String? = null,
        val format: String? = null,
    )
}

/**
 * Search learned from picks. Suggestions come from the server; without it (no network, or it doesn't answer) from the
 * device: the searches kept here and the saved titles. Picks are queued here first (`search/pending` in
 * `catalog_setting`, so nothing waits on a schema change) and sent at once, or with the next pick or search when the
 * network is back; the ones the server refuses, and those older than 7 days, are dropped. Signing out empties the
 * table, picks and searches included.
 */
class SearchRepositoryImpl(
    private val remote: SearchRemoteDataSource,
    private val database: CatalogDatabase,
    private val io: CoroutineDispatcher,
    private val clock: Clock = SystemClock,
    private val language: () -> String = ::sourceLanguage,
) : SearchRepository {
    private val queries get() = database.catalogQueries
    private val sending = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun suggest(
        query: String,
        limit: Int,
        format: ReadingFormat?,
        episodes: Boolean,
    ): Suggestions {
        val trimmed = query.trim().take(MAX_QUERY)
        return when (val result = remote.suggest(trimmed, language(), limit, format?.wire, episodes)) {
            is AppResult.Ok -> {
                if (pendingCount() > 0) sendPending()
                Suggestions(
                    recent = result.value.recent,
                    queries = result.value.queries,
                    titles = result.value.titles.map { it.toDomain() },
                )
            }
            is AppResult.Err -> onDevice(trimmed, limit, format, episodes)
        }
    }

    private suspend fun onDevice(
        query: String,
        limit: Int,
        format: ReadingFormat?,
        episodes: Boolean,
    ): Suggestions =
        withContext(io) {
            val recent = SearchRules.matching(readList(RECENT), query).take(RECENT_SHOWN)
            val titles =
                if (query.isBlank()) {
                    emptyList()
                } else {
                    queries
                        .searchAllTitles(query, limit.toLong(), ::summary)
                        .executeAsList()
                        .filter { (format == null || it.format == format) && (!episodes || it.episodeCount > 0) }
                        .map {
                            SuggestedTitle(
                                it.sourceKey,
                                it.id,
                                it.name,
                                it.contentRating,
                                it.format,
                                it.coverUrl,
                                episodeCount = it.episodeCount,
                            )
                        }
                }
            Suggestions(recent = recent, titles = titles, offline = true)
        }

    override suspend fun recordPick(pick: SearchPick) {
        if (SearchRules.normalise(pick.query).length < SearchRules.MIN_PICK_QUERY) return
        val queued =
            withContext(io) {
                // Turned off: nothing is kept, here or there.
                if (queries.setting(LEARN).executeAsOneOrNull() == OFF) return@withContext false
                queries.transaction {
                    writeList(RECENT, SearchRules.remember(readList(RECENT), pick.query))
                    val entry =
                        PickDto(
                            query = pick.query.trim().take(MAX_QUERY),
                            sourceKey = pick.sourceKey,
                            titleRef = pick.titleRef,
                            // Required for a source read on the device; the server knows its own titles.
                            titleName = pick.titleName.takeIf { pick.sourceKey != INTERNAL_SOURCE },
                            lang = language(),
                            contentRating = pick.contentRating?.apiValue,
                            format = pick.format?.wire,
                            at = clock.nowMillis(),
                        )
                    writePending((readPending() + entry).takeLast(SearchRules.PENDING_KEPT))
                }
                true
            }
        if (queued) sendPending()
    }

    override suspend fun sendPending() {
        sending.withLock {
            val now = clock.nowMillis()
            val pending = withContext(io) { readPending() }
            val fresh = pending.filter { now - it.at <= SearchRules.PENDING_MAX_AGE_MS }
            val sent = mutableSetOf<PickDto>()
            for (entry in fresh) {
                when (val result = remote.pick(entry)) {
                    is AppResult.Ok -> sent += entry
                    // Refused for good (a source turned off, a title gone…): sending it again would never help.
                    is AppResult.Err -> if (result.error.isPermanent()) sent += entry else break
                }
            }
            if (sent.isEmpty() && fresh.size == pending.size) return
            withContext(io) {
                queries.transaction {
                    // Picks made while these were sent stay queued.
                    writePending(readPending().filter { it !in sent && now - it.at <= SearchRules.PENDING_MAX_AGE_MS })
                }
            }
        }
    }

    override suspend fun learning(): AppResult<Boolean> = remote.preferences().also { remember(it) }

    override suspend fun setLearning(on: Boolean): AppResult<Boolean> {
        val result = remote.setPreferences(on)
        remember(result)
        if (result is AppResult.Ok && !result.value) forget()
        return result
    }

    override suspend fun clearHistory(): AppResult<Int> {
        val result = remote.clearHistory()
        if (result is AppResult.Ok) forget()
        return result
    }

    private suspend fun remember(result: AppResult<Boolean>) {
        val learn = (result as? AppResult.Ok)?.value ?: return
        withContext(io) { if (learn) queries.deleteSetting(LEARN) else queries.putSetting(LEARN, OFF) }
    }

    /** The searches and waiting picks kept on the device. */
    private suspend fun forget() =
        withContext(io) {
            queries.transaction {
                queries.deleteSetting(RECENT)
                queries.deleteSetting(PENDING)
            }
        }

    private suspend fun pendingCount(): Int = withContext(io) { readPending().size }

    private fun readList(key: String): List<String> =
        queries.setting(key).executeAsOneOrNull()?.let {
            runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
        } ?: emptyList()

    private fun writeList(
        key: String,
        values: List<String>,
    ) {
        if (values.isEmpty()) {
            queries.deleteSetting(
                key,
            )
        } else {
            queries.putSetting(key, json.encodeToString(ListSerializer(String.serializer()), values))
        }
    }

    private fun readPending(): List<PickDto> =
        queries.setting(PENDING).executeAsOneOrNull()?.let {
            runCatching { json.decodeFromString(ListSerializer(PickDto.serializer()), it) }.getOrNull()
        } ?: emptyList()

    private fun writePending(values: List<PickDto>) {
        if (values.isEmpty()) {
            queries.deleteSetting(PENDING)
        } else {
            queries.putSetting(PENDING, json.encodeToString(ListSerializer(PickDto.serializer()), values))
        }
    }

    private companion object {
        const val RECENT = "search/recent"
        const val PENDING = "search/pending"
        const val LEARN = "search/learn"
        const val OFF = "off"
        const val MAX_QUERY = 200
        const val RECENT_SHOWN = 5
    }
}

private val ReadingFormat.wire: String get() = name.lowercase()

/** A refusal that will never change (a 4xx with a code, a missing right); not the network, a rate limit or a 5xx. */
private fun AppError.isPermanent(): Boolean = this is AppError.Rejected || this is AppError.Forbidden

private fun SuggestedTitleDto.toDomain() =
    SuggestedTitle(
        sourceKey = sourceKey,
        titleRef = titleRef,
        name = name,
        contentRating = ContentRating.fromApi(contentRating),
        format = format?.let(ReadingFormat::fromApi),
        // Only our own source's cover comes from here; any other one is resolved on the device.
        coverUrl = coverUrl.takeIf { sourceKey == INTERNAL_SOURCE },
        reason = SuggestReason.fromApi(reason),
        episodeCount = episodeCount,
    )
