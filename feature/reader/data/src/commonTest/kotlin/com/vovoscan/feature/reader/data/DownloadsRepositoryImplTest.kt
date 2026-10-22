package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.NetworkMonitor
import com.vovoscan.core.common.NetworkState
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.RulesHttp
import com.vovoscan.core.sources.SourceManifest
import com.vovoscan.core.sources.createRulesHttpClient
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.ChapterDownload
import com.vovoscan.feature.reader.domain.DownloadActivity
import com.vovoscan.feature.reader.domain.DownloadBlock
import com.vovoscan.feature.reader.domain.DownloadScheduler
import com.vovoscan.feature.reader.domain.DownloadState
import com.vovoscan.feature.reader.domain.DownloadableChapter
import com.vovoscan.feature.reader.domain.DownloadableTitle
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class DownloadsRepositoryImplTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4, 5)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9)
    private val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "vovoscan-downloads-${Random.nextLong().toULong()}"
    private val fs = FileSystem.SYSTEM

    private val chapter =
        """{"data":{"id":"c2","titleId":"t1","titleName":"Le Griot de Guerre","kind":"comic","number":2,"format":"webtoon",""" +
            """"previousChapterId":"c1","nextChapterId":null,"pages":[""" +
            """{"index":0,"url":"https://api.test/media/pages/p0?expires=1&signature=a","width":800,"height":1200},""" +
            """{"index":1,"url":"https://api.test/media/pages/p1?expires=1&signature=b","width":800,"height":1200}]}}"""

    private val inkwell =
        """{"contractVersion":1,"key":"inkwell","name":"Inkwell","version":"1.0.0","kinds":["comic"],"langs":["en"],""" +
            """"rights":{"basis":"licensed","notes":"Test fixture."},"hosts":["comics.inkwell.example"],""" +
            """"catalog":{"request":{"url":"https://comics.inkwell.example/browse","type":"html"},"list":{"css":"div.card"},""" +
            """"item":{"id":{"css":"a","attr":"href"},"name":{"css":"h3"}}},""" +
            """"title":{"request":{"url":"https://comics.inkwell.example/series/{{id}}","type":"html"},"fields":{"name":{"css":"h1"}},""" +
            """"units":{"list":{"css":"ul.chapters li a"},""" +
            """"item":{"id":{"css":":self","attr":"href","then":[{"regex":"/read/([0-9]+)"}]},""" +
            """"number":{"css":":self","then":[{"regex":"Chapter ([0-9.]+)"},"number"]}}}},""" +
            """"content":{"comic":{"request":{"url":"https://comics.inkwell.example/read/{{unitId}}","type":"html"},""" +
            """"list":{"css":"div.reader img"},"item":{"url":{"css":":self","attr":"data-src","then":["absUrl"]}}}}}"""

    private val book =
        """{"data":{"id":"b1","titleId":"t9","titleName":"Letters","kind":"book","number":1,"name":"Full text","format":"text",""" +
            """"pages":[],"document":{"url":"https://api.test/media/documents/d1?sig=x","mediaType":"application/epub+zip"}}}"""
    private val epub = Base64.decode(EpubFixture.base64)
    private val letters =
        DownloadableTitle("vovoscan", "t9", "Letters", isBook = true, listOf(DownloadableChapter("b1", 1.0, "Full text")), null)

    /** What the queue asked the platform for. */
    private class RecordingScheduler : DownloadScheduler {
        val calls = mutableListOf<String>()

        override fun schedule(
            wifiOnly: Boolean,
            replace: Boolean,
        ) {
            calls += "schedule wifiOnly=$wifiOnly" + if (replace) " replace" else ""
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    private val seriesPage = """<h1>Ink Road</h1><ul class="chapters"><li><a href="/read/12">Chapter 2</a></li></ul>"""

    private val griot =
        DownloadableTitle(
            "vovoscan",
            "t1",
            "Le Griot de Guerre",
            isBook = false,
            chapters = listOf(DownloadableChapter("c1", 1.0, "Chapter 1"), DownloadableChapter("c2", 2.0, "Chapter 2")),
            resumeChapterId = null,
        )

    private class Setup(
        val downloads: DownloadsRepositoryImpl,
        val reader: ReaderRepositoryImpl,
        val readerLocal: SqlReaderLocalDataSource,
        val network: NetworkMonitor.Fixed,
        val ruleRequests: MutableList<HttpRequestData>,
    )

    @AfterTest
    fun cleanUp() = fs.deleteRecursively(root, mustExist = false)

    private fun TestScope.setup(
        network: NetworkState = NetworkState(online = true, unmetered = true),
        scheduler: DownloadScheduler = DownloadScheduler.None,
        rules: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", HttpStatusCode.NotFound) },
        api: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Setup {
        val io = StandardTestDispatcher(testScheduler)
        val database = ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema))
        val readerLocal = SqlReaderLocalDataSource(database, io)
        val local = SqlDownloadsLocalDataSource(database, io)
        val files = PageFiles(root.toString())
        val monitor = NetworkMonitor.Fixed(network)
        val ruleRequests = mutableListOf<HttpRequestData>()
        val manifests =
            ManifestProvider { key ->
                if (key ==
                    "inkwell"
                ) {
                    SourceManifest("inkwell", Json.parseToJsonElement(inkwell).jsonObject, emptyMap())
                } else {
                    null
                }
            }
        val remote =
            RoutingReaderRemoteDataSource(
                manifests,
                ApiReaderRemoteDataSource(testHttpClient(token = "session-token", handler = api)),
                OnDeviceReaderRemoteDataSource(
                    manifests,
                    RulesHttp(
                        createRulesHttpClient(
                            MockEngine { request ->
                                ruleRequests += request
                                rules(request)
                            },
                        ),
                        monitor,
                    ),
                ),
            )
        return Setup(
            DownloadsRepositoryImpl(remote, readerLocal, local, files, monitor, backgroundScope, io, scheduler) { 1_000L },
            ReaderRepositoryImpl(remote, readerLocal, EpubParser(), io, DownloadedPages(local, files)),
            readerLocal,
            monitor,
            ruleRequests,
        )
    }

    private fun MockRequestHandleScope.internal(request: HttpRequestData): HttpResponseData =
        when {
            request.url.encodedPath == "/api/v1/catalog/chapters/c2" -> respondJson(chapter)
            request.url.encodedPath == "/api/v1/catalog/chapters/b1" -> respondJson(book)
            request.url.encodedPath.startsWith("/media/documents/") ->
                respond(epub, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/epub+zip"))
            request.url.encodedPath.startsWith(
                "/media/pages/",
            ) -> respond(jpeg, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            else -> respond("", HttpStatusCode.NotFound)
        }

    private suspend fun Setup.only(): ChapterDownload = downloads.observeAll().first().single()

    /** The queue works in the app's scope, the mock HTTP engine on its own threads: wait for the state, don't idle. */
    private suspend fun Setup.awaitState(state: DownloadState): ChapterDownload =
        downloads.observeAll().first { it.size == 1 && it.single().state == state }.single()

    private fun files(): List<Path> =
        if (fs.exists(root)) {
            fs
                .listRecursively(root)
                .filter {
                    fs.metadata(it).isRegularFile
                }.toList()
        } else {
            emptyList()
        }

    @Test
    fun anInternalChapterIsReadFromTheDeviceOnceDownloaded() =
        runTest {
            val s = setup { internal(it) }
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            val done = s.awaitState(DownloadState.Done)
            assertEquals(2 to 2, done.pagesDone to done.pageCount)
            assertEquals(2L * jpeg.size, done.bytes)
            assertEquals(listOf("0000.jpg", "0001.jpg"), files().map { it.name }.sorted())

            // Local files first: the reader gets file URLs, not the signed links.
            val pages =
                s.reader
                    .observe("vovoscan", "c2")
                    .first()!!
                    .pages
            assertTrue(pages.all { it.url.startsWith("file://") && it.url.endsWith(".jpg") }, pages.toString())

            // Deleting the download erases its files; the reader falls back to the network.
            s.downloads.remove("vovoscan", "c2")
            advanceUntilIdle()
            assertEquals(emptyList(), files())
            assertTrue(
                s.downloads
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                s.reader
                    .observe("vovoscan", "c2")
                    .first()!!
                    .pages
                    .all { it.url.startsWith("https://api.test/media/pages/") },
            )
        }

    @Test
    fun aRuleBasedChapterIsFetchedFromTheSourceWithoutTheSession() =
        runTest {
            val s =
                setup(
                    rules = { request ->
                        val html =
                            when (request.url.encodedPath) {
                                "/series/ink-road" -> seriesPage
                                "/read/12" -> """<div class="reader"><img data-src="/img/1.png"><img data-src="/img/2.png"></div>"""
                                else -> null
                            }
                        when {
                            html != null -> respond(html, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                            request.url.encodedPath.startsWith("/img/") -> respond(png, HttpStatusCode.OK)
                            else -> respond("", HttpStatusCode.NotFound)
                        }
                    },
                ) { error("the API is not used for a rule-based source") }
            val inkRoad =
                DownloadableTitle("inkwell", "ink-road", "Ink Road", false, listOf(DownloadableChapter("12", 2.0, "Chapter 2")), null)
            s.downloads.enqueue(inkRoad, inkRoad.chapters)
            s.awaitState(DownloadState.Done)
            assertEquals(listOf("0000.png", "0001.png"), files().map { it.name }.sorted())
            // Only the source's declared host, and never the vovoscan session.
            assertTrue(s.ruleRequests.all { it.url.host == "comics.inkwell.example" })
            assertTrue(s.ruleRequests.none { it.headers[HttpHeaders.Authorization] != null || it.headers[HttpHeaders.Cookie] != null })
            assertEquals(4, s.ruleRequests.size)
        }

    @Test
    fun aPageOnAnUndeclaredHostIsNotDownloaded() =
        runTest {
            val s =
                setup(
                    rules = { request ->
                        val html =
                            when (request.url.encodedPath) {
                                "/series/ink-road" -> seriesPage
                                "/read/12" -> """<div class="reader"><img data-src="https://tracker.elsewhere.example/1.png"></div>"""
                                else -> ""
                            }
                        respond(html, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                    },
                ) { error("unused") }
            val inkRoad =
                DownloadableTitle("inkwell", "ink-road", "Ink Road", false, listOf(DownloadableChapter("12", 2.0, "Chapter 2")), null)
            s.downloads.enqueue(inkRoad, inkRoad.chapters)
            val failed = s.awaitState(DownloadState.Failed)
            assertEquals("This source can't reach tracker.elsewhere.example.", failed.error)
            assertTrue(s.ruleRequests.none { it.url.host == "tracker.elsewhere.example" })
            assertEquals(emptyList(), files())
        }

    @Test
    fun wifiOnlyWaitsOnMobileData() =
        runTest {
            val s = setup(network = NetworkState(online = true, unmetered = false)) { internal(it) }
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            s.awaitState(DownloadState.Waiting)
            assertEquals(DownloadBlock.NotOnWifi, s.downloads.observeBlock().first())
            assertEquals(emptyList(), files())

            // Wi-Fi again: it carries on by itself.
            s.network.set(NetworkState(online = true, unmetered = true))
            s.awaitState(DownloadState.Done)
            assertNull(s.downloads.observeBlock().first())
        }

    @Test
    fun turningWifiOnlyOffUsesMobileData() =
        runTest {
            val s = setup(network = NetworkState(online = true, unmetered = false)) { internal(it) }
            assertTrue(s.downloads.observeWifiOnly().first())
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            s.awaitState(DownloadState.Waiting)
            s.downloads.setWifiOnly(false)
            s.awaitState(DownloadState.Done)
            assertFalse(s.downloads.observeWifiOnly().first())
        }

    @Test
    fun withoutNetworkItWaitsThenResumes() =
        runTest {
            val s = setup(network = NetworkState(online = false, unmetered = false)) { internal(it) }
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            s.awaitState(DownloadState.Waiting)
            assertEquals(DownloadBlock.NoConnection, s.downloads.observeBlock().first())
            s.network.set(NetworkState(online = true, unmetered = true))
            s.awaitState(DownloadState.Done)
        }

    @Test
    fun aFailureWhileOnlineCanBeRetried() =
        runTest {
            var broken = true
            val s =
                setup { request ->
                    if (broken && request.url.encodedPath == "/media/pages/p1") respond("", HttpStatusCode.Forbidden) else internal(request)
                }
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            val failed = s.awaitState(DownloadState.Failed)
            assertEquals(1, failed.pagesDone)
            assertEquals("The download stopped. Try again.", failed.error)

            broken = false
            s.downloads.retry("vovoscan", "c2")
            s.awaitState(DownloadState.Done)
            assertEquals(2, files().size)
        }

    @Test
    fun theQueueRunsInOrderAndSkipsWhatIsAlreadyQueued() =
        runTest {
            val s =
                setup { request ->
                    if (request.url.encodedPath ==
                        "/api/v1/catalog/chapters/c1"
                    ) {
                        respond("", HttpStatusCode.NotFound)
                    } else {
                        internal(request)
                    }
                }
            s.downloads.enqueue(griot, griot.chapters)
            s.downloads.enqueue(griot, griot.chapters)
            val all =
                s.downloads.observeTitle("vovoscan", "t1").first { list ->
                    list.size == 2 && list.all { it.state == DownloadState.Done || it.state == DownloadState.Failed }
                }
            assertEquals(listOf("c1" to DownloadState.Failed, "c2" to DownloadState.Done), all.map { it.chapterId to it.state })
        }

    @Test
    fun signOutAndSourceRemovalEraseTheFiles() =
        runTest {
            val s = setup { internal(it) }
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            s.awaitState(DownloadState.Done)
            assertEquals(2, files().size)
            s.downloads.clearSource("vovoscan")
            assertEquals(emptyList(), files())
            assertTrue(
                s.downloads
                    .observeAll()
                    .first()
                    .isEmpty(),
            )

            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            s.awaitState(DownloadState.Done)
            assertEquals(2, files().size)
            s.downloads.clearLocal()
            assertFalse(fs.exists(root))
            assertTrue(
                s.downloads
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun aBookOpenedOnceShowsAsSaved() =
        runTest {
            val s = setup { error("unused") }
            s.readerLocal.save(
                "gutenberg",
                ReaderChapterDto("1342", "1342", "Pride and Prejudice", kind = "book", number = 1.0, name = "Full text", format = "text"),
            )
            s.readerLocal.saveBook("gutenberg", "1342", listOf(ParsedSection("Chapter 1", "<p>It is a truth…</p>")))
            val saved =
                s.downloads
                    .observeTitle("gutenberg", "1342")
                    .first()
                    .single()
            assertTrue(saved.isBook)
            assertEquals(DownloadState.Done to "Full text", saved.state to saved.label)
            assertTrue(saved.bytes > 0)

            s.downloads.remove("gutenberg", "1342")
            assertTrue(
                s.downloads
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertNull(s.reader.observeBook("gutenberg", "1342").first())
        }

    @Test
    fun aBookNeverOpenedIsDownloadedAndSavedAsOpeningItWould() =
        runTest {
            val s = setup { internal(it) }
            assertNull(s.reader.observeBook("vovoscan", "b1").first())
            s.downloads.enqueue(letters, letters.chapters)
            // The queue row goes once the sections are saved: then it is a saved book, as an opened one.
            val saved =
                s.downloads
                    .observeTitle("vovoscan", "t9")
                    .first { list -> list.singleOrNull()?.state == DownloadState.Done }
                    .single()
            assertTrue(saved.isBook)
            assertEquals("Full text", saved.label)
            assertTrue(saved.bytes > 0)
            assertTrue(
                s.reader
                    .observeBook("vovoscan", "b1")
                    .first()!!
                    .sections
                    .isNotEmpty(),
            )
            // Offline, the reader opens it from the device (no new request needed).
            assertEquals(
                com.vovoscan.core.common.AppResult
                    .Ok(Unit),
                s.reader.loadBook("vovoscan", "b1"),
            )
            assertTrue(
                s.downloads
                    .observeActivity()
                    .first()
                    .let { !it.busy },
            )
            assertEquals(emptyList(), files())
        }

    @Test
    fun aBookThatIsNotAnEpubFails() =
        runTest {
            val s =
                setup { request ->
                    if (request.url.encodedPath.startsWith(
                            "/media/documents/",
                        )
                    ) {
                        respond("<html/>", HttpStatusCode.OK)
                    } else {
                        internal(request)
                    }
                }
            s.downloads.enqueue(letters, letters.chapters)
            val failed = s.awaitState(DownloadState.Failed)
            assertTrue(failed.isBook)
            assertEquals("Not an EPUB file.", failed.error)
            assertNull(s.reader.observeBook("vovoscan", "b1").first())
        }

    @Test
    fun aBookWaitsForWifiLikeAChapter() =
        runTest {
            val s = setup(network = NetworkState(online = true, unmetered = false)) { internal(it) }
            s.downloads.enqueue(letters, letters.chapters)
            s.awaitState(DownloadState.Waiting)
            assertEquals(
                DownloadActivity(books = 1, current = s.only(), block = DownloadBlock.NotOnWifi),
                s.downloads.observeActivity().first(),
            )
            s.network.set(NetworkState(online = true, unmetered = true))
            s.downloads.observeTitle("vovoscan", "t9").first { list -> list.singleOrNull()?.state == DownloadState.Done }
        }

    @Test
    fun pausingPutsTheChapterBackInTheQueueAndDrainingFinishesItAtTheFirstMissingPage() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val pageRequests = mutableListOf<String>()
            val s =
                setup { request ->
                    if (request.url.encodedPath.startsWith("/media/pages/")) pageRequests += request.url.encodedPath
                    if (request.url.encodedPath == "/media/pages/p1") gate.await()
                    internal(request)
                }
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            s.downloads.observeAll().first { it.singleOrNull()?.pagesDone == 1 }

            // The system is about to suspend the app: stop cleanly, nothing failed.
            s.downloads.pause()
            val paused = s.only()
            assertEquals(DownloadState.Queued to 1, paused.state to paused.pagesDone)
            gate.complete(Unit)

            // Background work runs it again: only the missing page is fetched.
            assertNull(s.downloads.drain())
            assertEquals(DownloadState.Done, s.only().state)
            assertEquals(listOf("/media/pages/p0", "/media/pages/p1", "/media/pages/p1"), pageRequests)
            assertEquals(2, files().size)
        }

    @Test
    fun drainingSaysWhyTheQueueWaits() =
        runTest {
            val s = setup(network = NetworkState(online = true, unmetered = false)) { internal(it) }
            assertNull(s.downloads.drain())
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            assertEquals(DownloadBlock.NotOnWifi, s.downloads.drain())
            s.network.set(NetworkState(online = false, unmetered = false))
            assertEquals(
                DownloadBlock.NoConnection,
                s.downloads
                    .observeActivity()
                    .first { it.block == DownloadBlock.NoConnection }
                    .block,
            )
            s.network.set(NetworkState(online = true, unmetered = true))
            assertNull(s.downloads.drain())
            assertEquals(DownloadState.Done, s.only().state)
        }

    @Test
    fun thePlatformIsAskedToKeepTheQueueGoing() =
        runTest {
            val scheduler = RecordingScheduler()
            val s = setup(network = NetworkState(online = true, unmetered = false), scheduler = scheduler) { internal(it) }
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            s.awaitState(DownloadState.Waiting)
            assertEquals(listOf("schedule wifiOnly=true"), scheduler.calls)
            // The network it needs changed: what was scheduled is replaced.
            s.downloads.setWifiOnly(false)
            s.awaitState(DownloadState.Done)
            assertEquals(listOf("schedule wifiOnly=true", "schedule wifiOnly=false replace"), scheduler.calls)
            // Nothing left: a change of setting schedules nothing.
            s.downloads.setWifiOnly(true)
            assertEquals(2, scheduler.calls.size)
        }

    @Test
    fun cancellingTheQueueKeepsWhatIsOnTheDevice() =
        runTest {
            val scheduler = RecordingScheduler()
            val s = setup(network = NetworkState(online = true, unmetered = false), scheduler = scheduler) { internal(it) }
            s.downloads.setWifiOnly(false)
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c2" })
            s.awaitState(DownloadState.Done)
            s.downloads.setWifiOnly(true)
            s.downloads.enqueue(griot, griot.chapters.filter { it.id == "c1" })
            s.downloads.enqueue(letters, letters.chapters)
            s.downloads.observeActivity().first { it.remaining == 2 && it.block == DownloadBlock.NotOnWifi }

            s.downloads.cancelAll()
            assertEquals(
                listOf("c2" to DownloadState.Done),
                s.downloads
                    .observeAll()
                    .first()
                    .map { it.chapterId to it.state },
            )
            assertEquals(2, files().size)
            assertEquals("cancel", scheduler.calls.last())
            assertEquals(DownloadActivity(), s.downloads.observeActivity().first())
        }

    @Test
    fun fileUrlsAreEncoded() {
        assertEquals(
            "file:///var/mobile/Library/Application%20Support/Downloads/ab/0001.jpg",
            fileUrl("/var/mobile/Library/Application Support/Downloads/ab/0001.jpg"),
        )
        assertEquals("jpg" to "png", imageExtension(jpeg) to imageExtension(png))
        assertEquals("img", imageExtension(byteArrayOf(1, 2)))
        assertTrue("/x".toPath().isAbsolute)
    }
}
