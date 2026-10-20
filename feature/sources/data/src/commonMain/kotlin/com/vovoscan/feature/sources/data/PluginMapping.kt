package com.vovoscan.feature.sources.data

import com.vovoscan.core.sources.ManifestInfo
import com.vovoscan.core.sources.ManifestSetting
import com.vovoscan.core.sources.SourceManifest
import com.vovoscan.feature.sources.domain.PluginInfo
import com.vovoscan.feature.sources.domain.SettingField
import com.vovoscan.feature.sources.domain.SettingType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun ManifestSetting.toField() = SettingField(name, label, SettingType.fromManifest(type), required, default, help)

/** What the manifest says, for the preview; hosts include those of the url settings' default values. */
internal fun ManifestInfo.toInfo(): PluginInfo {
    val defaults = settings.mapNotNull { s -> s.default?.let { s.name to it } }.toMap()
    return PluginInfo(
        key = key,
        name = name,
        version = version,
        summary = summary,
        kinds = kinds,
        langs = langs,
        rightsBasis = rightsBasis,
        rightsNotes = rightsNotes,
        hosts = SourceManifest(key, json, defaults).allowedHosts.sorted(),
        settings = settings.map { it.toField() },
    )
}

/** Body of `PUT /plugins/{key}`, without `updatedAt` (added when it is sent). */
internal fun pluginPayload(source: StoredSource): String =
    buildJsonObject {
        put("manifest", source.manifest ?: JsonObject(emptyMap()))
        put("settings", JsonObject(source.settings.mapValues { JsonPrimitive(it.value) }))
        put("origin", source.origin.apiValue)
        put("originUrl", source.originUrl?.let(::JsonPrimitive) ?: JsonNull)
        put("repositoryId", source.repositoryId?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

/** Body of `PUT /plugin-repositories/{id}`, without `updatedAt`. */
internal fun repositoryPayload(
    url: String,
    name: String,
): String =
    buildJsonObject {
        put("url", url)
        put("name", name)
    }.toString()
