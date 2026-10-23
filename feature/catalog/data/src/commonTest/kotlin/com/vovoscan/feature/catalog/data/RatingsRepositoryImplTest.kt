package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.MarkedTitle
import com.vovoscan.feature.catalog.domain.TitleStats
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingsRepositoryImplTest {
    private val title = MarkedTitle("inkwell", "t-kingdom")
    private var online = false
    private var refuse = false
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()
    private var pulled = """{"data":[]}"""

    private fun TestScope.repository(): RatingsRepositoryImpl {
        val http =
            testHttpClient { request ->
                if (!online) throw IllegalStateException("offline")
                requests += request
                bodies += request.body.toByteArray().decodeToString()
                val path = request.url.encodedPath
                when {
                    path == "/api/v1/ratings/stats" ->
                        respondJson(
                            """{"data":[{"sourceKey":"inkwell","titleRef":"t-kingdom","rating":4.5,"favorite":true,""" +
                                """"ratingAvg":4.33,"ratingCount":12,"favoriteCount":30},""" +
                                """{"sourceKey":"inkwell","titleRef":"t-quiet","ratingAvg":null,"ratingCount":null,"favoriteCount":null}]}""",
                        )
                    request.method == HttpMethod.Get -> respondJson(pulled)
                    refuse ->
                        respondJson(
                            """{"code":404,"message":"Title not found.","data":{"error":"TITLE_NOT_FOUND"}}""",
                            HttpStatusCode.NotFound,
                        )
                    request.method == HttpMethod.Put ->
                        respondJson(
                            """{"data":{"sourceKey":"inkwell","titleRef":"t-kingdom","titleName":"Kingdom","score":4.5,""" +
                                """"updatedAt":"1970-01-01T00:00:01Z","deleted":false}}""",
                        )
                    else ->
                        respondJson(
                            """{"data":{"sourceKey":"inkwell","titleRef":"t-kingdom","titleName":"Kingdom","score":null,""" +
                                """"updatedAt":"1970-01-01T00:00:01Z","deleted":true}}""",
                        )
                }
            }
        return RatingsRepositoryImpl(
            ApiRatingsRemoteDataSource(http),
            CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema)),
            StandardTestDispatcher(testScheduler),
        ) { 1_000L }
    }

    @Test
    fun aRatingGivenOfflineShowsAtOnceAndGoesOutWithItsTime() =
        runTest {
            val repo = repository()
            assertEquals(AppResult.Ok(Unit), repo.rate(title, "Kingdom", 4.5))
            assertEquals(mapOf("inkwell/t-kingdom" to 4.5), repo.observeRatings().first())
            assertTrue(requests.isEmpty())

            online = true
            assertEquals(AppResult.Ok(Unit), repo.sync())
            val put = requests.first { it.method == HttpMethod.Put }
            assertEquals("/api/v1/ratings/inkwell/t-kingdom", put.url.encodedPath)
            assertEquals(
                """{"score":4.5,"updatedAt":"1970-01-01T00:00:01Z","titleName":"Kingdom"}""",
                bodies[requests.indexOf(put)],
            )
            // Sent once: the next sync only pulls.
            requests.clear()
            repo.sync()
            assertTrue(requests.none { it.method == HttpMethod.Put })

            // Taken back.
            assertEquals(AppResult.Ok(Unit), repo.rate(title, "Kingdom", null))
            assertEquals(HttpMethod.Delete, requests.first { it.method != HttpMethod.Get }.method)
            assertEquals(emptyMap(), repo.observeRatings().first())
        }

    @Test
    fun aRefusedRatingIsPutBack() =
        runTest {
            val repo = repository()
            online = true
            repo.rate(title, "Kingdom", 4.5)
            refuse = true
            val result = repo.rate(title, "Kingdom", 2.0)
            assertIs<AppResult.Err>(result)
            assertEquals(mapOf("inkwell/t-kingdom" to 4.5), repo.observeRatings().first())
        }

    @Test
    fun ratingsFromOtherDevicesArePulledAndEveryonesNumbersKept() =
        runTest {
            val repo = repository()
            online = true
            pulled =
                """{"data":[{"sourceKey":"vovoscan","titleRef":"t1","titleName":"Ink Road","score":3.5,""" +
                """"updatedAt":"2026-10-22T21:00:00Z","deleted":false}]}"""
            repo.sync()
            assertEquals(mapOf("vovoscan/t1" to 3.5), repo.observeRatings().first())
            // The next pull asks only for what changed since.
            repo.sync()
            assertEquals("2026-10-22T21:00:00Z", requests.last().url.parameters["updatedSince"])

            repo.refreshStats(listOf(title, MarkedTitle("inkwell", "t-quiet")))
            val asked = Json.parseToJsonElement(bodies[requests.indexOfFirst { it.url.encodedPath == "/api/v1/ratings/stats" }])
            assertEquals(2, asked.jsonObject["titles"]!!.jsonArray.size)
            val stats = repo.observeStats().first()
            assertEquals(TitleStats(4.33, 12, 30), stats["inkwell/t-kingdom"])
            // Fewer than 3 accounts: nothing to show.
            assertNull(stats["inkwell/t-quiet"]?.ratingAvg)
        }
}
