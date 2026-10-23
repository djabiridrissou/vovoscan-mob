package com.vovoscan.feature.account.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.account.data.db.AccountDatabase
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.ReadingModel
import com.vovoscan.feature.account.domain.UnitType
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountRepositoryImplTest {
    /** An obvious fake: never a real key in tests. */
    private val secret = "fake-anthropic-key-for-tests-only-wxyz"

    private val keysJson =
        """{"data":{"enabled":true,"keys":[{"id":"k1","provider":"anthropic","hint":"abcd","label":"Home",""" +
            """"createdAt":"2026-10-20T10:00:00+00:00","lastUsedAt":null},{"id":"k2","provider":"elevenlabs","hint":"9876",""" +
            """"label":null,"createdAt":"2026-10-19T08:00:00Z","lastUsedAt":"2026-10-20T12:00:00Z"}]}}"""
    private val addedJson =
        """{"data":{"id":"k3","provider":"anthropic","hint":"wxyz","label":"Laptop","createdAt":"2026-10-21T09:00:00Z","lastUsedAt":null}}"""

    private class Setup(
        val repository: AccountRepositoryImpl,
        val driver: SqlDriver,
    )

    private fun TestScope.setup(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Setup {
        val driver = inMemoryDriver(AccountDatabase.Schema)
        val local = SqlAccountLocalDataSource(AccountDatabase(driver), StandardTestDispatcher(testScheduler))
        return Setup(
            AccountRepositoryImpl(KtorAccountRemoteDataSource(testHttpClient(token = "session", handler = handler)), local),
            driver,
        )
    }

    /** Every row of a query, its first [columns] columns as text. */
    private fun SqlDriver.strings(
        sql: String,
        columns: Int,
    ): List<List<String?>> =
        executeQuery(null, sql, { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.next().value) rows += (0 until columns).map { cursor.getString(it) }
            QueryResult.Value(rows)
        }, 0).value

    private fun SqlDriver.columns(table: String): List<String> = strings("PRAGMA table_info($table)", 2).map { it[1]!! }

    @Test
    fun keyListIsKeptForOfflineUse() =
        runTest {
            var online = true
            val s = setup { if (online) respondJson(keysJson) else error("offline") }
            assertEquals(AppResult.Ok(Unit), s.repository.refreshKeys())
            online = false
            assertEquals(AppResult.Err(AppError.Network), s.repository.refreshKeys())
            val keys = s.repository.observeKeys().first()!!
            assertTrue(keys.enabled)
            assertEquals("abcd", keys.keyFor(Provider.Anthropic)?.hint)
            assertEquals("Home", keys.keyFor(Provider.Anthropic)?.label)
            assertNull(keys.keyFor(Provider.Anthropic)?.lastUsedAtMillis)
            assertEquals(parseIsoMillis("2026-10-19T08:00:00Z"), keys.keyFor(Provider.ElevenLabs)?.createdAtMillis)
            assertEquals(parseIsoMillis("2026-10-20T12:00:00Z"), keys.keyFor(Provider.ElevenLabs)?.lastUsedAtMillis)
        }

    @Test
    fun theSecretTravelsOnlyInTheRequestBodyAndIsNeverStored() =
        runTest {
            val bodies = mutableListOf<String>()
            val s =
                setup { request ->
                    when {
                        request.method == HttpMethod.Post -> {
                            bodies += (request.body as TextContent).text
                            assertFalse(request.url.toString().contains(secret), "the secret never goes in a URL")
                            assertFalse(request.headers.entries().any { (_, v) -> v.any { it.contains(secret) } }, "nor in a header")
                            respondJson(addedJson, HttpStatusCode.Created)
                        }
                        else -> respondJson(keysJson)
                    }
                }
            s.repository.refreshKeys()
            val added = s.repository.add(Provider.Anthropic, "  $secret  ", " Laptop ")
            assertIs<AppResult.Ok<*>>(added)
            assertEquals("""{"provider":"anthropic","key":"$secret","label":"Laptop"}""", bodies.single())
            // The previous Anthropic key was revoked by the server: the new one replaces it on the device.
            val keys = s.repository.observeKeys().first()!!
            assertEquals(listOf("k3", "k2"), keys.keys.sortedBy { it.provider.ordinal }.map { it.id })
            assertEquals("wxyz", keys.keyFor(Provider.Anthropic)?.hint)
            // No column could hold a secret, and no stored value contains it.
            assertEquals(
                listOf("id", "provider", "hint", "label", "created_at", "last_used_at"),
                s.driver.columns("provider_key"),
            )
            val tables = s.driver.strings("SELECT name FROM sqlite_master WHERE type = 'table'", 1).map { it[0]!! }
            assertTrue("provider_key" in tables)
            for (table in tables) {
                val count = s.driver.columns(table).size
                for (row in s.driver.strings("SELECT * FROM $table", count)) assertFalse(row.any { it?.contains(secret) == true }, table)
            }
            assertFalse(AddProviderKeyBody("anthropic", secret).toString().contains(secret))
        }

    @Test
    fun aKeyIsNeverQueuedOffline() =
        runTest {
            val s = setup { error("offline") }
            assertEquals(AppResult.Err(AppError.Network), s.repository.add(Provider.ElevenLabs, secret, null))
            assertNull(s.repository.observeKeys().first())
        }

    @Test
    fun rejectionsKeepTheirCodes() =
        runTest {
            val s =
                setup {
                    respondJson(
                        """{"code":422,"message":"The provider refused this key.","data":{"error":"KEY_REJECTED"}}""",
                        HttpStatusCode.UnprocessableEntity,
                    )
                }
            assertEquals(
                AppResult.Err(AppError.Rejected("KEY_REJECTED", "The provider refused this key.")),
                s.repository.add(Provider.Anthropic, secret, null),
            )
        }

    @Test
    fun removingIsIdempotent() =
        runTest {
            var deleted = 0
            val s =
                setup { request ->
                    if (request.method == HttpMethod.Delete) {
                        deleted += 1
                        if (deleted == 1) {
                            respondJson("", HttpStatusCode.NoContent)
                        } else {
                            respondJson(
                                """{"code":404,"message":"This key doesn't exist.","data":{"error":"PROVIDER_KEY_NOT_FOUND"}}""",
                                HttpStatusCode.NotFound,
                            )
                        }
                    } else {
                        respondJson(keysJson)
                    }
                }
            s.repository.refreshKeys()
            assertEquals(AppResult.Ok(Unit), s.repository.remove("k1"))
            assertNull(
                s.repository
                    .observeKeys()
                    .first()!!
                    .keyFor(Provider.Anthropic),
            )
            // Already gone (here or on another device): still a success.
            assertEquals(AppResult.Ok(Unit), s.repository.remove("k2"))
            assertTrue(
                s.repository
                    .observeKeys()
                    .first()!!
                    .keys
                    .isEmpty(),
            )
        }

    @Test
    fun keysTurnedOffOnTheServer() =
        runTest {
            val s = setup { respondJson("""{"data":{"enabled":false,"keys":[]}}""") }
            s.repository.refreshKeys()
            assertFalse(
                s.repository
                    .observeKeys()
                    .first()!!
                    .enabled,
            )
        }

    @Test
    fun readingModelFollowsTheServer() =
        runTest {
            val sent = mutableListOf<String>()
            val s =
                setup { request ->
                    if (request.method == HttpMethod.Put) {
                        sent += (request.body as TextContent).text
                        respondJson("""{"data":{"ocrModel":"claude-opus-5-5"}}""")
                    } else {
                        respondJson("""{"data":{"ocrModel":"claude-sonnet-5-5"}}""")
                    }
                }
            assertNull(s.repository.observeReadingModel().first())
            s.repository.refreshReadingModel()
            assertEquals(ReadingModel.Sonnet, s.repository.observeReadingModel().first())
            assertEquals(AppResult.Ok(ReadingModel.Opus), s.repository.set(ReadingModel.Opus))
            assertEquals(listOf("""{"ocrModel":"claude-opus-5-5"}"""), sent)
            assertEquals(ReadingModel.Opus, s.repository.observeReadingModel().first())
        }

    @Test
    fun aReadingLanguageGoesWithTheCurrentModelSoAnOlderServerTakesIt() =
        runTest {
            val sent = mutableListOf<String>()
            // A server from before `targetLanguage`: `ocrModel` required, the language not answered back.
            val s =
                setup { request ->
                    if (request.method == HttpMethod.Put) {
                        val body = (request.body as TextContent).text
                        sent += body
                        if (body.contains("ocrModel")) {
                            respondJson("""{"data":{"ocrModel":"claude-haiku-5-5"}}""")
                        } else {
                            respondJson(
                                """{"code":422,"message":"Invalid.","data":{"error":"VALIDATION_ERROR"}}""",
                                HttpStatusCode.UnprocessableEntity,
                            )
                        }
                    } else {
                        respondJson("""{"data":{"ocrModel":"claude-haiku-5-5"}}""")
                    }
                }
            assertEquals(AppResult.Ok("ar"), s.repository.setTargetLanguage("ar"))
            assertEquals(listOf("""{"ocrModel":"claude-haiku-5-5","targetLanguage":"ar"}"""), sent)
            assertEquals("ar", s.repository.observeTargetLanguage().first())
            // The server answers no language: the device's choice stays.
            s.repository.refreshReadingModel()
            assertEquals("ar", s.repository.observeTargetLanguage().first())
        }

    @Test
    fun aRefusedReadingLanguageNeverBlocksAndWinsOverTheServersOlderAnswer() =
        runTest {
            var refuse = true
            val puts = mutableListOf<String>()
            val s =
                setup { request ->
                    if (request.method == HttpMethod.Put) {
                        puts += (request.body as TextContent).text
                        if (refuse) {
                            respondJson(
                                """{"code":422,"message":"Invalid.","data":{"error":"VALIDATION_ERROR"}}""",
                                HttpStatusCode.UnprocessableEntity,
                            )
                        } else {
                            respondJson("""{"data":{"ocrModel":"claude-haiku-5-5","targetLanguage":"ee"}}""")
                        }
                    } else {
                        respondJson("""{"data":{"ocrModel":"claude-haiku-5-5","targetLanguage":"fr"}}""")
                    }
                }
            // Refused: kept on the device, the tour moves on.
            assertEquals(AppResult.Ok("ee"), s.repository.setTargetLanguage("ee"))
            assertEquals("ee", s.repository.observeTargetLanguage().first())
            // Still refused at the next refresh: the server's older "fr" doesn't replace it.
            s.repository.refreshReadingModel()
            assertEquals("ee", s.repository.observeTargetLanguage().first())
            assertEquals(2, puts.size)
            // Taken at last: sent once more, then the server's answer is the truth again.
            refuse = false
            s.repository.refreshReadingModel()
            assertEquals(3, puts.size)
            s.repository.refreshReadingModel()
            assertEquals(3, puts.size)
            assertEquals("fr", s.repository.observeTargetLanguage().first())
        }

    @Test
    fun usageAsksForThePeriodAndReadsTotals() =
        runTest {
            var query = ""
            val s =
                setup { request ->
                    query = request.url.encodedQuery
                    respondJson(
                        """{"data":{"from":"2026-10-01T00:00:00Z","to":null,"totals":[{"provider":"anthropic","costUsd":1.25,""" +
                            """"units":{"tokens":182400,"requests":12}}],"entries":[{"id":"u1","provider":"anthropic",""" +
                            """"model":"claude-haiku-5-5","unitType":"tokens","units":12000,"costUsd":0.012,"chapterId":"c1",""" +
                            """"renderId":null,"createdAt":"2026-10-20T14:05:00Z"}]}}""",
                    )
                }
            val usage = (s.repository.usage(1_759_276_800_000L, null) as AppResult.Ok).value
            assertEquals("from=2025-10-01T00%3A00%3A00Z", query)
            assertEquals(1.25, usage.totals.single().costUsd)
            assertEquals(mapOf(UnitType.Tokens to 182_400.0, UnitType.Requests to 12.0), usage.totals.single().units)
            assertEquals("c1", usage.entries.single().chapterId)
        }
}
