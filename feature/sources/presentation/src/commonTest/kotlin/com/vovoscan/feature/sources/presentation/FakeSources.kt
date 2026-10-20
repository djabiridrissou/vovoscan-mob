package com.vovoscan.feature.sources.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.sources.domain.EntryStatus
import com.vovoscan.feature.sources.domain.PluginCandidate
import com.vovoscan.feature.sources.domain.PluginInfo
import com.vovoscan.feature.sources.domain.PluginLookup
import com.vovoscan.feature.sources.domain.PluginsRepository
import com.vovoscan.feature.sources.domain.RepositoryEntry
import com.vovoscan.feature.sources.domain.RepositoryListing
import com.vovoscan.feature.sources.domain.SettingField
import com.vovoscan.feature.sources.domain.SettingType
import com.vovoscan.feature.sources.domain.SettingsRules
import com.vovoscan.feature.sources.domain.SourceEngine
import com.vovoscan.feature.sources.domain.SourceItem
import com.vovoscan.feature.sources.domain.SourceOrigin
import com.vovoscan.feature.sources.domain.SourceRepositoryItem
import com.vovoscan.feature.sources.domain.SourceUpdate
import com.vovoscan.feature.sources.domain.SourcesRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory sources and plugins for the ViewModel tests. */
internal class FakeSources :
    SourcesRepository,
    PluginsRepository {
    val items =
        MutableStateFlow(
            listOf(
                SourceItem("vovoscan", "vovoscan", SourceEngine.Internal, "1", listOf("comic", "book"), listOf("en"), true, 0, false),
                SourceItem("gutenberg", "Project Gutenberg", SourceEngine.OnDevice, "1", listOf("book"), listOf("en"), false, 1, false),
            ),
        )
    val repositories =
        MutableStateFlow(listOf(SourceRepositoryItem("org.demo", "Demo plugins", "https://demo.example/repo.json", 2, false)))
    val installs = mutableListOf<Pair<String, Map<String, String>>>()
    val removedKeys = mutableListOf<String>()
    var checks = mutableListOf<Boolean>()
    var lookup: AppResult<PluginLookup> = AppResult.Err(AppError.Network)
    val candidates = mutableMapOf<String, PluginCandidate>()

    override fun observe() = items

    override suspend fun refresh() = AppResult.Ok(Unit)

    override suspend fun save(choices: List<Pair<String, Boolean>>): AppResult<Unit> {
        val byKey = items.value.associateBy { it.key }
        items.value = choices.mapIndexed { i, (k, e) -> byKey.getValue(k).copy(enabled = e, position = i) }
        return AppResult.Ok(Unit)
    }

    override suspend fun clearLocal() = Unit

    override fun observeRepositories() = repositories

    override suspend fun open(link: String) = lookup

    override suspend fun openFile(
        bytes: ByteArray,
        fileName: String,
    ): AppResult<PluginCandidate> =
        (lookup as? AppResult.Ok)?.let { AppResult.Ok((it.value as PluginLookup.Single).candidate) } ?: AppResult.Err(AppError.Network)

    override suspend fun browse(repositoryId: String) =
        (lookup as? AppResult.Ok)?.let { AppResult.Ok((it.value as PluginLookup.Repository).listing) } ?: AppResult.Err(AppError.Network)

    override suspend fun candidate(
        repositoryId: String,
        key: String,
    ): AppResult<PluginCandidate> = candidates[key]?.let { AppResult.Ok(it) } ?: AppResult.Err(AppError.Network)

    override suspend fun updateCandidate(key: String): AppResult<PluginCandidate> =
        candidates[key]?.let { AppResult.Ok(it) } ?: AppResult.Err(AppError.Rejected("PLUGIN_NO_UPDATE", "No update available."))

    override suspend fun install(
        candidate: PluginCandidate,
        settings: Map<String, String>,
    ): AppResult<Unit> {
        installs += candidate.info.key to settings
        val current = items.value.firstOrNull { it.key == candidate.info.key }
        val item =
            SourceItem(
                key = candidate.info.key,
                name = candidate.info.name,
                engine = SourceEngine.OnDevice,
                version = candidate.info.version,
                kinds = candidate.info.kinds,
                langs = candidate.info.langs,
                enabled = true,
                position = items.value.size,
                pendingSync = false,
                origin = candidate.origin,
                hosts = candidate.info.hosts,
                settingsSpec = candidate.info.settings,
                settings = SettingsRules.merge(candidate.info.settings, current?.settings.orEmpty(), settings),
            )
        items.value = items.value.filter { it.key != item.key } + item
        return AppResult.Ok(Unit)
    }

    override suspend fun saveSettings(
        key: String,
        settings: Map<String, String>,
    ): AppResult<Unit> {
        items.value = items.value.map { if (it.key == key) it.copy(settings = it.settings + settings) else it }
        return AppResult.Ok(Unit)
    }

    override suspend fun remove(key: String): AppResult<Unit> {
        removedKeys += key
        items.value = items.value.filter { it.key != key }
        return AppResult.Ok(Unit)
    }

    override suspend fun removeRepository(id: String): AppResult<Unit> {
        repositories.value = repositories.value.filter { it.id != id }
        return AppResult.Ok(Unit)
    }

    override suspend fun checkUpdates(force: Boolean): AppResult<Unit> {
        checks += force
        return AppResult.Ok(Unit)
    }

    companion object {
        val baseUrl =
            SettingField("baseUrl", "Instance", SettingType.Url, required = true, defaultValue = null, help = "Your own instance.")

        fun candidate(
            key: String = "demo",
            version: String = "1.0.0",
            settings: List<SettingField> = emptyList(),
            hosts: List<String> = listOf("demo.example"),
            installedVersion: String? = null,
            newHosts: List<String> = emptyList(),
            blocker: String? = null,
        ) = PluginCandidate(
            info =
                PluginInfo(
                    key,
                    "Demo $key",
                    version,
                    "A demo source.",
                    listOf("comic"),
                    listOf("en", "fr"),
                    "public-domain",
                    "Notes.",
                    hosts,
                    settings,
                ),
            manifestJson = "{}",
            origin = SourceOrigin.Repository,
            originUrl = "https://demo.example/repo.json",
            repositoryId = "org.demo",
            iconUrl = null,
            installedVersion = installedVersion,
            newHosts = newHosts,
            blocker = blocker,
        )

        fun entry(
            key: String,
            status: EntryStatus,
            installedVersion: String? = null,
            version: String = "1.0.0",
        ) = RepositoryEntry(
            key,
            "Demo $key",
            version,
            listOf("comic"),
            listOf("en"),
            "public-domain",
            null,
            null,
            status,
            installedVersion,
            null,
        )

        fun thirdParty(
            key: String = "demo",
            update: SourceUpdate? = null,
            settings: Map<String, String> = mapOf("baseUrl" to "https://one.example"),
        ) = SourceItem(
            key = key,
            name = "Demo $key",
            engine = SourceEngine.OnDevice,
            version = "1.0.0",
            kinds = listOf("comic"),
            langs = listOf("en"),
            enabled = true,
            position = 5,
            pendingSync = false,
            origin = SourceOrigin.Repository,
            rightsBasis = "licensed",
            rightsNotes = "Licensed by the author.",
            hosts = listOf("demo.example"),
            settingsSpec = listOf(baseUrl),
            settings = settings,
            repositoryId = "org.demo",
            repositoryName = "Demo plugins",
            update = update,
        )

        fun listing(vararg entries: RepositoryEntry) =
            RepositoryListing("org.demo", "Demo plugins", "https://demo.example/repo.json", null, entries.toList())
    }
}
