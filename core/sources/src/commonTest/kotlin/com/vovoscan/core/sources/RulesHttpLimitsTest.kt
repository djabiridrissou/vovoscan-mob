package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a request fetches decides how long it may take and how big it may be. */
class RulesHttpLimitsTest {
    private val manifest = SourceManifest("inkwell", Json.parseToJsonElement(Fixtures.inkwell).jsonObject, emptyMap())
    private val tooLarge = AppResult.Err(AppError.Rejected("SOURCE_ERROR", "The source answered with a file too large."))

    @Test
    fun aBookFileGetsLongerAndBiggerLimitsThanAPage() =
        runTest {
            val totals = mutableListOf<Long?>()
            val engine =
                MockEngine { request ->
                    totals += request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
                    respond(ByteArray(8 * 1024 * 1024), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/epub+zip"))
                }
            val source = RulesSource(manifest, RulesHttp(createRulesHttpClient(engine)))
            // 8 MB: too big for a page of the site, fine for a book file.
            assertEquals(tooLarge, source.catalog(1))
            val book = source.download("https://comics.inkwell.example/books/1.epub")
            assertTrue(book is AppResult.Ok && book.value.size == 8 * 1024 * 1024)
            source.downloadImage("https://comics.inkwell.example/pages/1.jpg")
            assertEquals(listOf<Long?>(Transfer.Page.totalMillis, Transfer.File.totalMillis, Transfer.Image.totalMillis), totals)
        }

    @Test
    fun aFileAnnouncedTooLargeIsRefusedBeforeItIsRead() =
        runTest {
            val announced = (Transfer.File.maxBytes + 1).toString()
            val engine = MockEngine { respond("small body", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, announced)) }
            val source = RulesSource(manifest, RulesHttp(createRulesHttpClient(engine)))
            assertEquals(tooLarge, source.download("https://comics.inkwell.example/books/1.epub"))
        }
}
