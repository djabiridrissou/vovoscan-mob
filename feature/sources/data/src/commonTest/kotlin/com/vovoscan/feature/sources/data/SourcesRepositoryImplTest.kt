package com.vovoscan.feature.sources.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.sources.data.db.SourcesDatabase
import com.vovoscan.feature.sources.domain.SourceEngine
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcesRepositoryImplTest {
    private val mine =
        """{"data":[{"key":"vovoscan","name":"vovoscan","engine":"internal",""" +
            """"contentKinds":["comic","book"],"enabled":true,"position":1000},""" +
            """{"key":"gutenberg","name":"Project Gutenberg","engine":"rules","contentKinds":["book"],"enabled":false,"position":2001,""" +
            """"manifest":{"contractVersion":1,"key":"gutenberg","name":"Project Gutenberg","kinds":["book"],"hosts":[],""" +
            """"settings":{"catalogBaseUrl":{"label":"x","type":"url"}}},"settings":{"catalogBaseUrl":"https://gutendex.example"}}]}"""

    @Test
    fun manifestsStayOnTheDevice() =
        runTest {
            var online = true
            val local =
                SqlSourcesLocalDataSource(SourcesDatabase(inMemoryDriver(SourcesDatabase.Schema)), StandardTestDispatcher(testScheduler))
            val repo =
                SourcesRepositoryImpl(
                    KtorSourcesRemoteDataSource(testHttpClient { if (online) respondJson(mine) else error("offline") }),
                    local,
                )
            repo.refresh()
            online = false
            val sources = repo.observe().first()
            assertEquals(
                listOf("vovoscan" to SourceEngine.Internal, "gutenberg" to SourceEngine.OnDevice),
                sources.map {
                    it.key to
                        it.engine
                },
            )
            val manifest = assertNotNull(local.manifest("gutenberg"))
            assertEquals(setOf("gutendex.example"), manifest.allowedHosts)
            assertNull(local.manifest("vovoscan"))
        }

    @Test
    fun offlineChoiceIsSentFirst() =
        runTest {
            var online = false
            val sentBodies = mutableListOf<String>()
            val local =
                SqlSourcesLocalDataSource(SourcesDatabase(inMemoryDriver(SourcesDatabase.Schema)), StandardTestDispatcher(testScheduler))
            local.replace(emptyList())
            val http =
                testHttpClient { request ->
                    if (!online) error("offline")
                    if (request.method == HttpMethod.Put) sentBodies += request.body.toString()
                    respondJson(mine)
                }
            val repo = SourcesRepositoryImpl(KtorSourcesRemoteDataSource(http), local)
            val saved = repo.save(listOf("gutenberg" to true, "vovoscan" to true))
            assertTrue(saved is AppResult.Err)
            assertNotNull(local.pending())
            online = true
            assertEquals(AppResult.Ok(Unit), repo.refresh())
            assertEquals(1, sentBodies.size)
            assertNull(local.pending())
        }
}
