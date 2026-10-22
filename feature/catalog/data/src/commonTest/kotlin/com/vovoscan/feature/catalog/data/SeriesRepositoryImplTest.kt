package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.MarkedTitle
import com.vovoscan.feature.catalog.domain.Series
import com.vovoscan.feature.catalog.domain.SeriesMember
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SeriesRepositoryImplTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var online = true
    private var answer =
        """{"data":[{"sourceKey":"inkwell","titleRef":"ink-road","seriesId":"s1","members":[""" +
            """{"sourceKey":"inkwell","titleRef":"ink-road","name":"Ink Road","chapters":3},""" +
            """{"sourceKey":"vovoscan","titleRef":"t1","name":"Ink Road","chapters":null}]}]}"""

    private fun TestScope.repository(): SeriesRepositoryImpl {
        val http =
            testHttpClient { request ->
                if (!online) throw IllegalStateException("offline")
                requests += request
                respondJson(answer)
            }
        return SeriesRepositoryImpl(
            ApiSeriesRemoteDataSource(http),
            CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema)),
            StandardTestDispatcher(testScheduler),
        )
    }

    @Test
    fun seriesAreKeptByTitleAndForgottenWhenNoLongerAnswered() =
        runTest {
            val repo = repository()
            val asked = listOf(MarkedTitle("inkwell", "ink-road"), MarkedTitle("quill", "q 1"))
            assertEquals(AppResult.Ok(Unit), repo.resolve(asked))
            val request = requests.single()
            assertEquals("/api/v1/series/resolve", request.url.encodedPath)
            assertEquals(listOf("inkwell:ink-road", "quill:q 1"), request.url.parameters.getAll("ref"))
            val inkRoad =
                Series(
                    "s1",
                    listOf(SeriesMember("inkwell", "ink-road", "Ink Road", 3), SeriesMember("vovoscan", "t1", "Ink Road", null)),
                )
            assertEquals(mapOf("inkwell/ink-road" to inkRoad), repo.observe().first())

            // Offline: what was known stays.
            online = false
            assertIs<AppResult.Err>(repo.resolve(asked))
            assertEquals(mapOf("inkwell/ink-road" to inkRoad), repo.observe().first())

            // Asked again and left out: in no series anymore.
            online = true
            answer = """{"data":[]}"""
            repo.resolve(asked)
            assertEquals(emptyMap(), repo.observe().first())
        }

    @Test
    fun oneHundredTitlesACall() =
        runTest {
            val repo = repository()
            answer = """{"data":[]}"""
            repo.resolve((1..230).map { MarkedTitle("inkwell", "t$it") })
            assertEquals(
                listOf(100, 100, 30),
                requests.map {
                    it.url.parameters
                        .getAll("ref")!!
                        .size
                },
            )
        }
}
