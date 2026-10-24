package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.ContentRating
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SearchPick
import com.vovoscan.feature.catalog.domain.SearchRules
import com.vovoscan.feature.catalog.domain.SuggestReason
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchRepositoryImplTest {
    private val suggestions =
        """{"data":{"recent":["Kings R"],"queries":["kingdom"],"titles":[""" +
            """{"sourceKey":"inkwell","titleRef":"t-kingdom","name":"Kingdom","contentRating":"safe","lang":"en",""" +
            """"format":"webtoon","coverUrl":"https://elsewhere.example/c.png","reason":"popular"},""" +
            """{"sourceKey":"vovoscan","titleRef":"t1","name":"Ink Road","format":null,""" +
            """"coverUrl":"https://api.test/media/covers/ab.webp","reason":"yours"}]}}"""

    /** The server is reachable; otherwise every request fails like a lost connection. */
    private var online = true
    private var pickStatus = HttpStatusCode.OK
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()
    private var now = 1_000_000_000L

    private fun TestScope.repository(): Pair<SearchRepositoryImpl, CatalogDatabase> {
        val database = CatalogDatabase(inMemoryDriver(CatalogDatabase.Schema))
        val http =
            testHttpClient { request ->
                if (!online) throw IllegalStateException("offline")
                requests += request
                if (request.method == HttpMethod.Post) bodies += request.body.toByteArray().decodeToString()
                when (request.url.encodedPath) {
                    "/api/v1/search/suggest" -> respondJson(suggestions)
                    "/api/v1/search/picks" ->
                        if (pickStatus == HttpStatusCode.OK) {
                            respondJson("""{"data":{"recorded":true}}""")
                        } else {
                            respondJson("""{"code":422,"message":"No.","data":{"error":"UNKNOWN_SOURCE"}}""", pickStatus)
                        }
                    "/api/v1/search/preferences" -> respondJson("""{"data":{"learn":false}}""")
                    "/api/v1/search/history" -> respondJson("""{"data":{"removed":4}}""")
                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        val repo =
            SearchRepositoryImpl(
                ApiSearchRemoteDataSource(http),
                database,
                StandardTestDispatcher(testScheduler),
                clock = { now },
                language = { "fr" },
            )
        return repo to database
    }

    @Test
    fun suggestionsCarryTheFormatFilterAndOnlyOurOwnCovers() =
        runTest {
            val (repo, _) = repository()
            val found = repo.suggest(" king ", 8, ReadingFormat.Webtoon)
            val url = requests.single().url
            assertEquals("king", url.parameters["q"])
            assertEquals("fr", url.parameters["lang"])
            assertEquals("8", url.parameters["limit"])
            assertEquals("webtoon", url.parameters["format"])
            assertEquals(listOf("Kings R"), found.recent)
            assertEquals(listOf("kingdom"), found.queries)
            val (kingdom, road) = found.titles
            assertEquals(SuggestReason.Popular to ReadingFormat.Webtoon, kingdom.reason to kingdom.format)
            assertEquals(ContentRating.Safe, kingdom.contentRating)
            // A third-party source's cover never comes from the server.
            assertNull(kingdom.coverUrl)
            assertEquals("https://api.test/media/covers/ab.webp", road.coverUrl)
            assertNull(road.format)
            // No filter: no format parameter.
            repo.suggest("king", 8, null)
            assertNull(requests.last().url.parameters["format"])
        }

    @Test
    fun picksAreSentWithoutTheirTimeAndKeptWhileOffline() =
        runTest {
            val (repo, _) = repository()
            repo.recordPick(SearchPick(" Kingdom ", "inkwell", "t-kingdom", "Kingdom", ContentRating.Safe, ReadingFormat.Webtoon))
            val sent = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals("Kingdom", sent["query"]?.jsonPrimitive?.content)
            assertEquals("Kingdom", sent["titleName"]?.jsonPrimitive?.content)
            assertEquals("webtoon", sent["format"]?.jsonPrimitive?.content)
            assertEquals("safe", sent["contentRating"]?.jsonPrimitive?.content)
            assertEquals("fr", sent["lang"]?.jsonPrimitive?.content)
            assertNull(sent["at"])
            // Our own titles: the server knows their name.
            repo.recordPick(SearchPick("road", "vovoscan", "t1", "Ink Road"))
            assertNull(Json.parseToJsonElement(bodies.last()).jsonObject["titleName"])

            online = false
            repo.recordPick(SearchPick("ink", "inkwell", "t-ink", "Ink"))
            repo.recordPick(SearchPick("old", "inkwell", "t-old", "Old"))
            assertEquals(2, bodies.size)
            // Offline suggestions: this device's searches.
            val offline = repo.suggest("", 8, null)
            assertTrue(offline.offline)
            assertEquals(listOf("old", "ink", "road", "Kingdom"), offline.recent.take(4))

            online = true
            now += SearchRules.PENDING_MAX_AGE_MS - 1
            repo.sendPending()
            assertEquals(
                listOf("ink", "old"),
                bodies.drop(2).map {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["query"]
                        ?.jsonPrimitive
                        ?.content
                },
            )
            repo.sendPending()
            assertEquals(4, bodies.size)
        }

    @Test
    fun oldOrRefusedPicksAreDropped() =
        runTest {
            val (repo, _) = repository()
            online = false
            repo.recordPick(SearchPick("ink", "inkwell", "t-ink", "Ink"))
            online = true
            now += SearchRules.PENDING_MAX_AGE_MS + 1
            repo.sendPending()
            assertTrue(bodies.isEmpty())

            pickStatus = HttpStatusCode.UnprocessableEntity
            repo.recordPick(SearchPick("gone", "inkwell", "t-gone", "Gone"))
            pickStatus = HttpStatusCode.OK
            repo.sendPending()
            // Refused once, never sent again.
            assertEquals(1, bodies.size)
            // Too short to teach anything: not even queued.
            repo.recordPick(SearchPick("a", "inkwell", "t-a", "A"))
            assertEquals(1, bodies.size)
        }

    @Test
    fun offlineSuggestionsAreTheSavedTitles() =
        runTest {
            val (repo, database) = repository()
            database.catalogQueries.insertSummary("inkwell", "t1", "Kingdom of Ink", null, "comic", "webtoon", "ongoing", "safe")
            database.catalogQueries.insertSummary("inkwell", "t2", "King's Road", null, "comic", "manga", "ongoing", null)
            online = false
            val found = repo.suggest("king", 8, ReadingFormat.Webtoon)
            assertTrue(found.offline)
            assertEquals(listOf("Kingdom of Ink"), found.titles.map { it.name })
            assertNull(found.titles.single().reason)
        }

    @Test
    fun turningLearningOffKeepsNothingHere() =
        runTest {
            val (repo, _) = repository()
            online = false
            repo.recordPick(SearchPick("ink", "inkwell", "t-ink", "Ink"))
            online = true
            assertEquals(AppResult.Ok(false), repo.setLearning(false))
            repo.sendPending()
            repo.recordPick(SearchPick("kingdom", "inkwell", "t-kingdom", "Kingdom"))
            assertTrue(bodies.isEmpty())
            online = false
            assertTrue(repo.suggest("", 8, null).recent.isEmpty())
            online = true
            assertEquals(AppResult.Ok(4), repo.clearHistory())
        }
}
