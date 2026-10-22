package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.HostPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Limits and rules shared by plugin files (spec v1). */
object PluginLimits {
    const val MANIFEST_MAX_BYTES = 256 * 1024
    const val REPOSITORY_MAX_BYTES = 512 * 1024
    const val REPOSITORY_MAX_SOURCES = 500
    const val ICON_MAX_BYTES = 256 * 1024
    const val PACKAGE_MAX_BYTES = 1024 * 1024
    const val PACKAGE_MAX_ENTRIES = 8
    const val CHANGES_MAX_CHARS = 2000
    const val RESERVED_KEY = "vovoscan"

    /** Kinds this app can read today. */
    val SUPPORTED_KINDS = setOf("comic", "book")
    val KEY_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,62}$")
    val REQUIRED_FIELDS = listOf("contractVersion", "key", "name", "version", "kinds", "langs", "catalog", "title", "content")
}

/** Messages shown when a plugin cannot be added (codes are stable, messages are for people). */
object PluginErrors {
    fun rejected(
        code: String,
        message: String,
    ): AppResult.Err = AppResult.Err(AppError.Rejected(code, message))

    val notASource get() = rejected("PLUGIN_NOT_A_SOURCE", "This link isn't a vovoscan source or repository.")
    val invalidFile get() = rejected("PLUGIN_INVALID", "This file isn't a valid vovoscan source.")
    val tooLarge get() = rejected("PLUGIN_TOO_LARGE", "This file is too large.")
    val newerApp get() = rejected("PLUGIN_CONTRACT", "Needs a newer version of vovoscan.")
    val kindUnsupported get() = rejected("PLUGIN_KIND", "This kind of content isn't supported yet.")
    val reservedKey get() = rejected("PLUGIN_KEY_RESERVED", "This source uses a reserved name.")
    val invalidKey get() = rejected("PLUGIN_KEY_INVALID", "This source has an invalid key.")
    val invalidVersion get() = rejected("PLUGIN_VERSION_INVALID", "This source has an invalid version.")
    val checksum get() = rejected("PLUGIN_CHECKSUM", "This source doesn't match its repository. It wasn't installed.")
    val mismatch get() = rejected("PLUGIN_MISMATCH", "This source doesn't match its repository entry. It wasn't installed.")
    val linkNotAllowed get() = rejected("PLUGIN_LINK", "Use an https:// link.")
    val privateHost get() = rejected("PLUGIN_HOST", "This source wants to reach a private network. It wasn't installed.")

    fun incomplete(missing: List<String>) =
        rejected("PLUGIN_INCOMPLETE", "This source is incomplete (missing ${missing.joinToString(", ")}).")
}

/** A setting a manifest asks for: `{{settings.name}}` in its templates. */
data class ManifestSetting(
    val name: String,
    val label: String,
    /** "url", "string" or "integer". */
    val type: String,
    val required: Boolean,
    val default: String?,
    val help: String?,
)

/** Typed read-only view of a manifest's top-level fields (what the app shows before and after install). */
class ManifestInfo(
    val json: JsonObject,
) {
    val key: String get() = string("key").orEmpty()
    val name: String get() = string("name") ?: key
    val version: String get() = string("version").orEmpty()
    val summary: String get() = string("description").orEmpty()
    val contractVersion: Int? get() = (json["contractVersion"] as? JsonPrimitive)?.intOrNull
    val kinds: List<String> get() = strings("kinds")
    val langs: List<String> get() = strings("langs")
    val rightsBasis: String? get() = (json["rights"] as? JsonObject)?.let { (it["basis"] as? JsonPrimitive)?.contentOrNull }
    val rightsNotes: String? get() = (json["rights"] as? JsonObject)?.let { (it["notes"] as? JsonPrimitive)?.contentOrNull }

    /** Fixed hosts the source declares (url-type settings add their own host at run time). */
    val hosts: List<String> get() = strings("hosts").map { it.lowercase() }.distinct()

    val settings: List<ManifestSetting>
        get() =
            (json["settings"] as? JsonObject).orEmpty().mapNotNull { (name, spec) ->
                val obj = spec as? JsonObject ?: return@mapNotNull null
                ManifestSetting(
                    name = name,
                    label = (obj["label"] as? JsonPrimitive)?.contentOrNull ?: name,
                    type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "string",
                    required = (obj["required"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    default = (obj["default"] as? JsonPrimitive)?.contentOrNull,
                    help = (obj["help"] as? JsonPrimitive)?.contentOrNull,
                )
            }

    private fun string(name: String): String? = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun strings(name: String): List<String> =
        runCatching { json[name]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()
}

/**
 * "Can this app run it" checks before install or update. Not a review: vovoscan does not check what a third-party
 * source reads, only that this engine understands it.
 */
object ManifestCheck {
    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** Parses and checks manifest bytes (size, JSON object, key, contract, kinds, version). */
    fun parse(bytes: ByteArray): AppResult<ManifestInfo> {
        if (bytes.size > PluginLimits.MANIFEST_MAX_BYTES) return PluginErrors.tooLarge
        val json =
            runCatching { lenientJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
                ?: return PluginErrors.invalidFile
        return check(json)
    }

    fun check(json: JsonObject): AppResult<ManifestInfo> {
        val info = ManifestInfo(json)
        val missing = PluginLimits.REQUIRED_FIELDS.filter { it !in json }
        val key = (json["key"] as? JsonPrimitive)?.contentOrNull
        return when {
            key == PluginLimits.RESERVED_KEY -> PluginErrors.reservedKey
            key == null || !PluginLimits.KEY_PATTERN.matches(key) -> PluginErrors.invalidKey
            info.contractVersion.let { it != null && it > RULES_CONTRACT_VERSION } -> PluginErrors.newerApp
            missing.isNotEmpty() -> PluginErrors.incomplete(missing)
            info.contractVersion == null -> PluginErrors.invalidFile
            Semver.parse(info.version) == null -> PluginErrors.invalidVersion
            info.kinds.isEmpty() || !PluginLimits.SUPPORTED_KINDS.containsAll(info.kinds) -> PluginErrors.kindUnsupported
            declaredHosts(json).any { !HostPolicy.isPublic(it) } -> PluginErrors.privateHost
            else -> AppResult.Ok(info)
        }
    }

    /** The manifest's `hosts` as written (a non-string entry counts as an invalid host). */
    private fun declaredHosts(json: JsonObject): List<String> =
        (json["hosts"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }

    /** Problem of a repository entry, read from its fields only (null when it looks installable). */
    fun entryProblem(
        contractVersion: Int,
        kinds: List<String>,
    ): String? =
        when {
            contractVersion > RULES_CONTRACT_VERSION -> "Needs a newer version of vovoscan."
            kinds.isEmpty() || !PluginLimits.SUPPORTED_KINDS.containsAll(kinds) -> "This kind of content isn't supported yet."
            else -> null
        }
}
