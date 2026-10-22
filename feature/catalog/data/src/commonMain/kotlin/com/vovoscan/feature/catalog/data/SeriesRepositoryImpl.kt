package com.vovoscan.feature.catalog.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.MarkedTitle
import com.vovoscan.feature.catalog.domain.Series
import com.vovoscan.feature.catalog.domain.SeriesMember
import com.vovoscan.feature.catalog.domain.SeriesRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SeriesMemberDto(
    val sourceKey: String,
    val titleRef: String,
    val name: String = "",
    val chapters: Int? = null,
)

@Serializable
data class SeriesLinkDto(
    val sourceKey: String,
    val titleRef: String,
    val seriesId: String,
    val members: List<SeriesMemberDto>,
)

/** `api/v1/series/resolve`: the series of the titles asked about (100 a call; a title in no series is left out). */
fun interface SeriesRemoteDataSource {
    suspend fun resolve(titles: List<MarkedTitle>): AppResult<List<SeriesLinkDto>>

    companion object {
        const val BATCH = 100
    }
}

class ApiSeriesRemoteDataSource(
    private val http: HttpClient,
) : SeriesRemoteDataSource {
    override suspend fun resolve(titles: List<MarkedTitle>): AppResult<List<SeriesLinkDto>> =
        http
            .apiCall<ApiData<List<SeriesLinkDto>>> {
                get("api/v1/series/resolve") { titles.forEach { parameter("ref", "${it.sourceKey}:${it.id}") } }
            }.map { it.data }
}

/** Series kept in the catalog's database (`series_link`), so cards and title pages stay folded offline. */
class SeriesRepositoryImpl(
    private val remote: SeriesRemoteDataSource,
    private val database: CatalogDatabase,
    private val io: CoroutineDispatcher,
) : SeriesRepository {
    private val queries get() = database.catalogQueries
    private val members = ListSerializer(SeriesMemberDto.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    override fun observe(): Flow<Map<String, Series>> =
        queries
            .allSeries()
            .asFlow()
            .mapToList(io)
            .map { rows ->
                rows
                    .mapNotNull { row ->
                        val list = runCatching { json.decodeFromString(members, row.members) }.getOrNull() ?: return@mapNotNull null
                        "${row.source_key}/${row.title_ref}" to
                            Series(row.series_id, list.map { SeriesMember(it.sourceKey, it.titleRef, it.name, it.chapters) })
                    }.toMap()
            }

    override suspend fun resolve(titles: List<MarkedTitle>): AppResult<Unit> {
        for (batch in titles.distinct().chunked(SeriesRemoteDataSource.BATCH)) {
            when (val result = remote.resolve(batch)) {
                is AppResult.Err -> return result
                is AppResult.Ok ->
                    withContext(io) {
                        queries.transaction {
                            // Asked and not answered: in no series (anymore).
                            batch.forEach { queries.forgetSeries(it.sourceKey, it.id) }
                            result.value.forEach {
                                queries.putSeries(it.sourceKey, it.titleRef, it.seriesId, json.encodeToString(members, it.members))
                            }
                        }
                    }
            }
        }
        return AppResult.Ok(Unit)
    }
}
