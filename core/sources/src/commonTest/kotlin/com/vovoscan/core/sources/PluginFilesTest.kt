package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.testing.TestZip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginFilesTest {
    private fun code(result: AppResult<*>): String = ((result as AppResult.Err).error as AppError.Rejected).code

    private fun message(result: AppResult<*>): String = ((result as AppResult.Err).error as AppError.Rejected).message

    @Test
    fun githubLinksBecomeRawFiles() {
        fun resolve(link: String) = PluginLinks.resolve(link, allowLocal = false)
        assertEquals(
            "https://raw.githubusercontent.com/djabiridrissou/vovo-plugin/HEAD/repo.json",
            resolve(" https://github.com/djabiridrissou/vovo-plugin "),
        )
        assertEquals("https://raw.githubusercontent.com/o/r/HEAD/repo.json", resolve("https://github.com/o/r.git"))
        assertEquals("https://raw.githubusercontent.com/o/r/HEAD/repo.json", resolve("https://github.com/o/r/"))
        assertEquals("https://raw.githubusercontent.com/o/r/main/repo.json", resolve("https://github.com/o/r/tree/main"))
        assertEquals("https://raw.githubusercontent.com/o/r/dev/plugins/repo.json", resolve("https://github.com/o/r/tree/dev/plugins/"))
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/sources/gutenberg/source.json",
            resolve("https://github.com/o/r/blob/main/sources/gutenberg/source.json"),
        )
        assertEquals("https://example.org/repo.json", resolve("https://example.org/repo.json"))
    }

    @Test
    fun onlyHttpsExceptLocalInDebug() {
        assertNull(PluginLinks.resolve("http://example.org/repo.json", allowLocal = false))
        assertNull(PluginLinks.resolve("http://example.org/repo.json", allowLocal = true))
        assertNull(PluginLinks.resolve("http://localhost:8080/repo.json", allowLocal = false))
        assertEquals("http://localhost:8080/repo.json", PluginLinks.resolve("http://localhost:8080/repo.json", allowLocal = true))
        assertEquals("http://10.0.2.2/repo.json", PluginLinks.resolve("http://10.0.2.2/repo.json", allowLocal = true))
        assertNull(PluginLinks.resolve("ftp://example.org/x", allowLocal = true))
        assertNull(PluginLinks.resolve("file:///etc/passwd", allowLocal = true))
        assertNull(PluginLinks.resolve("not a link", allowLocal = false))
        assertEquals("https://h.example/a/sources/x.json", PluginLinks.relativeTo("https://h.example/a/repo.json", "sources/x.json", false))
        assertEquals(
            "https://cdn.example/x.json",
            PluginLinks.relativeTo("https://h.example/a/repo.json", "https://cdn.example/x.json", false),
        )
        assertNull(PluginLinks.relativeTo("https://h.example/a/repo.json", "http://cdn.example/x.json", false))
        assertNull(PluginLinks.relativeTo("https://h.example/a/repo.json", "../x.json", false))
    }

    @Test
    fun semverOrdersReleasesAndPrereleases() {
        val ordered =
            listOf(
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-alpha.beta",
                "1.0.0-beta",
                "1.0.0-beta.2",
                "1.0.0-beta.11",
                "1.0.0-rc.1",
                "1.0.0",
                "1.0.1",
                "1.2.0",
                "2.0.0",
            )
        ordered.zipWithNext().forEach { (a, b) -> assertTrue(Semver.isNewer(b, a), "$b > $a") }
        assertFalse(Semver.isNewer("1.0.0", "1.0.0"))
        assertTrue(Semver.isNewer("1.10.0", "1.9.9"))
        assertNull(Semver.parse("1.0"))
        assertNull(Semver.parse("v1.0.0"))
        assertFalse(Semver.isNewer("2.0", "1.0.0"))
        assertEquals("1.2.3-rc.1", Semver.parse("1.2.3-rc.1").toString())
    }

    @Test
    fun manifestChecksBeforeInstall() {
        val gutenberg = Json.parseToJsonElement(Fixtures.gutenberg).jsonObject
        val ok = assertIs<AppResult.Ok<ManifestInfo>>(ManifestCheck.check(gutenberg)).value
        assertEquals(listOf("catalogBaseUrl", "filesBaseUrl", "languages"), ok.settings.map { it.name })
        assertEquals("public-domain", ok.rightsBasis)
        assertTrue(ok.settings.first().required)

        fun with(
            field: String,
            value: String,
        ) = ManifestCheck.parse(Fixtures.gutenberg.replaceFirst(Regex("\"$field\": [^,\\n]+"), "\"$field\": $value").encodeToByteArray())
        assertEquals("Needs a newer version of vovoscan.", message(with("contractVersion", "2")))
        assertEquals("PLUGIN_KEY_RESERVED", code(with("key", "\"vovoscan\"")))
        assertEquals("PLUGIN_KEY_INVALID", code(with("key", "\"Bad Key\"")))
        assertEquals("PLUGIN_VERSION_INVALID", code(with("version", "\"1.0\"")))
        assertEquals("This kind of content isn't supported yet.", message(with("kinds", "[\"video\"]")))
        assertEquals("PLUGIN_INVALID", code(ManifestCheck.parse("[1, 2]".encodeToByteArray())))
        assertEquals("PLUGIN_TOO_LARGE", code(ManifestCheck.parse(ByteArray(PluginLimits.MANIFEST_MAX_BYTES + 1) { ' '.code.toByte() })))
        assertEquals("PLUGIN_INCOMPLETE", code(ManifestCheck.parse("""{"contractVersion":1,"key":"abc"}""".encodeToByteArray())))
    }

    @Test
    fun aSourceThatReachesALocalNetworkIsRefused() {
        val gutenberg = Json.parseToJsonElement(Fixtures.gutenberg).jsonObject
        for (host in listOf("192.168.1.1", "localhost", "nas.local", "[::1]", "169.254.169.254")) {
            val hosts = JsonArray(listOf(JsonPrimitive("www.gutenberg.org"), JsonPrimitive(host)))
            assertEquals("PLUGIN_HOST", code(ManifestCheck.check(JsonObject(gutenberg + ("hosts" to hosts)))), host)
        }
    }

    private fun index(
        sources: String,
        format: Int = 1,
    ) =
        """{"formatVersion":$format,"id":"app.vovoscan.official","name":"Official","homepage":"https://github.com/x/y","sources":[$sources]}"""

    private val sha = "a".repeat(64)

    private fun entry(
        key: String,
        extra: String = "",
    ) = """{"key":"$key","name":"N $key","version":"1.2.0","contractVersion":1,"kinds":["book"],"langs":["en"],""" +
        """"rights":"public-domain","manifest":"sources/$key/source.json","sha256":"$sha","icon":"sources/$key/icon.png"$extra}"""

    @Test
    fun repositoryIndexIsParsedAndChecked() {
        val url = "https://raw.githubusercontent.com/x/y/HEAD/repo.json"
        val parsed =
            assertIs<AppResult.Ok<RepositoryIndex>>(
                RepositoryIndex.parse(
                    index(
                        entry("gutenberg", ""","changes":"Faster."""") + "," + entry("gutenberg") + ",{\"key\":\"broken\"}," +
                            entry("vovoscan"),
                    ).encodeToByteArray(),
                    url,
                    false,
                ),
            ).value
        assertEquals("app.vovoscan.official", parsed.id)
        val only = parsed.sources.single()
        assertEquals("https://raw.githubusercontent.com/x/y/HEAD/sources/gutenberg/source.json", only.manifestUrl)
        assertEquals("https://raw.githubusercontent.com/x/y/HEAD/sources/gutenberg/icon.png", only.iconUrl)
        assertEquals("Faster.", only.changes)

        assertEquals(
            "Needs a newer version of vovoscan.",
            message(RepositoryIndex.parse(index("", format = 2).encodeToByteArray(), url, false)),
        )
        assertEquals(
            "REPOSITORY_INVALID",
            code(RepositoryIndex.parse(index("").replace("app.vovoscan.official", "Bad Id").encodeToByteArray(), url, false)),
        )
        val tooMany = (1..501).joinToString(",") { entry("s$it") }
        assertEquals("REPOSITORY_TOO_LARGE", code(RepositoryIndex.parse(index(tooMany).encodeToByteArray(), url, false)))
        assertEquals("PLUGIN_TOO_LARGE", code(RepositoryIndex.parse(ByteArray(PluginLimits.REPOSITORY_MAX_BYTES + 1), url, false)))
        val insecure = index(entry("abc").replace("sources/abc/source.json", "http://evil.example/source.json"))
        assertTrue(
            assertIs<AppResult.Ok<RepositoryIndex>>(RepositoryIndex.parse(insecure.encodeToByteArray(), url, false))
                .value.sources
                .isEmpty(),
        )
    }

    @Test
    fun vovoPackageIsRead() {
        val manifest = Fixtures.gutenberg.encodeToByteArray()
        val pack =
            assertIs<AppResult.Ok<VovoPackage>>(
                VovoPackageReader().read(
                    TestZip.of(
                        "source.json" to manifest,
                        "README.md" to "# Hi".encodeToByteArray(),
                    ),
                ),
            ).value
        assertEquals(Fixtures.gutenberg, pack.manifest.decodeToString())
        assertEquals("# Hi", pack.readme)
        assertNull(pack.icon)
    }

    @Test
    fun vovoPackageLimitsAreEnforced() {
        val manifest = "{}".encodeToByteArray()
        val reader = VovoPackageReader()
        assertEquals("PLUGIN_PACKAGE", code(reader.read(TestZip.of("README.md" to manifest))))
        assertEquals("PLUGIN_PACKAGE", code(reader.read(TestZip.of("source.json" to manifest, "../evil.sh" to manifest))))
        assertEquals("PLUGIN_PACKAGE", code(reader.read(TestZip.of("source.json" to manifest, "sub/" to ByteArray(0)))))
        assertEquals("PLUGIN_PACKAGE", code(reader.read(TestZip.of("source.json" to manifest, "/source.json" to manifest))))
        assertEquals("PLUGIN_PACKAGE", code(reader.read(TestZip.of("source.json" to manifest, "source.json" to manifest))))
        assertEquals("PLUGIN_PACKAGE", code(reader.read(TestZip.of("source.json" to manifest, "notes.txt" to manifest))))
        assertEquals(
            "PLUGIN_TOO_LARGE",
            code(
                reader.read(
                    TestZip.of(
                        "source.json" to manifest,
                        "icon.png" to ByteArray(PluginLimits.ICON_MAX_BYTES + 1),
                    ),
                ),
            ),
        )
        assertEquals("PLUGIN_TOO_LARGE", code(reader.read(ByteArray(PluginLimits.PACKAGE_MAX_BYTES + 1))))
        assertEquals("PLUGIN_PACKAGE", code(reader.read("not a zip".encodeToByteArray())))
    }

    @Test
    fun sha256IsLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
        assertNotNull(sha256Hex(ByteArray(0)))
    }
}
