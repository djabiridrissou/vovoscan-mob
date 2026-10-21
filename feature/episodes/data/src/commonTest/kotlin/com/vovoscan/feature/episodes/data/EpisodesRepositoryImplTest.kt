package com.vovoscan.feature.episodes.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.episodes.data.db.EpisodesDatabase
import com.vovoscan.feature.episodes.domain.EpisodeStatus
import com.vovoscan.feature.episodes.domain.PipelineStage
import com.vovoscan.feature.episodes.domain.PrepareResult
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodesRepositoryImplTest {
    private fun episode(
        status: String,
        updatedAt: String = "2026-10-21T10:00:00Z",
        progress: String = "null",
        degraded: String = "null",
        render: String = "null",
    ) = """{"chapterId":"c1","status":"$status","progress":$progress,"error":null,"pageCount":12,"degraded":$degraded,""" +
        """"silent":null,"render":$render,"outdated":false,"canReread":false,"export":null,"updatedAt":"$updatedAt"}"""

    private val ready =
        episode(
            "ready",
            "2026-10-21T10:05:00Z",
            """{"stage":"READY"}""",
            "true",
            """{"id":"r1","renderKey":"rk","analyzer":"magiv2","ocr":"none","voice":"elevenlabs:eleven_v3","degraded":true,""" +
                """"silent":false,"createdAt":"2026-10-21T10:05:00Z"}""",
        )

    private fun sse(vararg events: String) =
        events.joinToString("") { if (it.startsWith(":")) "$it\n\n" else "event: snapshot\ndata: $it\n\n" }

    private fun MockRequestHandleScope.stream(body: String): HttpResponseData =
        respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))

    private fun MockRequestHandleScope.err(
        status: HttpStatusCode,
        code: String,
    ) = respondJson("""{"code":${status.value},"message":"$code","data":{"error":"$code"}}""", status)

    private fun TestScope.repository(
        now: Long = parseIsoMillis("2026-10-21T10:00:00Z")!!,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): EpisodesRepositoryImpl {
        val local =
            SqlEpisodesLocalDataSource(EpisodesDatabase(inMemoryDriver(EpisodesDatabase.Schema)), StandardTestDispatcher(testScheduler))
        return EpisodesRepositoryImpl(KtorEpisodesRemoteDataSource(testHttpClient(handler = handler)), local) { now }
    }

    private val HttpRequestData.events get() = url.encodedPath.endsWith("/events")

    @Test
    fun sseParserReadsEventsAndSkipsHeartbeats() {
        val parser = SseParser()
        val lines = listOf(": heartbeat", "", "event: snapshot", "data: {\"a\":1,", "data: \"b\":2}", "id: 7", "", "data:plain", "")
        val events = lines.mapNotNull { parser.line(it) }
        assertEquals(listOf(SseEvent("snapshot", "{\"a\":1,\n\"b\":2}"), SseEvent("message", "plain")), events)
    }

    @Test
    fun noEpisodeYet() =
        runTest {
            val repo = repository { err(HttpStatusCode.NotFound, "EPISODE_NOT_FOUND") }
            assertEquals(AppResult.Ok(null), repo.refresh("c1"))
            assertNull(repo.observe("c1").first())
        }

    @Test
    fun followsLiveProgressUntilReady() =
        runTest {
            var connections = 0
            val repo =
                repository { request ->
                    if (request.events) {
                        connections += 1
                        assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
                        stream(
                            sse(
                                episode("reading", "2026-10-21T10:01:00Z", """{"stage":"OCR","step":"pages","done":3,"total":12}"""),
                                ": heartbeat",
                                "not json at all",
                                ready,
                            ),
                        )
                    } else {
                        respondJson("""{"data":${episode("queued")}}""")
                    }
                }
            val result = repo.follow("c1")
            assertEquals(1, connections)
            val episode = assertIs<AppResult.Ok<*>>(result).value as com.vovoscan.feature.episodes.domain.Episode
            assertEquals(EpisodeStatus.Ready, episode.status)
            assertEquals(true, episode.degraded)
            assertEquals("none", episode.render?.ocr)
            assertEquals(EpisodeStatus.Ready, repo.observe("c1").first()?.status)
        }

    @Test
    fun reconnectsWithBackoffThenPollsWhenEventsKeepFailing() =
        runTest {
            val times = mutableListOf<Long>()
            var gets = 0
            val repo =
                repository { request ->
                    if (request.events) {
                        times += currentTime
                        error("connection reset")
                    } else {
                        gets += 1
                        respondJson("""{"data":${if (gets < 3) episode("voicing", "2026-10-21T10:0$gets:00Z") else ready}}""")
                    }
                }
            val result = repo.follow("c1")
            // Three connections (1 s, then 2 s apart), then GET every 10 s until the episode is final.
            assertEquals(listOf(0L, 1_000L, 3_000L), times)
            assertEquals(3, gets)
            assertEquals(3_000L + 2 * EpisodesRepositoryImpl.POLL_MS, currentTime)
            assertEquals(EpisodeStatus.Ready, (result as AppResult.Ok).value?.status)
        }

    @Test
    fun reconnectsAtOnceWhenTheServerEndsTheStream() =
        runTest {
            var connections = 0
            val repo =
                repository { request ->
                    if (!request.events) error("no GET expected")
                    connections += 1
                    stream(sse(if (connections == 1) episode("timing", "2026-10-21T10:02:00Z") else ready))
                }
            assertEquals(EpisodeStatus.Ready, (repo.follow("c1") as AppResult.Ok).value?.status)
            assertEquals(2, connections)
            assertEquals(0L, currentTime)
        }

    @Test
    fun tooManyStreamsGoesStraightToPolling() =
        runTest {
            var connections = 0
            val repo =
                repository { request ->
                    if (request.events) {
                        connections += 1
                        err(HttpStatusCode.TooManyRequests, "TOO_MANY_STREAMS")
                    } else {
                        respondJson("""{"data":$ready}""")
                    }
                }
            assertEquals(EpisodeStatus.Ready, (repo.follow("c1") as AppResult.Ok).value?.status)
            assertEquals(1, connections)
        }

    @Test
    fun anEpisodeThatWentAwayStopsFollowing() =
        runTest {
            val repo = repository { err(HttpStatusCode.NotFound, "EPISODE_NOT_FOUND") }
            assertEquals(AppResult.Ok(null), repo.follow("c1"))
            val book = repository { err(HttpStatusCode.UnprocessableEntity, "EPISODE_NOT_SUPPORTED") }
            assertEquals("EPISODE_NOT_SUPPORTED", ((book.follow("c1") as AppResult.Err).error as AppError.Rejected).code)
        }

    @Test
    fun prepareOutcomes() =
        runTest {
            var answer: HttpResponseData? = null
            val repo =
                repository { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    when (request.url.encodedPath) {
                        "/api/v1/episodes/chapters/c1" ->
                            respondJson(
                                """{"data":${episode("queued", progress = """{"stage":"ANALYZE","step":"queued"}""")}}""",
                                HttpStatusCode.Accepted,
                            )
                        "/api/v1/episodes/chapters/c2" -> respondJson("""{"data":${ready.replace("\"c1\"", "\"c2\"")}}""")
                        "/api/v1/episodes/chapters/c3" ->
                            respondJson(
                                """{"code":409,"message":"This episode is being prepared.","data":{"error":"EPISODE_BUSY",""" +
                                    """"episode":${episode("voicing").replace("\"c1\"", "\"c3\"")}}}""",
                                HttpStatusCode.Conflict,
                            )
                        "/api/v1/episodes/chapters/c4/reread" ->
                            respondJson(
                                """{"code":403,"message":"Only the person…","data":{"error":"NOT_REQUESTER"}}""",
                                HttpStatusCode.Forbidden,
                            )
                        else -> err(HttpStatusCode.UnprocessableEntity, "EPISODE_NOT_SUPPORTED")
                    }.also { answer = it }
                }
            val queued = assertIs<PrepareResult.Queued>((repo.prepare("c1") as AppResult.Ok).value)
            assertEquals(PipelineStage.Analyze, queued.episode.stage)
            assertEquals(EpisodeStatus.Queued, repo.observe("c1").first()?.status)
            assertIs<PrepareResult.AlreadyReady>((repo.prepare("c2") as AppResult.Ok).value)
            val busy = assertIs<PrepareResult.Busy>((repo.prepare("c3") as AppResult.Ok).value)
            assertEquals(EpisodeStatus.Voicing, busy.episode?.status)
            assertEquals(EpisodeStatus.Voicing, repo.observe("c3").first()?.status)
            assertEquals(AppResult.Err(AppError.Rejected("NOT_REQUESTER", "Only the person…")), repo.reread("c4"))
            assertEquals("EPISODE_NOT_SUPPORTED", ((repo.prepare("book") as AppResult.Err).error as AppError.Rejected).code)
            assertTrue(answer != null)
        }

    @Test
    fun anOlderSnapshotNeverReplacesANewerOne() =
        runTest {
            var body = ready
            val repo = repository { respondJson("""{"data":$body}""") }
            repo.refresh("c1")
            body = episode("reading", "2026-10-21T10:01:00Z")
            repo.refresh("c1")
            assertEquals(EpisodeStatus.Ready, repo.observe("c1").first()?.status)
        }

    private val watch =
        """{"data":{"chapterId":"c1","renderKey":"rk","timeline":{"v":1,"chapterHash":"h","renderKey":"rk",""" +
            """"pages":[{"index":0,"width":800,"height":1200}],"bubbles":[{"id":"b0001","page":0,"panelBbox":[10,10,780,500],""" +
            """"bubbleBbox":[40,40,200,90],"speaker":{"clusterId":"c0"},"text":"Hi!","display":null,"shout":false,"tone":"normal",""" +
            """"audioKey":"audio/ab/x.ogg","durationMs":1800,"leadMs":0,"tags":{"onomatopoeia":[],"ambience":"none","mood":"neutral"},""" +
            """"camera":{"from":[10,10,780,500],"to":[10,10,780,500]},"panelShape":[[10,10],[790,10],[790,510],[120,510]],""" +
            """"detailBbox":null,"keepBbox":null,"sounds":[],"pan":-0.2}],"ambienceSegments":[],"direction":"ltr","beds":[],""" +
            """"music":[]},"pageUrls":["https://api.test/media/pages/p0?sig=1"],""" +
            """"audioUrls":{"audio/ab/x.ogg":"https://api.test/a?sig=1"},""" +
            """"expiresAt":"EXPIRES"}}"""

    @Test
    fun watchIsKeptWhileItsUrlsLastAndRefetchedNearExpiry() =
        runTest {
            var fetches = 0
            var online = true
            var expires = "2026-10-22T10:00:00Z"
            val repo =
                repository { _ ->
                    if (!online) error("offline")
                    fetches += 1
                    respondJson(watch.replace("EXPIRES", expires))
                }
            val first = (repo.watch("c1") as AppResult.Ok).value
            assertEquals(com.vovoscan.feature.episodes.domain.Direction.Ltr, first.timeline.direction)
            assertEquals(
                -0.2,
                first.timeline.bubbles
                    .single()
                    .pan,
            )
            assertEquals(
                120.0,
                first.timeline.bubbles
                    .single()
                    .panelShape
                    ?.p3
                    ?.x,
            )
            assertEquals("https://api.test/a?sig=1", first.audioUrls["audio/ab/x.ogg"])
            repo.watch("c1")
            assertEquals(1, fetches)
            // Close to expiry: fetched again.
            expires = "2026-10-21T10:05:00Z"
            repo.watch("c1", forceRefresh = true)
            repo.watch("c1")
            assertEquals(3, fetches)
            // Offline: the last watch still plays.
            online = false
            assertEquals("rk", (repo.watch("c1") as AppResult.Ok).value.renderKey)
        }

    @Test
    fun watchNotReady() =
        runTest {
            val repo = repository { err(HttpStatusCode.Conflict, "EPISODE_NOT_READY") }
            assertEquals("EPISODE_NOT_READY", ((repo.watch("c1") as AppResult.Err).error as AppError.Rejected).code)
        }
}
