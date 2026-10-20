package com.vovoscan.core.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Version du contrat des sources que ce moteur sait lire (schéma du dépôt vovo-plugin). */
const val RULES_CONTRACT_VERSION = 1

/**
 * Une source à règles telle que le serveur la distribue : son manifeste (contrat v1) et les valeurs effectives de ses
 * réglages, fixées par un admin. L'appareil l'interprète ; son contenu ne passe jamais par vovoscan.
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

    /** Hôtes que l'appareil a le droit de contacter : ceux du manifeste et ceux des réglages de type url. */
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

/** Fournit le manifeste d'une source à règles active ; null pour la source interne (lue par l'API vovoscan). */
fun interface ManifestProvider {
    suspend fun manifest(key: String): SourceManifest?
}

internal fun hostOf(url: String): String? =
    Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)")
        .find(url.trim())
        ?.groupValues
        ?.get(1)
        ?.lowercase()
