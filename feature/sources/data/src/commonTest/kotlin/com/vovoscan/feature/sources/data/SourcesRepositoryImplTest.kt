package com.vovoscan.feature.sources.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.sources.PluginFetcher
import com.vovoscan.core.sources.VovoPackageReader
import com.vovoscan.core.sources.createPluginHttpClient
import com.vovoscan.core.sources.sha256Hex
import com.vovoscan.core.testing.TestZip
import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.sources.data.db.SourcesDatabase
import com.vovoscan.feature.sources.domain.EntryStatus
import com.vovoscan.feature.sources.domain.PluginCandidate
import com.vovoscan.feature.sources.domain.PluginLookup
import com.vovoscan.feature.sources.domain.SourceEngine
import com.vovoscan.feature.sources.domain.SourceOrigin
import com.vovoscan.feature.sources.domain.SourceRemovedListener
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    /** A minimal manifest the engine accepts (the rule sections are not read by these tests). */
    private fun manifest(
        key: String,
        version: String,
        hosts: List<String> = listOf("demo.example"),
        settings: String = "",
    ) = """{"contractVersion":1,"key":"$key","name":"Demo $key","version":"$version","description":"Demo source.",""" +
        """"kinds":["comic"],"langs":["en"],"rights":{"basis":"public-domain","notes":"Demo notes for the tests."},""" +
        """"hosts":[${hosts.joinToString(",") { "\"$it\"" }}],"settings":{$settings},"catalog":{},"title":{},"content":{}}"""

    private val repoUrl = "https://raw.githubusercontent.com/demo/plugins/HEAD/repo.json"

    private fun index(vararg entries: Triple<String, String, String>) =
        """{"formatVersion":1,"id":"org.demo.plugins","name":"Demo plugins","sources":[""" +
            entries.joinToString(",") { (key, version, sha) ->
                """{"key":"$key","name":"Demo $key","version":"$version","contractVersion":1,"kinds":["comic"],"langs":["en"],""" +
                    """"rights":"public-domain","manifest":"sources/$key/source.json","sha256":"$sha","icon":"sources/$key/icon.png",""" +
                    """"changes":"Changes of $version."}"""
            } + "]}"

    /** The vovoscan API, small but stateful: plugins and repositories PUT/DELETE, listed by `GET catalog/sources`. */
    private class FakeApi {
        var online = true
        val plugins = linkedMapOf<String, JsonObject>()
        val repositories = linkedMapOf<String, JsonObject>()
        val calls = mutableListOf<String>()

        val http =
            testHttpClient { request ->
                if (!online) throw IllegalStateException("offline")
                val path = request.url.encodedPath
                calls += "${request.method.value} $path"
                val body =
                    (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()?.let {
                        Json
                            .parseToJsonElement(
                                it,
                            ).jsonObject
                    }
                when {
                    request.method == HttpMethod.Put && path.startsWith("/api/v1/plugins/") -> {
                        val key = path.substringAfterLast('/')
                        if (key == "gutenberg") {
                            respondJson(
                                """{"code":409,"message":"Already available as an official source.","data":{"error":"PLUGIN_KEY_TAKEN"}}""",
                                HttpStatusCode.Conflict,
                            )
                        } else {
                            plugins[key] = body!!
                            respond("", HttpStatusCode.NoContent)
                        }
                    }
                    request.method == HttpMethod.Delete && path.startsWith("/api/v1/plugins/") -> {
                        plugins.remove(path.substringAfterLast('/'))
                        respond("", HttpStatusCode.NoContent)
                    }
                    request.method == HttpMethod.Put && path.startsWith("/api/v1/plugin-repositories/") -> {
                        repositories[path.substringAfterLast('/')] = body!!
                        respond("", HttpStatusCode.NoContent)
                    }
                    request.method == HttpMethod.Delete && path.startsWith("/api/v1/plugin-repositories/") -> {
                        repositories.remove(path.substringAfterLast('/'))
                        respond("", HttpStatusCode.NoContent)
                    }
                    path == "/api/v1/plugins" -> {
                        val repos =
                            repositories.entries.joinToString(",") { (id, b) ->
                                """{"id":"$id","url":${b["url"]},"name":${b["name"]},"updatedAt":${b["updatedAt"]}}"""
                            }
                        respondJson("""{"data":{"repositories":[$repos],"plugins":[]}}""")
                    }
                    else -> respondJson(sources())
                }
            }

        private fun sources(): String {
            val official =
                """{"key":"vovoscan","name":"vovoscan","engine":"internal","contentKinds":["comic","book"],"enabled":true,"position":0,""" +
                    """"origin":"official"},{"key":"gutenberg","name":"Project Gutenberg","engine":"rules","contentKinds":["book"],""" +
                    """"enabled":true,"position":1,"origin":"official","manifest":{"contractVersion":1,"key":"gutenberg"}}"""
            val user =
                plugins.entries.mapIndexed { i, (key, body) ->
                    val manifest = body.getValue("manifest").jsonObject
                    """{"key":"$key","name":${manifest["name"]},"engine":"rules","version":${manifest["version"]},"kinds":["comic"],""" +
                        """"langs":["en"],"enabled":true,"position":${10 + i},"origin":"user","manifest":$manifest,""" +
                        """"settings":${body["settings"]},"originUrl":${body["originUrl"]},"repositoryId":${body["repositoryId"]}}"""
                }
            return """{"data":[${(listOf(official) + user).joinToString(",")}]}"""
        }
    }

    private class Harness(
        val repo: SourcesRepositoryImpl,
        val local: SqlSourcesLocalDataSource,
        val files: MutableMap<String, String>,
        val fetched: MutableList<String>,
        val removed: MutableList<String>,
        var now: Long,
    )

    private fun TestScope.harness(api: FakeApi): Harness {
        val files = mutableMapOf<String, String>()
        val fetched = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val local =
            SqlSourcesLocalDataSource(SourcesDatabase(inMemoryDriver(SourcesDatabase.Schema)), StandardTestDispatcher(testScheduler))
        val fetcher =
            PluginFetcher(
                createPluginHttpClient(
                    MockEngine { request ->
                        if (!api.online) throw IllegalStateException("offline")
                        fetched += request.url.toString()
                        files[request.url.toString()]?.let { respond(it, HttpStatusCode.OK) } ?: respond("", HttpStatusCode.NotFound)
                    },
                ),
            )
        lateinit var harness: Harness
        val repo =
            SourcesRepositoryImpl(
                remote = KtorSourcesRemoteDataSource(api.http),
                local = local,
                files = KtorPluginFilesRemoteDataSource(fetcher),
                clock = { harness.now },
                removedListener = SourceRemovedListener { removed += it },
                packages = VovoPackageReader(),
            )
        harness = Harness(repo, local, files, fetched, removed, 1_000_000L)
        return harness
    }

    /** Publishes a repository with the given sources (key to manifest text). */
    private fun Harness.publish(vararg sources: Pair<String, String>) {
        val entries =
            sources.map { (key, text) ->
                files["https://raw.githubusercontent.com/demo/plugins/HEAD/sources/$key/source.json"] = text
                val version =
                    Json
                        .parseToJsonElement(text)
                        .jsonObject["version"]!!
                        .jsonPrimitive.content
                Triple(key, version, sha256Hex(text.encodeToByteArray()))
            }
        files[repoUrl] = index(*entries.toTypedArray())
    }

    private suspend fun Harness.installFromRepository(key: String): AppResult<Unit> {
        val candidate = assertIs<AppResult.Ok<PluginCandidate>>(repo.candidate("org.demo.plugins", key)).value
        return repo.install(candidate, emptyMap())
    }

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
                    { _, _ -> AppResult.Err(AppError.Network) },
                    { 0L },
                    {},
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
            assertEquals(SourceOrigin.Official, sources.last().origin)
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
            val repo =
                SourcesRepositoryImpl(KtorSourcesRemoteDataSource(http), local, { _, _ -> AppResult.Err(AppError.Network) }, { 0L }, {})
            val saved = repo.save(listOf("gutenberg" to true, "vovoscan" to true))
            assertTrue(saved is AppResult.Err)
            assertNotNull(local.pending())
            online = true
            assertEquals(AppResult.Ok(Unit), repo.refresh())
            assertEquals(1, sentBodies.size)
            assertNull(local.pending())
        }

    @Test
    fun repositoryIsSavedThenSourcesInstalledAndUpdated() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            h.repo.refresh()
            h.publish("demo" to manifest("demo", "1.0.0"), "other" to manifest("other", "2.0.0"))

            val lookup = assertIs<AppResult.Ok<PluginLookup>>(h.repo.open("https://github.com/demo/plugins")).value
            val listing = assertIs<PluginLookup.Repository>(lookup).listing
            assertEquals(listOf(EntryStatus.Install, EntryStatus.Install), listing.entries.map { it.status })
            assertEquals(
                "Demo plugins",
                api.repositories["org.demo.plugins"]
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                2,
                h.repo
                    .observeRepositories()
                    .first()
                    .single()
                    .sourceCount,
            )

            assertEquals(AppResult.Ok(Unit), h.installFromRepository("demo"))
            val sent = assertNotNull(api.plugins["demo"])
            assertEquals("repository", sent["origin"]?.jsonPrimitive?.content)
            assertEquals("org.demo.plugins", sent["repositoryId"]?.jsonPrimitive?.content)
            assertTrue(
                sent["updatedAt"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .startsWith("1970-01-01T00:16:40"),
            )
            val installed =
                h.repo
                    .observe()
                    .first()
                    .first { it.key == "demo" }
            assertEquals(SourceOrigin.Repository, installed.origin)
            assertEquals("Demo plugins", installed.repositoryName)
            assertEquals(listOf("demo.example"), installed.hosts)
            assertEquals("https://raw.githubusercontent.com/demo/plugins/HEAD/sources/demo/icon.png", installed.iconUrl)

            // A newer version: found by the update check, applied from what was kept (works offline).
            h.publish("demo" to manifest("demo", "1.1.0"), "other" to manifest("other", "2.0.0"))
            assertEquals(AppResult.Ok(Unit), h.repo.checkUpdates(force = true))
            val update =
                assertNotNull(
                    h.repo
                        .observe()
                        .first()
                        .first { it.key == "demo" }
                        .update,
                )
            assertEquals("1.1.0", update.version)
            assertEquals("Changes of 1.1.0.", update.changes)
            assertTrue(update.newHosts.isEmpty())
            api.online = false
            val candidate = assertIs<AppResult.Ok<PluginCandidate>>(h.repo.updateCandidate("demo")).value
            assertTrue(candidate.isUpdate)
            assertEquals(AppResult.Ok(Unit), h.repo.install(candidate, emptyMap()))
            val updated =
                h.repo
                    .observe()
                    .first()
                    .first { it.key == "demo" }
            assertEquals("1.1.0", updated.version)
            assertNull(updated.update)
            assertTrue(updated.pendingSync)
            api.online = true
            assertEquals(AppResult.Ok(Unit), h.repo.refresh())
            assertEquals(
                "1.1.0",
                api.plugins["demo"]
                    ?.get("manifest")
                    ?.jsonObject
                    ?.get("version")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                EntryStatus.Installed,
                (h.repo.browse("org.demo.plugins") as AppResult.Ok)
                    .value.entries
                    .first()
                    .status,
            )
        }

    @Test
    fun updatesAreCheckedAtMostEveryTwelveHoursUnlessForced() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            h.publish("demo" to manifest("demo", "1.0.0"))
            h.repo.open(repoUrl)
            h.installFromRepository("demo")
            assertEquals(AppResult.Ok(Unit), h.repo.checkUpdates(force = false))
            h.fetched.clear()
            h.now += 11 * 60 * 60 * 1000L
            h.repo.checkUpdates(force = false)
            assertTrue(h.fetched.isEmpty())
            h.now += 2 * 60 * 60 * 1000L
            h.repo.checkUpdates(force = false)
            assertTrue(h.fetched.contains(repoUrl))
            h.fetched.clear()
            h.repo.checkUpdates(force = true)
            assertTrue(h.fetched.contains(repoUrl))
        }

    @Test
    fun checksumMismatchAndEntryMismatchAreRefused() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            h.publish("demo" to manifest("demo", "1.0.0"))
            h.files["https://raw.githubusercontent.com/demo/plugins/HEAD/sources/demo/source.json"] =
                manifest("demo", "1.0.0").replace("Demo source.", "Tampered.")
            h.repo.open(repoUrl)
            val refused = assertIs<AppResult.Err>(h.repo.candidate("org.demo.plugins", "demo"))
            assertEquals("PLUGIN_CHECKSUM", (refused.error as AppError.Rejected).code)

            // The right bytes, but not the version the index announces.
            val other = manifest("demo", "9.9.9")
            h.files[repoUrl] = index(Triple("demo", "1.0.0", sha256Hex(other.encodeToByteArray())))
            h.files["https://raw.githubusercontent.com/demo/plugins/HEAD/sources/demo/source.json"] = other
            h.repo.browse("org.demo.plugins")
            val mismatch = assertIs<AppResult.Err>(h.repo.candidate("org.demo.plugins", "demo"))
            assertEquals("PLUGIN_MISMATCH", (mismatch.error as AppError.Rejected).code)
            assertTrue(api.plugins.isEmpty())
        }

    @Test
    fun newHostsAreReportedBeforeAnUpdate() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            h.publish("demo" to manifest("demo", "1.0.0"))
            h.repo.open(repoUrl)
            h.installFromRepository("demo")
            h.publish("demo" to manifest("demo", "2.0.0", hosts = listOf("demo.example", "cdn.example")))
            h.repo.browse("org.demo.plugins")
            val candidate = assertIs<AppResult.Ok<PluginCandidate>>(h.repo.candidate("org.demo.plugins", "demo")).value
            assertTrue(candidate.isUpdate)
            assertEquals(listOf("cdn.example"), candidate.newHosts)
            h.repo.checkUpdates(force = true)
            assertEquals(
                listOf("cdn.example"),
                h.repo
                    .observe()
                    .first()
                    .first { it.key == "demo" }
                    .update
                    ?.newHosts,
            )
        }

    @Test
    fun singleLinkRequiredSettingsAndOfficialKeyConflict() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            h.repo.refresh()
            val url = "https://example.org/demo.json"
            h.files[url] = manifest("linked", "1.0.0", settings = """"baseUrl":{"label":"Base","type":"url","required":true}""")
            val single = assertIs<PluginLookup.Single>((h.repo.open(url) as AppResult.Ok).value).candidate
            assertEquals(SourceOrigin.Link, single.origin)
            assertEquals(
                "SETTINGS_INVALID",
                ((h.repo.install(single, emptyMap()) as AppResult.Err).error as AppError.Rejected).code,
            )
            assertEquals(
                "SETTINGS_INVALID",
                ((h.repo.install(single, mapOf("baseUrl" to "http://plain.example")) as AppResult.Err).error as AppError.Rejected).code,
            )
            assertEquals(AppResult.Ok(Unit), h.repo.install(single, mapOf("baseUrl" to "https://mirror.example")))
            val item =
                h.repo
                    .observe()
                    .first()
                    .first { it.key == "linked" }
            assertEquals(listOf("demo.example", "mirror.example"), item.hosts)
            assertEquals(
                url,
                api.plugins["linked"]
                    ?.get("originUrl")
                    ?.jsonPrimitive
                    ?.content,
            )

            h.files["https://example.org/gutenberg.json"] = manifest("gutenberg", "1.0.0")
            val conflict =
                assertIs<PluginLookup.Single>(
                    (h.repo.open("https://example.org/gutenberg.json") as AppResult.Ok).value,
                ).candidate
            assertEquals("Already available as an official source.", conflict.blocker)
            assertEquals(
                "This link isn't a vovoscan source or repository.",
                message(
                    h.repo.open(
                        "https://example.org/missing.json".also {
                            h.files[it] =
                                "{}"
                        },
                    ),
                ),
            )
            assertEquals("Use an https:// link.", message(h.repo.open("http://example.org/repo.json")))
        }

    private fun message(result: AppResult<*>) = ((result as AppResult.Err).error as AppError.Rejected).message

    @Test
    fun aRefusedInstallIsReportedAndDropped() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            // Not known as official on this device yet: the server refuses it.
            val candidate =
                assertIs<AppResult.Ok<PluginCandidate>>(
                    h.repo.openFile(manifest("gutenberg", "1.0.0").encodeToByteArray(), "source.json"),
                ).value
            val refused = assertIs<AppResult.Err>(h.repo.install(candidate, emptyMap()))
            assertEquals("PLUGIN_KEY_TAKEN", (refused.error as AppError.Rejected).code)
            assertTrue(h.local.outbox().isEmpty())
            assertEquals(
                SourceOrigin.Official,
                h.repo
                    .observe()
                    .first()
                    .first { it.key == "gutenberg" }
                    .origin,
            )
        }

    @Test
    fun offlineInstallIsServedToTheEngineAndReplayedLater() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            api.online = false
            val pack = TestZip.of("source.json" to manifest("filed", "1.0.0").encodeToByteArray(), "icon.png" to byteArrayOf(1, 2, 3))
            val candidate = assertIs<AppResult.Ok<PluginCandidate>>(h.repo.openFile(pack, "filed-1.0.0.vovo")).value
            assertTrue(candidate.iconUrl.orEmpty().startsWith("data:image/png;base64,"))
            assertEquals(AppResult.Ok(Unit), h.repo.install(candidate, emptyMap()))

            // Catalog and reader routing see it at once, offline.
            val served = assertNotNull(h.local.manifest("filed"))
            assertEquals(setOf("demo.example"), served.allowedHosts)
            assertEquals(1, h.local.outbox().size)
            assertTrue(
                h.repo
                    .observe()
                    .first()
                    .single()
                    .pendingSync,
            )

            // An older file is refused.
            val older =
                assertIs<AppResult.Ok<PluginCandidate>>(
                    h.repo.openFile(manifest("filed", "0.9.0").encodeToByteArray(), "x.json"),
                ).value
            assertEquals("A newer version is already installed.", older.blocker)

            api.online = true
            assertEquals(AppResult.Ok(Unit), h.repo.refresh())
            assertEquals(listOf("PUT /api/v1/plugins/filed", "GET /api/v1/catalog/sources", "GET /api/v1/plugins"), api.calls)
            assertEquals(
                "file",
                api.plugins["filed"]
                    ?.get("origin")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertTrue(h.local.outbox().isEmpty())
            val item =
                h.repo
                    .observe()
                    .first()
                    .first { it.key == "filed" }
            assertEquals(SourceOrigin.File, item.origin)
            assertTrue(!item.pendingSync)
        }

    @Test
    fun removalClearsCachesAndQueuesTheDelete() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            h.repo.install(
                (h.repo.openFile(manifest("gone", "1.0.0").encodeToByteArray(), "source.json") as AppResult.Ok).value,
                emptyMap(),
            )
            api.online = false
            assertEquals(AppResult.Ok(Unit), h.repo.remove("gone"))
            assertEquals(listOf("gone"), h.removed)
            assertNull(h.local.manifest("gone"))
            assertTrue(
                h.repo
                    .observe()
                    .first()
                    .none { it.key == "gone" },
            )
            val op = h.local.outbox().single()
            assertEquals(Triple(PluginOp.PLUGIN, "gone", PluginOp.DELETE), Triple(op.target, op.id, op.kind))
            api.online = true
            h.repo.refresh()
            assertTrue("DELETE /api/v1/plugins/gone" in api.calls)
            assertNull(api.plugins["gone"])

            assertEquals("SOURCE_OFFICIAL", ((h.repo.remove("gutenberg") as AppResult.Err).error as AppError.Rejected).code)
        }

    @Test
    fun aSourceRemovedOnAnotherDeviceHasItsCachesCleared() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            h.repo.install(
                (h.repo.openFile(manifest("elsewhere", "1.0.0").encodeToByteArray(), "s.json") as AppResult.Ok).value,
                emptyMap(),
            )
            api.plugins.remove("elsewhere")
            h.repo.refresh()
            assertEquals(listOf("elsewhere"), h.removed)
        }

    @Test
    fun settingsAreSavedAndQueued() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            val text = manifest("conf", "1.0.0", settings = """"lang":{"label":"Language","type":"string","default":"en"}""")
            h.repo.install((h.repo.openFile(text.encodeToByteArray(), "s.json") as AppResult.Ok).value, emptyMap())
            assertEquals(
                "en",
                h.local
                    .manifest("conf")
                    ?.settings
                    ?.get("lang"),
            )
            api.online = false
            assertEquals(AppResult.Ok(Unit), h.repo.saveSettings("conf", mapOf("lang" to "fr")))
            assertEquals(
                "fr",
                h.local
                    .manifest("conf")
                    ?.settings
                    ?.get("lang"),
            )
            api.online = true
            h.repo.refresh()
            assertEquals(
                "fr",
                (api.plugins["conf"]?.get("settings") as? JsonObject)
                    ?.get("lang")
                    ?.let { (it as JsonPrimitive).contentOrNull },
            )
        }

    @Test
    fun removedRepositoryIsForgottenButItsSourcesStay() =
        runTest {
            val api = FakeApi()
            val h = harness(api)
            h.publish("demo" to manifest("demo", "1.0.0"))
            h.repo.open(repoUrl)
            h.installFromRepository("demo")
            assertEquals(AppResult.Ok(Unit), h.repo.removeRepository("org.demo.plugins"))
            assertTrue(
                h.repo
                    .observeRepositories()
                    .first()
                    .isEmpty(),
            )
            assertTrue(api.repositories.isEmpty())
            assertTrue(
                h.repo
                    .observe()
                    .first()
                    .any { it.key == "demo" },
            )
        }
}
