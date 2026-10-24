package com.vovoscan.feature.forum.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.forum.data.db.ForumDatabase
import com.vovoscan.feature.forum.domain.Anchor
import com.vovoscan.feature.forum.domain.Draft
import com.vovoscan.feature.forum.domain.Order
import com.vovoscan.feature.forum.domain.PostKind
import com.vovoscan.feature.forum.domain.Sticker
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForumRepositoryImplTest {
    private val post =
        """{"id":"p1","kind":"note","pageIndex":3,"anchor":{"x":0.5,"y":0.25},"sticker":"doki","body":"Look at her hands",""" +
            """"language":"en","author":{"id":"u1","name":"Aya"},"reactions":{"doki":4,"nope":1},"myReaction":"doki"}"""
    private val margin = """{"data":{"room":{"id":"r1"},"readersHere":12,"pins":[$post],"notes":[$post]}}"""

    @Test
    fun marginIsReadThenKeptForOfflineAndWipedOnSignOut() =
        runTest {
            var online = true
            val paths = mutableListOf<String>()
            val http =
                testHttpClient { request ->
                    if (!online) throw IllegalStateException("offline")
                    paths += "${request.url.encodedPath}?${request.url.encodedQuery}"
                    respondJson(margin)
                }
            val repo = repository(http)
            val read = repo.margin("c1", Order.Best)
            assertIs<AppResult.Ok<*>>(read)
            assertEquals(listOf("/api/v1/forum/chapters/c1/margin?order=best"), paths)
            online = false
            assertIs<AppResult.Err>(repo.margin("c1", Order.Best))
            val saved = repo.observeMargin("c1").first()
            assertEquals(12, saved?.readersHere)
            val pin = saved?.pins?.single()
            assertEquals(Anchor.Point(0.5, 0.25), pin?.anchor)
            assertEquals(Sticker.of("doki"), pin?.myReaction)
            assertEquals(4, pin?.reactions?.get(Sticker.of("doki")))
            repo.clearLocal()
            assertNull(repo.observeMargin("c1").first())
        }

    @Test
    fun aMarkedNoteIsSentWithItsAnchorAndNoEmptyFields() =
        runTest {
            val bodies = mutableListOf<String>()
            val http =
                testHttpClient { request ->
                    bodies += (request.body as TextContent).text
                    respondJson("""{"data":$post}""")
                }
            val draft =
                Draft(
                    PostKind.Note,
                    "en",
                    body = " ",
                    sticker = Sticker.of("doki"),
                    pageIndex = 3,
                    anchor = Anchor.Frame(0.1, 0.2, 0.3, 0.4),
                )
            assertIs<AppResult.Ok<*>>(repository(http).post("c1", draft))
            val sent = bodies.single()
            assertTrue("\"anchor\":{\"x\":0.1,\"y\":0.2,\"w\":0.3,\"h\":0.4}" in sent, sent)
            assertTrue("\"sticker\":\"doki\"" in sent, sent)
            assertTrue("\"body\"" !in sent && "\"spoiler\"" !in sent, sent)
        }

    @Test
    fun takingAReactionOrVoteBackIsADelete() =
        runTest {
            val calls = mutableListOf<String>()
            val http =
                testHttpClient { request ->
                    calls += "${request.method.value} ${request.url.encodedPath}"
                    respondJson("""{"data":$post}""")
                }
            val repo = repository(http)
            repo.react("p1", null)
            repo.vote("p1", null)
            repo.vote("p1", true)
            assertEquals(
                listOf(
                    "${HttpMethod.Delete.value} /api/v1/forum/posts/p1/reaction",
                    "${HttpMethod.Delete.value} /api/v1/forum/posts/p1/vote",
                    "${HttpMethod.Put.value} /api/v1/forum/posts/p1/vote",
                ),
                calls,
            )
        }

    private fun TestScope.repository(http: HttpClient) =
        ForumRepositoryImpl(
            ApiForumRemoteDataSource(http),
            ForumDatabase(inMemoryDriver(ForumDatabase.Schema)),
            StandardTestDispatcher(testScheduler),
        ) { 1_000L }
}
