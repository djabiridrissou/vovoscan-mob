package com.vovoscan.core.sources

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One source listed by a repository (fields copied from its manifest, URLs made absolute). */
data class RepositoryIndexEntry(
    val key: String,
    val name: String,
    val version: String,
    val contractVersion: Int,
    val kinds: List<String>,
    val langs: List<String>,
    val rights: String?,
    val manifestUrl: String,
    /** Lowercase hex SHA-256 of the manifest file bytes. */
    val sha256: String,
    val iconUrl: String?,
    val changes: String?,
)

/** A plugin repository (`repo.json`, formatVersion 1). */
data class RepositoryIndex(
    val id: String,
    val name: String,
    val homepage: String?,
    val url: String,
    val sources: List<RepositoryIndexEntry>,
) {
    companion object {
        const val FORMAT_VERSION = 1
        private val idPattern = Regex("^[a-z0-9]+(\\.[a-z0-9-]+)+$")
        private val shaPattern = Regex("^[0-9a-f]{64}$")
        private val lenientJson = Json { ignoreUnknownKeys = true }

        /** True when the JSON looks like a repository rather than a single source. */
        fun looksLikeIndex(json: JsonObject): Boolean = "formatVersion" in json && "sources" in json

        fun parse(
            bytes: ByteArray,
            url: String,
            allowLocal: Boolean,
        ): AppResult<RepositoryIndex> {
            if (bytes.size > PluginLimits.REPOSITORY_MAX_BYTES) return PluginErrors.tooLarge
            val json =
                runCatching { lenientJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
                    ?: return PluginErrors.notASource
            return parse(json, url, allowLocal)
        }

        /**
         * Checks the index and keeps the well-formed entries (a malformed entry is skipped, it does not hide the
         * others). URLs must stay https (or local in debug builds).
         */
        fun parse(
            json: JsonObject,
            url: String,
            allowLocal: Boolean,
        ): AppResult<RepositoryIndex> {
            val format = (json["formatVersion"] as? JsonPrimitive)?.intOrNull ?: return PluginErrors.notASource
            if (format > FORMAT_VERSION) return PluginErrors.newerApp
            if (format != FORMAT_VERSION) return PluginErrors.notASource
            val id =
                json.string("id")?.takeIf { idPattern.matches(it) }
                    ?: return PluginErrors.rejected("REPOSITORY_INVALID", t("plugins.repositoryInvalid"))
            val list = json["sources"] as? JsonArray ?: return PluginErrors.notASource
            if (list.size > PluginLimits.REPOSITORY_MAX_SOURCES) {
                return PluginErrors.rejected("REPOSITORY_TOO_LARGE", t("plugins.repositoryTooLarge"))
            }
            val seen = mutableSetOf<String>()
            val entries =
                list.mapNotNull { element ->
                    entry(element as? JsonObject ?: return@mapNotNull null, url, allowLocal)?.takeIf { seen.add(it.key) }
                }
            return AppResult.Ok(
                RepositoryIndex(
                    id = id,
                    name = json.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: id,
                    homepage = json.string("homepage")?.takeIf { PluginLinks.isAllowed(it, allowLocal) },
                    url = url,
                    sources = entries,
                ),
            )
        }

        private fun entry(
            obj: JsonObject,
            base: String,
            allowLocal: Boolean,
        ): RepositoryIndexEntry? {
            val key = obj.string("key")?.takeIf { PluginLimits.KEY_PATTERN.matches(it) && it != PluginLimits.RESERVED_KEY } ?: return null
            val version = obj.string("version")?.takeIf { Semver.parse(it) != null } ?: return null
            val contract = (obj["contractVersion"] as? JsonPrimitive)?.intOrNull ?: return null
            val manifest = obj.string("manifest")?.let { PluginLinks.relativeTo(base, it, allowLocal) } ?: return null
            val sha = obj.string("sha256")?.lowercase()?.takeIf { shaPattern.matches(it) } ?: return null
            return RepositoryIndexEntry(
                key = key,
                name = obj.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: key,
                version = version,
                contractVersion = contract,
                kinds = obj.strings("kinds"),
                langs = obj.strings("langs"),
                rights = obj.string("rights"),
                manifestUrl = manifest,
                sha256 = sha,
                iconUrl = obj.string("icon")?.let { PluginLinks.relativeTo(base, it, allowLocal) },
                changes = obj.string("changes")?.take(PluginLimits.CHANGES_MAX_CHARS)?.takeIf { it.isNotBlank() },
            )
        }

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.strings(name: String): List<String> =
            (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }.orEmpty()
    }
}
