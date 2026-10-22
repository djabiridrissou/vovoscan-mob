package com.vovoscan.feature.library.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.library.data.db.LibraryDatabase
import com.vovoscan.feature.library.domain.TitleRef
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryRepositoryImplTest {
    private val title = TitleRef("gutenberg", "1342", "Pride and Prejudice", null)

    @Test
    fun offlineActionStaysQueuedAndShowsAtOnce() =
        runTest {
            val local =
                SqlLibraryLocalDataSource(LibraryDatabase(inMemoryDriver(LibraryDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val repo =
                LibraryRepositoryImpl(
                    KtorLibraryRemoteDataSource(testHttpClient { throw IllegalStateException("offline") }),
                    local,
                ) { 1_000L }
            repo.follow(title)
            assertTrue(repo.isFollowed("gutenberg", "1342").first())
            assertTrue(
                repo
                    .observe()
                    .first()
                    .single()
                    .pendingSync,
            )
            assertEquals(1, local.pending().size)
        }

    @Test
    fun onReconnectQueueIsSentWithTheActionTime() =
        runTest {
            var online = false
            val sent = mutableListOf<String>()
            val local =
                SqlLibraryLocalDataSource(LibraryDatabase(inMemoryDriver(LibraryDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val http =
                testHttpClient { request ->
                    if (!online) throw IllegalStateException("offline")
                    if (request.method == HttpMethod.Put) {
                        sent += request.url.encodedPath
                        respondJson(
                            """{"data":{"sourceKey":"gutenberg","titleRef":"1342","titleName":"Pride and Prejudice",""" +
                                """"lastPage":0,"updatedAt":"1970-01-01T00:00:01Z"}}""",
                        )
                    } else {
                        respondJson("""{"data":[]}""")
                    }
                }
            val repo = LibraryRepositoryImpl(KtorLibraryRemoteDataSource(http), local) { 1_000L }
            repo.follow(title)
            online = true
            assertEquals(AppResult.Ok(Unit), repo.sync())
            assertEquals(listOf("/api/v1/library/gutenberg/1342"), sent)
            assertEquals(0, local.pending().size)
            assertEquals(
                false,
                repo
                    .observe()
                    .first()
                    .single()
                    .pendingSync,
            )
        }

    @Test
    fun newerServerChangeWins() =
        runTest {
            val local =
                SqlLibraryLocalDataSource(LibraryDatabase(inMemoryDriver(LibraryDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val http =
                testHttpClient {
                    respondJson(
                        """{"data":[{"sourceKey":"vovoscan","titleRef":"t2","titleName":"Other",""" +
                            """"lastPage":7,"updatedAt":"2026-10-20T10:00:00Z"}]}""",
                    )
                }
            val repo = LibraryRepositoryImpl(KtorLibraryRemoteDataSource(http), local) { 1_000L }
            repo.sync()
            assertEquals(
                7,
                repo
                    .observe()
                    .first()
                    .single()
                    .lastPage,
            )
            assertEquals("2026-10-20T10:00:00Z", local.lastPulledAt())
        }

    private fun entryJson(
        favorite: Boolean,
        at: String = "1970-01-01T00:00:01Z",
        newChapters: Int? = null,
    ) = """{"data":{"sourceKey":"gutenberg","titleRef":"1342","titleName":"Pride and Prejudice","lastPage":0,""" +
        """"updatedAt":"$at","favorite":$favorite,"favoriteUpdatedAt":"$at"""" +
        (if (favorite) ""","favoritedAt":"$at"""" else "") +
        (newChapters?.let { ""","chapterCount":9,"newChapters":$it""" } ?: "") + "}}"

    @Test
    fun aHeartGivenOfflineFollowsAtOnceAndGoesOutOnTheFavouritesPath() =
        runTest {
            var online = false
            val sent = mutableListOf<HttpRequestData>()
            val local =
                SqlLibraryLocalDataSource(LibraryDatabase(inMemoryDriver(LibraryDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val http =
                testHttpClient { request ->
                    if (!online) throw IllegalStateException("offline")
                    if (request.method == HttpMethod.Get) {
                        respondJson("""{"data":[]}""")
                    } else {
                        sent += request
                        respondJson(entryJson(favorite = request.method == HttpMethod.Put, newChapters = 2))
                    }
                }
            val repo = LibraryRepositoryImpl(KtorLibraryRemoteDataSource(http), local) { 1_000L }
            assertEquals(AppResult.Ok(Unit), repo.setFavorite(title, true))
            assertTrue(repo.isFavorite("gutenberg", "1342").first())
            assertTrue(repo.isFollowed("gutenberg", "1342").first())
            assertEquals(1, local.pendingFavorites().size)

            online = true
            assertEquals(AppResult.Ok(Unit), repo.sync())
            // `favorites/…`, never `library/favorites/…`.
            assertEquals(listOf("/api/v1/favorites/gutenberg/1342"), sent.map { it.url.encodedPath })
            assertEquals(0, local.pendingFavorites().size)
            val entry = repo.observe().first().single()
            assertTrue(entry.favorite && !entry.pendingSync)
            assertEquals(2, entry.newChapters)

            // Taken back: still followed.
            assertEquals(AppResult.Ok(Unit), repo.setFavorite(title, false))
            assertEquals(HttpMethod.Delete, sent.last().method)
            assertEquals("1970-01-01T00:00:01Z", sent.last().url.parameters["updatedAt"])
            assertFalse(repo.isFavorite("gutenberg", "1342").first())
            assertTrue(repo.isFollowed("gutenberg", "1342").first())
        }

    @Test
    fun aProgressWriteNeverUndoesAHeartAndARefusedHeartIsPutBack() =
        runTest {
            var refuse = false
            val local =
                SqlLibraryLocalDataSource(LibraryDatabase(inMemoryDriver(LibraryDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val http =
                testHttpClient { request ->
                    when {
                        request.method == HttpMethod.Get -> respondJson("""{"data":[]}""")
                        refuse ->
                            respondJson(
                                """{"code":404,"message":"Title not found.","data":{"error":"TITLE_NOT_FOUND"}}""",
                                HttpStatusCode.NotFound,
                            )
                        request.url.encodedPath.startsWith("/api/v1/favorites/") -> respondJson(entryJson(favorite = true))
                        // The library's answer carries the heart as the server has it.
                        else -> respondJson(entryJson(favorite = true, at = "1970-01-01T00:00:02Z"))
                    }
                }
            val repo = LibraryRepositoryImpl(KtorLibraryRemoteDataSource(http), local) { 1_000L }
            repo.setFavorite(title, true)
            repo.saveProgress("gutenberg", "1342", "c1", 3)
            val entry = repo.observe().first().single()
            assertTrue(entry.favorite)
            assertEquals("c1", entry.lastChapterRef)

            refuse = true
            val result = repo.setFavorite(title, false)
            assertIs<AppResult.Err>(result)
            assertTrue(repo.isFavorite("gutenberg", "1342").first())
            assertEquals(0, local.pendingFavorites().size)
        }
}
