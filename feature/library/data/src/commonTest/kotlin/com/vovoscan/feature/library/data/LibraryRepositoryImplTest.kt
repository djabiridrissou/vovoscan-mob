package com.vovoscan.feature.library.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.library.data.db.LibraryDatabase
import com.vovoscan.feature.library.domain.TitleRef
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
