package com.vovoscan.core.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Version of the source contract this engine can read (schema from the vovo-plugin repository). */
const val RULES_CONTRACT_VERSION = 1

/**
 * A rule-based source as the server distributes it: its manifest (contract v1) and the effective values of its
 * settings, set by an admin. The device interprets it; its content never goes through vovoscan.
 */
data class SourceManifest(
    val key: String,
    val json: JsonObject,
    val settings: Map<String, String>,
) {
    val name: String get() = json["name"]?.jsonPrimitive?.contentOrNull ?: key
    val contractVersion: Int? get() = json["contractVersion"]?.jsonPrimitive?.intOrNull
    val kinds: List<String> get() = json["kinds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val requestsPerMinute: Int get() =
        json["rateLimit"]
            ?.jsonObject
            ?.get("requestsPerMinute")
            ?.jsonPrimitive
            ?.int ?: 30

    /** Hosts the device may contact: those in the manifest and those from url-type settings. */
    val allowedHosts: Set<String>
        get() {
            val fixed = json["hosts"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }.orEmpty()
            val specs = json["settings"]?.jsonObject.orEmpty()
            val fromSettings =
                specs.mapNotNull { (name, spec) ->
                    if (spec.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "url") settings[name]?.let(::hostOf) else null
                }
            return (fixed + fromSettings).toSet()
        }
}

/** Provides the manifest of an active rule-based source; null for the internal source (read through the vovoscan API). */
fun interface ManifestProvider {
    suspend fun manifest(key: String): SourceManifest?
}

internal fun hostOf(url: String): String? =
    Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)")
        .find(url.trim())
        ?.groupValues
        ?.get(1)
        ?.lowercase()
