package com.vovoscan.feature.sources.domain

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.HostPolicy
import com.vovoscan.core.common.i18n.t
import kotlinx.coroutines.flow.Flow

/** Who reads the source: the vovoscan server (our titles) or the device (rules manifest, content never on our side). */
enum class SourceEngine {
    Internal,
    OnDevice,
    ;

    companion object {
        fun fromApi(value: String): SourceEngine = if (value == "internal") Internal else OnDevice
    }
}

/**
 * Where a source comes from. Official: installed by an admin on the server. The others are third-party sources (user
 * plugins), added by the user and not reviewed by vovoscan.
 */
enum class SourceOrigin {
    Official,
    Repository,
    Link,
    File,
    ;

    val thirdParty: Boolean get() = this != Official

    /** Value sent to the API (`origin` of `PUT /plugins/{key}`). */
    val apiValue: String get() = name.lowercase()

    companion object {
        fun fromApi(value: String?): SourceOrigin = entries.firstOrNull { it.apiValue == value } ?: Official
    }
}

enum class SettingType {
    Url,
    Text,
    Integer,
    ;

    companion object {
        fun fromManifest(value: String): SettingType =
            when (value) {
                "url" -> Url
                "integer" -> Integer
                else -> Text
            }
    }
}

/** A setting a source asks for (instance address, mirror, languages…). */
data class SettingField(
    val name: String,
    val label: String,
    val type: SettingType,
    val required: Boolean,
    val defaultValue: String?,
    val help: String?,
)

/** A newer version of an installed third-party source, already checked and kept on the device. */
data class SourceUpdate(
    val version: String,
    val changes: String?,
    /** Hosts the new version connects to that the installed one does not: the user confirms them first. */
    val newHosts: List<String>,
)

data class SourceItem(
    val key: String,
    val name: String,
    val engine: SourceEngine,
    val version: String,
    /** "comic", "book"… */
    val kinds: List<String>,
    val langs: List<String>,
    val enabled: Boolean,
    val position: Int,
    /** Choice made offline, not sent yet. */
    val pendingSync: Boolean,
    val origin: SourceOrigin = SourceOrigin.Official,
    /** The manifest's description (named `summary`: `description` clashes with NSObject on iOS). */
    val summary: String = "",
    /** https or `data:` URL, null when the source has no icon (show its initial). */
    val iconUrl: String? = null,
    /** "public-domain" or "licensed"; claimed by the author for a third-party source. */
    val rightsBasis: String? = null,
    val rightsNotes: String? = null,
    /** Hosts the phone connects to for this source (declared hosts and those of url settings). */
    val hosts: List<String> = emptyList(),
    /** Rule-based sources: the manifest's request cap per minute; null for the internal source. */
    val requestsPerMinute: Int? = null,
    val settingsSpec: List<SettingField> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    /** Repository index (`repo.json`) or link the source was added from. */
    val originUrl: String? = null,
    val repositoryId: String? = null,
    val repositoryName: String? = null,
    val update: SourceUpdate? = null,
) {
    val thirdParty: Boolean get() = origin.thirdParty

    /** Required settings without a value: the source cannot be used until they are filled. */
    val missingSettings: List<String> get() = SettingsRules.missing(settingsSpec, settings)
}

/** A plugin repository saved by the user (synced to their devices). */
data class SourceRepositoryItem(
    val id: String,
    val name: String,
    val url: String,
    /** Number of sources in its index, null until it was read on this device. */
    val sourceCount: Int?,
    val pendingSync: Boolean,
)

/** What a manifest says about itself, shown before install. */
data class PluginInfo(
    val key: String,
    val name: String,
    val version: String,
    val summary: String,
    val kinds: List<String>,
    val langs: List<String>,
    val rightsBasis: String?,
    val rightsNotes: String?,
    val hosts: List<String>,
    val settings: List<SettingField>,
    /** The manifest's `rateLimit.requestsPerMinute` (30 when it declares none). */
    val requestsPerMinute: Int = 30,
)

/** A source checked and ready to install (or to update an installed one). */
data class PluginCandidate(
    val info: PluginInfo,
    /** The manifest as fetched (opaque to the screens). */
    val manifestJson: String,
    val origin: SourceOrigin,
    val originUrl: String?,
    val repositoryId: String?,
    val iconUrl: String?,
    /** Version installed on this device, null when it is not installed. */
    val installedVersion: String?,
    /** Hosts not declared by the installed version (empty for a new install). */
    val newHosts: List<String>,
    /** Why it cannot be installed ("Already available as an official source."…), null when it can. */
    val blocker: String?,
) {
    val isUpdate: Boolean get() = installedVersion != null && blocker == null && installedVersion != info.version
}

enum class EntryStatus {
    Install,
    Installed,
    Update,
    Unavailable,
}

/** A source listed by a repository, with its state on this device. */
data class RepositoryEntry(
    val key: String,
    val name: String,
    val version: String,
    val kinds: List<String>,
    val langs: List<String>,
    val rightsBasis: String?,
    val iconUrl: String?,
    val changes: String?,
    val status: EntryStatus,
    val installedVersion: String?,
    /** Why it is unavailable, null otherwise. */
    val problem: String?,
)

data class RepositoryListing(
    val id: String,
    val name: String,
    val url: String,
    val homepage: String?,
    val entries: List<RepositoryEntry>,
)

/** What a link points to: a single source or a repository (saved to the user's repositories). */
sealed interface PluginLookup {
    data class Single(
        val candidate: PluginCandidate,
    ) : PluginLookup

    data class Repository(
        val listing: RepositoryListing,
    ) : PluginLookup
}

/**
 * My sources, offline first: the list kept on the device (with manifests of the sources read on the device)
 * shows at once; a change (visibility, order) applies locally, then goes to the server.
 */
interface SourcesRepository {
    fun observe(): Flow<List<SourceItem>>

    /** Sends what is queued (plugins, repositories, choices), then replaces the local list with the server's. */
    suspend fun refresh(): AppResult<Unit>

    /** All sources, in the desired order, each visible or not. */
    suspend fun save(choices: List<Pair<String, Boolean>>): AppResult<Unit>

    suspend fun clearLocal()
}

/**
 * Third-party sources (user plugins) and their repositories. Writes apply on the device at once, are queued, then
 * sent; a write the server refuses (key of an official source…) comes back as an error.
 */
interface PluginsRepository {
    fun observeRepositories(): Flow<List<SourceRepositoryItem>>

    /** Fetches a link (https; GitHub pages resolved to raw files): a source to preview or a repository to browse. */
    suspend fun open(link: String): AppResult<PluginLookup>

    /** Reads a `.vovo` package (or a raw `source.json`). */
    suspend fun openFile(
        bytes: ByteArray,
        fileName: String,
    ): AppResult<PluginCandidate>

    /** Reads a saved repository's index again. */
    suspend fun browse(repositoryId: String): AppResult<RepositoryListing>

    /** Fetches and verifies (sha256, key, version, contract) a repository entry's manifest. */
    suspend fun candidate(
        repositoryId: String,
        key: String,
    ): AppResult<PluginCandidate>

    /** The update already found for an installed source, ready to apply (works offline). */
    suspend fun updateCandidate(key: String): AppResult<PluginCandidate>

    /** Installs or updates; [settings] are merged over the current values and the defaults. */
    suspend fun install(
        candidate: PluginCandidate,
        settings: Map<String, String>,
    ): AppResult<Unit>

    suspend fun saveSettings(
        key: String,
        settings: Map<String, String>,
    ): AppResult<Unit>

    /** Removes a third-party source: gone on all devices, its local caches cleared; library entries stay. */
    suspend fun remove(key: String): AppResult<Unit>

    /** Forgets a repository; the sources installed from it stay. */
    suspend fun removeRepository(id: String): AppResult<Unit>

    /** Looks for newer versions; without [force], only if the last check is more than 12 hours old. */
    suspend fun checkUpdates(force: Boolean): AppResult<Unit>
}

/** Port: a source left the user's list (removed here or on another device); the app clears that source's caches. */
fun interface SourceRemovedListener {
    suspend fun sourceRemoved(key: String)
}

/** Settings checks shared by the install form and the detail screen. */
object SettingsRules {
    val REQUIRED: String get() = t("sources.setting.required")
    val HTTPS: String get() = t("sources.setting.https")
    val PUBLIC: String get() = t("sources.setting.public")
    val INTEGER: String get() = t("sources.setting.integer")

    fun missing(
        spec: List<SettingField>,
        values: Map<String, String>,
    ): List<String> = spec.filter { it.required && values[it.name].isNullOrBlank() }.map { it.name }

    /** Error by setting name (empty when every value is valid). */
    fun validate(
        spec: List<SettingField>,
        values: Map<String, String>,
    ): Map<String, String> =
        spec
            .mapNotNull { field ->
                val value = values[field.name]?.trim().orEmpty()
                val error =
                    when {
                        value.isEmpty() -> if (field.required) REQUIRED else null
                        field.type == SettingType.Url && !isHttpsUrl(value) -> HTTPS
                        field.type == SettingType.Url && !HostPolicy.isPublicUrl(value) -> PUBLIC
                        field.type == SettingType.Integer && value.toLongOrNull() == null -> INTEGER
                        else -> null
                    }
                error?.let { field.name to it }
            }.toMap()

    /** Values to keep: defaults, then [current], then [edits]; trimmed, blanks dropped. */
    fun merge(
        spec: List<SettingField>,
        current: Map<String, String>,
        edits: Map<String, String>,
    ): Map<String, String> {
        val defaults = spec.mapNotNull { f -> f.defaultValue?.let { f.name to it } }.toMap()
        return (defaults + current + edits).mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
    }

    private fun isHttpsUrl(value: String): Boolean = Regex("^https://[^/\\s?#]+[^\\s]*$", RegexOption.IGNORE_CASE).matches(value)
}
