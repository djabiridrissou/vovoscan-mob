package com.vovoscan.core.sources

import com.vovoscan.core.common.HostPolicy
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

    /**
     * `content.comic.referer` rendered with the settings and the device's language: the Referer sent with this
     * source's page images (and only to its allowed hosts). Null when the manifest declares none, or when it does not
     * render to an http(s) address.
     */
    val imageReferer: String?
        get() {
            val template =
                json["content"]
                    ?.jsonObject
                    ?.get("comic")
                    ?.jsonObject
                    ?.get("referer")
                    ?.jsonPrimitive
                    ?.contentOrNull ?: return null
            val rendered = Template.render(template, mapOf("settings" to settings, "lang" to deviceLanguage())).trim()
            return rendered.takeIf { hostOf(it) != null && (it.startsWith("https://") || it.startsWith("http://")) }
        }

    /** Hosts the device may contact: those in the manifest and those from url-type settings. */
    val allowedHosts: Set<String>
        get() {
            val fixed = json["hosts"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }.orEmpty()
            val specs = json["settings"]?.jsonObject.orEmpty()
            val fromSettings =
                specs.mapNotNull { (name, spec) ->
                    if (spec.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "url") settings[name]?.let(::hostOf) else null
                }
            // Only public hosts, whatever the manifest or a setting says (HostPolicy).
            return (fixed + fromSettings).filter(HostPolicy::isPublic).toSet()
        }
}

/** Provides the manifest of an active rule-based source; null for the internal source (read through the vovoscan API). */
fun interface ManifestProvider {
    suspend fun manifest(key: String): SourceManifest?
}

internal fun hostOf(url: String): String? = HostPolicy.hostOf(url)
