package com.vovoscan.feature.catalog.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.isoUtc
import com.vovoscan.core.common.map
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.MarkedTitle
import com.vovoscan.feature.catalog.domain.RatingsRepository
import com.vovoscan.feature.catalog.domain.Stars
import com.vovoscan.feature.catalog.domain.TitleStats
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class RatingDto(
    val sourceKey: String,
    val titleRef: String,
    val titleName: String = "",
    val score: Double? = null,
    val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class TitleStatsDto(
    val sourceKey: String,
    val titleRef: String,
    val ratingAvg: Double? = null,
    val ratingCount: Int? = null,
    val favoriteCount: Int? = null,
)

@Serializable
internal data class RateBody(
    val score: Double,
    val updatedAt: String,
    val titleName: String,
)

@Serializable
internal data class StatsTitle(
    val sourceKey: String,
    val titleRef: String,
)

@Serializable
internal data class StatsBody(
    val titles: List<StatsTitle>,
)

/** `api/v1/ratings`: the account's ratings (idempotent writes, the server answers the winning state) and everyone's numbers. */
interface RatingsRemoteDataSource {
    suspend fun put(
        sourceKey: String,
        titleRef: String,
        score: Double,
        updatedAt: String,
        titleName: String,
    ): AppResult<RatingDto>

    suspend fun delete(
        sourceKey: String,
        titleRef: String,
        updatedAt: String,
    ): AppResult<RatingDto>

    /** With [updatedSince]: changes since then, tombstones included. */
    suspend fun ratings(updatedSince: String?): AppResult<List<RatingDto>>

    /** At most [STATS_BATCH] titles. */
    suspend fun stats(titles: List<MarkedTitle>): AppResult<List<TitleStatsDto>>

    companion object {
        const val STATS_BATCH = 100
    }
}

class ApiRatingsRemoteDataSource(
    private val http: HttpClient,
) : RatingsRemoteDataSource {
    private fun path(
        sourceKey: String,
        titleRef: String,
    ) = "api/v1/ratings/${sourceKey.encodeURLPathPart()}/${titleRef.encodeURLPathPart()}"

    override suspend fun put(
        sourceKey: String,
        titleRef: String,
        score: Double,
        updatedAt: String,
        titleName: String,
    ): AppResult<RatingDto> =
        http
            .apiCall<ApiData<RatingDto>> {
                put(path(sourceKey, titleRef)) {
                    contentType(ContentType.Application.Json)
                    setBody(RateBody(score, updatedAt, titleName))
                }
            }.map { it.data }

    override suspend fun delete(
        sourceKey: String,
        titleRef: String,
        updatedAt: String,
    ): AppResult<RatingDto> =
        http
            .apiCall<ApiData<RatingDto>> {
                delete(path(sourceKey, titleRef)) { parameter("updatedAt", updatedAt) }
            }.map { it.data }

    override suspend fun ratings(updatedSince: String?): AppResult<List<RatingDto>> =
        http
            .apiCall<ApiData<List<RatingDto>>> {
                get("api/v1/ratings") { updatedSince?.let { parameter("updatedSince", it) } }
            }.map { it.data }

    override suspend fun stats(titles: List<MarkedTitle>): AppResult<List<TitleStatsDto>> =
        http
            .apiCall<ApiData<List<TitleStatsDto>>> {
                post("api/v1/ratings/stats") {
                    contentType(ContentType.Application.Json)
                    setBody(StatsBody(titles.map { StatsTitle(it.sourceKey, it.id) }))
                }
            }.map { it.data }
}

/**
 * Ratings offline first (the `my_rating` row is its own queue, one change per title, the latest wins) and everyone's
 * numbers kept in `title_stats` for offline display. One sync at a time; a rating is sent with the time it was given.
 */
class RatingsRepositoryImpl(
    private val remote: RatingsRemoteDataSource,
    private val database: CatalogDatabase,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
) : RatingsRepository {
    private val queries get() = database.catalogQueries
    private val syncing = Mutex()

    /** Ratings the server refused for good during a sync, by title and time, for the [rate] that queued them. */
    private val refused = mutableMapOf<Triple<String, String, Long>, AppError>()

    override fun observeRatings(): Flow<Map<String, Double>> =
        queries
            .ratings()
            .asFlow()
            .mapToList(io)
            .map { rows -> rows.mapNotNull { r -> r.score?.let { "${r.source_key}/${r.title_ref}" to it } }.toMap() }

    override fun observeStats(): Flow<Map<String, TitleStats>> =
        queries
            .allStats()
            .asFlow()
            .mapToList(io)
            .map { rows ->
                rows.associate {
                    "${it.source_key}/${it.title_id}" to TitleStats(it.rating_avg, it.rating_count?.toInt(), it.favorite_count?.toInt())
                }
            }

    override suspend fun refreshStats(titles: List<MarkedTitle>): AppResult<Unit> {
        for (batch in titles.distinct().chunked(RatingsRemoteDataSource.STATS_BATCH)) {
            when (val result = remote.stats(batch)) {
                is AppResult.Err -> return result
                is AppResult.Ok ->
                    withContext(io) {
                        queries.transaction {
                            result.value.forEach {
                                queries.putStats(
                                    it.sourceKey,
                                    it.titleRef,
                                    it.ratingAvg,
                                    it.ratingCount?.toLong(),
                                    it.favoriteCount?.toLong(),
                                )
                            }
                        }
                    }
            }
        }
        return AppResult.Ok(Unit)
    }

    override suspend fun rate(
        title: MarkedTitle,
        titleName: String,
        score: Double?,
    ): AppResult<Unit> {
        require(score == null || Stars.valid(score)) { "1 to 5 stars, half steps" }
        val now = clock.nowMillis()
        withContext(io) {
            queries.transaction {
                queries.insertRating(title.sourceKey, title.id, titleName)
                queries.rateLocal(score, titleName, now, title.sourceKey, title.id)
            }
        }
        sync()
        return syncing.withLock { refused.remove(Triple(title.sourceKey, title.id, now)) }?.let { AppResult.Err(it) }
            ?: AppResult.Ok(Unit)
    }

    override suspend fun sync(): AppResult<Unit> =
        syncing.withLock {
            for (row in withContext(io) { queries.pendingRatings().executeAsList() }) {
                val at = isoUtc(row.updated_at)
                val score = row.score
                val result =
                    if (score == null) {
                        remote.delete(row.source_key, row.title_ref, at)
                    } else {
                        remote.put(row.source_key, row.title_ref, score, at, row.title_name)
                    }
                when (result) {
                    is AppResult.Ok -> applyServer(result.value, sentAt = row.updated_at)
                    is AppResult.Err ->
                        when {
                            result.error.waits() -> return@withLock result
                            // Taking back a rating the server never had: done.
                            score == null && (result.error as? AppError.Rejected)?.code == "RATING_NOT_FOUND" ->
                                withContext(io) {
                                    queries.serverRating(
                                        null,
                                        row.title_name,
                                        row.updated_at,
                                        row.source_key,
                                        row.title_ref,
                                        row.updated_at,
                                    )
                                }
                            else -> {
                                withContext(io) { queries.revertRating(row.source_key, row.title_ref, row.updated_at) }
                                refused[Triple(row.source_key, row.title_ref, row.updated_at)] = result.error
                            }
                        }
                }
            }
            pull()
        }

    private suspend fun pull(): AppResult<Unit> {
        val since = withContext(io) { queries.setting(PULLED_AT).executeAsOneOrNull() }
        return when (val result = remote.ratings(since)) {
            is AppResult.Err -> result
            is AppResult.Ok -> {
                var newest: Long? = null
                result.value.forEach { dto ->
                    val at = parseIsoMillis(dto.updatedAt) ?: return@forEach
                    applyServer(dto, sentAt = null)
                    if (newest == null || at > newest) newest = at
                }
                newest?.let { withContext(io) { queries.putSetting(PULLED_AT, isoUtc(it)) } }
                AppResult.Ok(Unit)
            }
        }
    }

    /** The server's state; a newer change still waiting here wins. [sentAt]: the time of the change it answers. */
    private suspend fun applyServer(
        dto: RatingDto,
        sentAt: Long?,
    ) = withContext(io) {
        val at = parseIsoMillis(dto.updatedAt) ?: sentAt ?: return@withContext
        val score = dto.score.takeIf { !dto.deleted }
        queries.transaction {
            queries.insertRating(dto.sourceKey, dto.titleRef, dto.titleName)
            val row = queries.ratingOf(dto.sourceKey, dto.titleRef).executeAsOne()
            val name = dto.titleName.ifEmpty { row.title_name }
            // The answer to the change sent clears it, whatever time the server kept; a pull only wins over older changes.
            queries.serverRating(score, name, at, dto.sourceKey, dto.titleRef, maxOf(at, sentAt ?: at))
        }
    }

    override suspend fun clearLocal() =
        withContext(io) {
            queries.transaction {
                queries.clearRatings()
                queries.clearStats()
                queries.deleteSetting(PULLED_AT)
            }
        }

    private fun AppError.waits() = this == AppError.Network || this == AppError.Unauthorized || this == AppError.RateLimited

    private companion object {
        const val PULLED_AT = "ratings/pulledAt"
    }
}
