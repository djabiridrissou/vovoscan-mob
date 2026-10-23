package com.vovoscan.feature.together.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.together.domain.ChapterKind
import com.vovoscan.feature.together.domain.Device
import com.vovoscan.feature.together.domain.PagePoint
import com.vovoscan.feature.together.domain.Place
import com.vovoscan.feature.together.domain.Room
import com.vovoscan.feature.together.domain.SignalKind
import com.vovoscan.feature.together.domain.Sticker
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TogetherRepositoryImplTest {
    @Test
    fun readsARoomAsTheServerSendsIt() =
        runTest {
            val repo = TogetherRepositoryImpl(testHttpClient { respondJson("""{"data":${room(seq = 4)}}""") })
            val room = assertIs<AppResult.Ok<Room>>(repo.room("r1")).value
            assertEquals("VK3Q-9TZM", room.prettyCode)
            assertEquals(ChapterKind.Comic, room.chapter.kind)
            assertEquals(Place.Comic(3, 0.42, 7), room.place)
            assertEquals(4L, room.placeSeq)
            assertEquals(listOf("u1", "u2"), room.members.map { it.userId })
            assertTrue(room.members[0].host)
            assertEquals(Device.Ios, room.members[1].device)
            assertNull(room.members[1].place)
            assertEquals(SignalKind.Sticker, room.signals.single().kind)
            assertEquals(Sticker.Doki, room.signals.single().sticker)
            assertEquals(PagePoint(2, 0.5, 0.25), room.signals.single().at)
            assertTrue(room.following)
        }

    @Test
    fun sendsOnlyWhatChanges() =
        runTest {
            val bodies = mutableListOf<String>()
            val repo =
                TogetherRepositoryImpl(
                    testHttpClient { request ->
                        bodies += request.body.toByteArray().decodeToString()
                        assertEquals(HttpMethod.Put, request.method)
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            repo.me("r1", place = Place.Comic(2, 0.5))
            repo.me("r1", following = false)
            repo.me("r1")
            repo.me("r1", place = Place.Book(4, 12, 300))
            assertEquals(
                listOf(
                    """{"position":{"kind":"comic","page":2,"fraction":0.5}}""",
                    """{"following":false}""",
                    "{}",
                    """{"position":{"kind":"book","section":4,"block":12,"offset":300}}""",
                ),
                bodies,
            )
        }

    @Test
    fun liveReconnectsWhenTheServerEndsTheStreamAndStopsWhenTheRoomCloses() =
        runTest {
            var connections = 0
            val repo =
                TogetherRepositoryImpl(
                    testHttpClient { request ->
                        assertTrue(request.events)
                        connections += 1
                        if (connections == 1) stream(sse(room(seq = 1), ": ping", "not json")) else stream(sse(room(seq = 2, open = false)))
                    },
                )
            val seen = mutableListOf<Long>()
            val result =
                repo.live("r1") {
                    seen += it.placeSeq
                    true
                }
            assertIs<AppResult.Ok<Unit>>(result)
            assertEquals(listOf(1L, 2L), seen)
            assertEquals(2, connections)
        }

    @Test
    fun liveGivesUpWhenTheReaderIsNoLongerInTheRoom() =
        runTest {
            val repo = TogetherRepositoryImpl(testHttpClient { err(HttpStatusCode.NotFound, "ROOM_NOT_FOUND") })
            val result = assertIs<AppResult.Err>(repo.live("r1") { true })
            assertEquals(AppError.Rejected("ROOM_NOT_FOUND", "ROOM_NOT_FOUND"), result.error)
        }

    @Test
    fun livePollsAfterThreeFailedConnections() =
        runTest {
            var streams = 0
            var gets = 0
            val repo =
                TogetherRepositoryImpl(
                    testHttpClient { request ->
                        if (request.events) {
                            streams += 1
                            err(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE")
                        } else {
                            gets += 1
                            respondJson("""{"data":${room(seq = gets.toLong(), open = gets < 2)}}""")
                        }
                    },
                )
            repo.live("r1") { true }
            assertEquals(3, streams)
            assertEquals(2, gets)
        }

    @Test
    fun signalsCarryTheirPoint() =
        runTest {
            var body = ""
            val repo =
                TogetherRepositoryImpl(
                    testHttpClient { request ->
                        body = request.body.toByteArray().decodeToString()
                        respondJson("""{"data":${signal()}}""", HttpStatusCode.Created)
                    },
                )
            val sent = repo.signal("r1", SignalKind.Sticker, Sticker.Doki, at = PagePoint(2, 0.5, 0.25))
            assertEquals("s1", assertIs<AppResult.Ok<*>>(sent).let { (it.value as com.vovoscan.feature.together.domain.Signal).id })
            assertEquals("""{"kind":"sticker","sticker":"doki","at":{"page":2,"x":0.5,"y":0.25}}""", body)
        }

    private val HttpRequestData.events get() = url.encodedPath.endsWith("/events")

    private fun sse(vararg events: String) =
        events.joinToString("") { if (it.startsWith(":")) "$it\n\n" else "event: snapshot\ndata: $it\n\n" }

    private fun MockRequestHandleScope.stream(body: String): HttpResponseData =
        respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))

    private fun MockRequestHandleScope.err(
        status: HttpStatusCode,
        code: String,
    ) = respondJson("""{"code":${status.value},"message":"$code","data":{"error":"$code"}}""", status)

    private fun signal() =
        """{"id":"s1","kind":"sticker","userId":"u2","name":"Ben","sticker":"doki","text":null,"at":{"page":2,"x":0.5,"y":0.25},""" +
            """"chapterId":null,"chapterNumber":null,"targetUserId":null,"createdAt":"2026-10-23T10:00:05Z"}"""

    private fun room(
        seq: Long,
        open: Boolean = true,
    ) = """{"id":"r1","code":"VK3Q9TZM","prettyCode":"VK3Q-9TZM","invitePath":"/together/VK3Q9TZM","open":$open,""" +
        """"locked":false,"capacity":8,"createdAt":"2026-10-23T09:00:00Z","closesAt":"2026-10-23T12:00:00Z",""" +
        """"chapter":{"id":"c1","number":3.0,"name":"The gate","kind":"comic","pageCount":12,"sourceKey":"vovoscan",""" +
        """"titleRef":"t1","titleName":"Ink Lanterns"},"hostId":"u1",""" +
        """"position":{"kind":"comic","page":3,"fraction":0.42,"panel":7},"positionSeq":$seq,"positionAt":null,""" +
        """"members":[{"userId":"u1","name":"Aya","slot":0,"role":"host","here":true,"following":false,""" +
        """"position":{"kind":"comic","page":3,"fraction":0.42},"positionAt":null,"language":null,"device":"web",""" +
        """"joinedAt":"2026-10-23T09:00:00Z","lastSeenAt":"2026-10-23T10:00:00Z"},""" +
        """{"userId":"u2","name":"Ben","slot":1,"role":"reader","here":true,"following":true,"position":null,""" +
        """"positionAt":null,"language":"fr","device":"ios","joinedAt":"2026-10-23T09:01:00Z",""" +
        """"lastSeenAt":"2026-10-23T10:00:00Z"}],"signals":[${signal()}],""" +
        """"me":{"userId":"u2","role":"reader","following":true},"serverTime":"2026-10-23T10:00:10Z"}"""
}
