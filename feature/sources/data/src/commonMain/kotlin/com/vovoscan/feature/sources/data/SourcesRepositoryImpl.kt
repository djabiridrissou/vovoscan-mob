package com.vovoscan.feature.sources.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.isoUtc
import com.vovoscan.core.sources.ManifestCheck
import com.vovoscan.core.sources.ManifestInfo
import com.vovoscan.core.sources.PluginErrors
import com.vovoscan.core.sources.PluginLimits
import com.vovoscan.core.sources.PluginLinks
import com.vovoscan.core.sources.RepositoryIndex
import com.vovoscan.core.sources.RepositoryIndexEntry
import com.vovoscan.core.sources.Semver
import com.vovoscan.core.sources.VovoPackageReader
import com.vovoscan.core.sources.sha256Hex
import com.vovoscan.feature.sources.domain.EntryStatus
import com.vovoscan.feature.sources.domain.PluginCandidate
import com.vovoscan.feature.sources.domain.PluginLookup
import com.vovoscan.feature.sources.domain.PluginsRepository
import com.vovoscan.feature.sources.domain.RepositoryEntry
import com.vovoscan.feature.sources.domain.RepositoryListing
import com.vovoscan.feature.sources.domain.SettingsRules
import com.vovoscan.feature.sources.domain.SourceItem
import com.vovoscan.feature.sources.domain.SourceOrigin
import com.vovoscan.feature.sources.domain.SourceRemovedListener
import com.vovoscan.feature.sources.domain.SourceRepositoryItem
import com.vovoscan.feature.sources.domain.SourcesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * My sources and my third-party sources, offline first. Every write applies on the device at once and is queued
 * (choices in `pending_choices`, plugins and repositories in `plugin_outbox`); a sync sends the queue first, then
 * replaces the local list with the server's. One sync at a time.
 */
class SourcesRepositoryImpl(
    private val remote: SourcesRemoteDataSource,
    private val local: SourcesLocalDataSource,
    private val files: PluginFilesRemoteDataSource,
    private val clock: Clock,
    private val removedListener: SourceRemovedListener,
    private val packages: VovoPackageReader = VovoPackageReader(),
    private val allowLocal: Boolean = false,
) : SourcesRepository,
    PluginsRepository {
    private val syncing = Mutex()

    /** Last index read for each saved repository: installing from a listing does not fetch it again. */
    private val indexes = mutableMapOf<String, RepositoryIndex>()

    override fun observe(): Flow<List<SourceItem>> = local.observe()

    override fun observeRepositories(): Flow<List<SourceRepositoryItem>> = local.observeRepositories()

    override suspend fun refresh(): AppResult<Unit> = syncing.withLock { sync().result }

    override suspend fun save(choices: List<Pair<String, Boolean>>): AppResult<Unit> {
        local.applyPending(choices.map { (key, enabled) -> SourceChoiceDto(key, enabled) })
        return refresh()
    }

    override suspend fun clearLocal() {
        indexes.clear()
        local.clear()
    }

    private class SyncOutcome(
        val result: AppResult<Unit>,
        /** Writes the server refused for good (dropped from the queue), by (target, id). */
        val refused: Map<Pair<String, String>, AppError> = emptyMap(),
    )

    private fun AppError.isTransient() = this == AppError.Network || this == AppError.Unauthorized

    private suspend fun sync(): SyncOutcome {
        val refused = mutableMapOf<Pair<String, String>, AppError>()
        // Plugin and repository writes first: the choices may name a plugin the server does not know yet.
        for (op in local.outbox()) {
            val result = send(op)
            when {
                result is AppResult.Ok -> local.dequeue(op)
                result is AppResult.Err && result.error.isTransient() -> return SyncOutcome(result, refused)
                result is AppResult.Err -> {
                    local.dequeue(op)
                    refused[op.target to op.id] = result.error
                }
            }
        }
        // A choice made offline is sent next, otherwise the server's list would overwrite it.
        var list: List<MySourceDto>? = null
        local.pending()?.let { pending ->
            when (val sent = remote.save(pending)) {
                is AppResult.Ok -> {
                    local.clearPending()
                    list = sent.value
                }
                is AppResult.Err -> if (sent.error.isTransient()) return SyncOutcome(sent, refused) else local.clearPending()
            }
        }
        val sources =
            list ?: when (val mine = remote.mine()) {
                is AppResult.Ok -> mine.value
                is AppResult.Err -> return SyncOutcome(mine, refused)
            }
        replaceSources(sources)
        when (val plugins = remote.plugins()) {
            is AppResult.Ok -> local.replaceRepositories(plugins.value.repositories)
            // An API without the plugins module yet: the sources are still up to date.
            is AppResult.Err -> if (plugins.error == AppError.Network) return SyncOutcome(plugins, refused)
        }
        return SyncOutcome(AppResult.Ok(Unit), refused)
    }

    private suspend fun send(op: PluginOp): AppResult<Unit> {
        val at = isoUtc(op.updatedAt)
        return when {
            op.target == PluginOp.PLUGIN && op.isDelete -> remote.deletePlugin(op.id, at)
            op.target == PluginOp.PLUGIN -> remote.putPlugin(op.id, body(op.payload, at))
            op.isDelete -> remote.deleteRepository(op.id, at)
            else -> remote.putRepository(op.id, body(op.payload, at))
        }
    }

    private fun body(
        payload: String?,
        updatedAt: String,
    ): JsonObject = JsonObject(payload?.let(::parseObject).orEmpty() + ("updatedAt" to JsonPrimitive(updatedAt)))

    /** Sources that left the list (removed on another device, deactivated…) get their caches cleared. */
    private suspend fun replaceSources(sources: List<MySourceDto>) {
        val before = local.keys()
        local.replace(sources)
        (before - sources.map { it.key }.toSet()).forEach { removedListener.sourceRemoved(it) }
    }

    /** Applies a write locally and queues it, then syncs; only a definitive refusal of that write is an error. */
    private suspend fun write(
        target: String,
        id: String,
        apply: suspend () -> Unit,
    ): AppResult<Unit> =
        syncing.withLock {
            apply()
            val outcome = sync()
            outcome.refused[target to id]?.let { AppResult.Err(it) } ?: AppResult.Ok(Unit)
        }

    private suspend fun enqueue(
        target: String,
        id: String,
        kind: String,
        payload: String?,
    ) = local.enqueue(PluginOp(target, id, kind, payload, clock.nowMillis()))

    // --- Adding ---

    override suspend fun open(link: String): AppResult<PluginLookup> {
        val url = PluginLinks.resolve(link, allowLocal) ?: return PluginErrors.linkNotAllowed
        val fetched =
            when (val result = files.fetch(url, PluginLimits.REPOSITORY_MAX_BYTES)) {
                is AppResult.Err -> return result
                is AppResult.Ok -> result.value
            }
        val json = parseObject(fetched.body.decodeToString()) ?: return PluginErrors.notASource
        return when {
            RepositoryIndex.looksLikeIndex(json) -> {
                val index =
                    when (val parsed = RepositoryIndex.parse(json, fetched.finalUrl, allowLocal)) {
                        is AppResult.Err -> return parsed
                        is AppResult.Ok -> parsed.value.copy(url = url)
                    }
                indexes[index.id] = index
                val saved =
                    write(PluginOp.REPOSITORY, index.id) {
                        local.putRepository(index.id, url, index.name)
                        local.setRepositoryCount(index.id, index.sources.size)
                        enqueue(PluginOp.REPOSITORY, index.id, PluginOp.PUT, repositoryPayload(url, index.name))
                    }
                if (saved is AppResult.Err) return saved
                AppResult.Ok(PluginLookup.Repository(listing(index)))
            }
            "contractVersion" in json && "key" in json -> {
                if (fetched.body.size > PluginLimits.MANIFEST_MAX_BYTES) return PluginErrors.tooLarge
                when (val checked = ManifestCheck.check(json)) {
                    is AppResult.Err -> checked
                    is AppResult.Ok -> AppResult.Ok(PluginLookup.Single(candidate(checked.value, SourceOrigin.Link, url, null, null)))
                }
            }
            else -> PluginErrors.notASource
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun openFile(
        bytes: ByteArray,
        fileName: String,
    ): AppResult<PluginCandidate> {
        val rawJson = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() } == '{'.code.toByte()
        var icon: ByteArray? = null
        val manifest =
            if (rawJson) {
                bytes
            } else {
                when (val read = packages.read(bytes)) {
                    is AppResult.Err -> return read
                    is AppResult.Ok -> read.value.also { icon = it.icon }.manifest
                }
            }
        return when (val checked = ManifestCheck.parse(manifest)) {
            is AppResult.Err -> checked
            is AppResult.Ok -> {
                val iconUrl = icon?.let { "data:image/png;base64," + Base64.encode(it) }
                AppResult.Ok(candidate(checked.value, SourceOrigin.File, null, null, iconUrl))
            }
        }
    }

    override suspend fun browse(repositoryId: String): AppResult<RepositoryListing> {
        val repository = local.repository(repositoryId) ?: return unknownRepository()
        return when (val index = fetchIndex(repository.url)) {
            is AppResult.Err -> index
            is AppResult.Ok -> {
                val kept = index.value.copy(id = repositoryId)
                indexes[repositoryId] = kept
                local.setRepositoryCount(repositoryId, kept.sources.size)
                AppResult.Ok(listing(kept))
            }
        }
    }

    override suspend fun candidate(
        repositoryId: String,
        key: String,
    ): AppResult<PluginCandidate> {
        val index =
            indexes[repositoryId] ?: run {
                val repository = local.repository(repositoryId) ?: return unknownRepository()
                when (val fetched = fetchIndex(repository.url)) {
                    is AppResult.Err -> return fetched
                    is AppResult.Ok -> fetched.value.copy(id = repositoryId).also { indexes[repositoryId] = it }
                }
            }
        val entry =
            index.sources.firstOrNull { it.key == key }
                ?: return PluginErrors.rejected("PLUGIN_NOT_FOUND", "This source isn't in the repository anymore.")
        return when (val verified = verifiedEntry(entry)) {
            is AppResult.Err -> verified
            is AppResult.Ok -> AppResult.Ok(candidate(verified.value, SourceOrigin.Repository, index.url, index.id, entry.iconUrl))
        }
    }

    override suspend fun updateCandidate(key: String): AppResult<PluginCandidate> {
        val installed = local.source(key)?.takeIf { it.origin.thirdParty } ?: return notThirdParty()
        val extra = local.extra(key)
        val json = extra?.updateManifest?.let(::parseObject) ?: return PluginErrors.rejected("PLUGIN_NO_UPDATE", "No update available.")
        return when (val checked = ManifestCheck.check(json)) {
            is AppResult.Err -> checked
            is AppResult.Ok ->
                AppResult.Ok(
                    candidate(checked.value, installed.origin, installed.originUrl, installed.repositoryId, extra.iconUrl),
                )
        }
    }

    private suspend fun fetchIndex(url: String): AppResult<RepositoryIndex> =
        when (val fetched = files.fetch(url, PluginLimits.REPOSITORY_MAX_BYTES)) {
            is AppResult.Err -> fetched
            is AppResult.Ok ->
                when (val parsed = RepositoryIndex.parse(fetched.value.body, fetched.value.finalUrl, allowLocal)) {
                    is AppResult.Err -> parsed
                    is AppResult.Ok -> AppResult.Ok(parsed.value.copy(url = url))
                }
        }

    /** The entry's manifest, fetched and checked against the entry: sha256 first, then key, version and contract. */
    private suspend fun verifiedEntry(entry: RepositoryIndexEntry): AppResult<ManifestInfo> {
        val bytes =
            when (val fetched = files.fetch(entry.manifestUrl, PluginLimits.MANIFEST_MAX_BYTES)) {
                is AppResult.Err -> return fetched
                is AppResult.Ok -> fetched.value.body
            }
        if (sha256Hex(bytes) != entry.sha256) return PluginErrors.checksum
        return when (val checked = ManifestCheck.parse(bytes)) {
            is AppResult.Err -> checked
            is AppResult.Ok -> {
                val info = checked.value
                if (info.key != entry.key || info.version != entry.version || info.contractVersion != entry.contractVersion) {
                    PluginErrors.mismatch
                } else {
                    checked
                }
            }
        }
    }

    private suspend fun candidate(
        info: ManifestInfo,
        origin: SourceOrigin,
        originUrl: String?,
        repositoryId: String?,
        iconUrl: String?,
    ): PluginCandidate {
        val installed = local.source(info.key)
        val official = installed != null && !installed.origin.thirdParty
        val blocker =
            when {
                official -> OFFICIAL_CONFLICT
                installed != null && Semver.isNewer(installed.version, info.version) -> NEWER_INSTALLED
                else -> null
            }
        val installedHosts =
            installed
                ?.manifest
                ?.let { ManifestInfo(it).hosts }
                .orEmpty()
                .toSet()
        return PluginCandidate(
            info = info.toInfo(),
            manifestJson = info.json.toString(),
            origin = origin,
            originUrl = originUrl,
            repositoryId = repositoryId,
            iconUrl = iconUrl,
            installedVersion = installed?.takeIf { !official }?.version,
            newHosts = if (installed == null || official) emptyList() else info.hosts.filter { it !in installedHosts },
            blocker = blocker,
        )
    }

    private suspend fun listing(index: RepositoryIndex): RepositoryListing =
        RepositoryListing(
            id = index.id,
            name = index.name,
            url = index.url,
            homepage = index.homepage,
            entries =
                index.sources.map { entry ->
                    val installed = local.source(entry.key)
                    val problem =
                        ManifestCheck.entryProblem(entry.contractVersion, entry.kinds)
                            ?: OFFICIAL_CONFLICT.takeIf { installed != null && !installed.origin.thirdParty }
                    val status =
                        when {
                            problem != null -> EntryStatus.Unavailable
                            installed == null -> EntryStatus.Install
                            Semver.isNewer(entry.version, installed.version) -> EntryStatus.Update
                            else -> EntryStatus.Installed
                        }
                    RepositoryEntry(
                        key = entry.key,
                        name = entry.name,
                        version = entry.version,
                        kinds = entry.kinds,
                        langs = entry.langs,
                        rightsBasis = entry.rights,
                        iconUrl = entry.iconUrl,
                        changes = entry.changes,
                        status = status,
                        installedVersion = installed?.takeIf { it.origin.thirdParty }?.version,
                        problem = problem,
                    )
                },
        )

    // --- Installing, changing, removing ---

    override suspend fun install(
        candidate: PluginCandidate,
        settings: Map<String, String>,
    ): AppResult<Unit> {
        candidate.blocker?.let { return PluginErrors.rejected("PLUGIN_BLOCKED", it) }
        val json = parseObject(candidate.manifestJson) ?: return PluginErrors.invalidFile
        val info =
            when (val checked = ManifestCheck.check(json)) {
                is AppResult.Err -> return checked
                is AppResult.Ok -> checked.value
            }
        val installed = local.source(info.key)
        if (installed != null && !installed.origin.thirdParty) return PluginErrors.rejected("PLUGIN_KEY_TAKEN", OFFICIAL_CONFLICT)
        val spec = info.settings.map { it.toField() }
        val names = spec.map { it.name }.toSet()
        val values = SettingsRules.merge(spec, installed?.settings.orEmpty(), settings).filterKeys { it in names }
        if (SettingsRules.validate(spec, values).isNotEmpty()) return settingsInvalid()
        val source =
            StoredSource(
                key = info.key,
                name = info.name,
                engine = "rules",
                version = info.version,
                kinds = info.kinds,
                langs = info.langs,
                enabled = installed?.enabled ?: true,
                position = installed?.position ?: local.nextPosition(),
                manifest = json,
                settings = values,
                origin = candidate.origin,
                originUrl = candidate.originUrl,
                repositoryId = candidate.repositoryId,
                summary = info.summary,
            )
        return write(PluginOp.PLUGIN, info.key) {
            local.putSource(source)
            candidate.iconUrl?.let { local.setIcon(info.key, it) }
            local.extra(info.key)?.updateVersion?.let { pending ->
                if (!Semver.isNewer(pending, info.version)) local.setUpdate(info.key, null, null, null)
            }
            enqueue(PluginOp.PLUGIN, info.key, PluginOp.PUT, pluginPayload(source))
        }
    }

    override suspend fun saveSettings(
        key: String,
        settings: Map<String, String>,
    ): AppResult<Unit> {
        val installed = local.source(key)?.takeIf { it.origin.thirdParty } ?: return notThirdParty()
        val spec =
            installed.manifest
                ?.let(::ManifestInfo)
                ?.settings
                ?.map { it.toField() }
                .orEmpty()
        val names = spec.map { it.name }.toSet()
        val values = SettingsRules.merge(spec, installed.settings, settings).filterKeys { it in names }
        if (SettingsRules.validate(spec, values).isNotEmpty()) return settingsInvalid()
        return write(PluginOp.PLUGIN, key) {
            local.setSettings(key, values)
            enqueue(PluginOp.PLUGIN, key, PluginOp.PUT, pluginPayload(installed.copy(settings = values)))
        }
    }

    override suspend fun remove(key: String): AppResult<Unit> {
        val installed = local.source(key) ?: return AppResult.Ok(Unit)
        if (!installed.origin.thirdParty) {
            return PluginErrors.rejected("SOURCE_OFFICIAL", "Official sources can't be removed. You can hide them.")
        }
        return write(PluginOp.PLUGIN, key) {
            local.deleteSource(key)
            local.dropFromPending(key)
            enqueue(PluginOp.PLUGIN, key, PluginOp.DELETE, null)
            removedListener.sourceRemoved(key)
        }
    }

    override suspend fun removeRepository(id: String): AppResult<Unit> =
        write(PluginOp.REPOSITORY, id) {
            indexes.remove(id)
            local.deleteRepository(id)
            enqueue(PluginOp.REPOSITORY, id, PluginOp.DELETE, null)
        }

    // --- Updates ---

    override suspend fun checkUpdates(force: Boolean): AppResult<Unit> {
        val now = clock.nowMillis()
        if (!force) local.lastUpdateCheck()?.let { if (now - it < UPDATE_CHECK_INTERVAL_MS) return AppResult.Ok(Unit) }
        var offline = false
        val plugins = local.userPlugins()
        val repositories = local.repositories()
        val indexUrls =
            (repositories.map { it.url } + plugins.filter { it.origin == SourceOrigin.Repository }.mapNotNull { it.originUrl }).distinct()
        for (url in indexUrls) {
            when (val fetched = fetchIndex(url)) {
                is AppResult.Err -> if (fetched.error == AppError.Network) offline = true
                is AppResult.Ok -> {
                    val index = fetched.value
                    repositories.filter { it.url == url }.forEach {
                        indexes[it.id] = index.copy(id = it.id)
                        local.setRepositoryCount(it.id, index.sources.size)
                    }
                    plugins
                        .filter { it.origin == SourceOrigin.Repository && it.originUrl == url }
                        .forEach { plugin -> index.sources.firstOrNull { it.key == plugin.key }?.let { checkEntry(plugin, it) } }
                }
            }
        }
        plugins.filter { it.origin == SourceOrigin.Link && it.originUrl != null }.forEach { plugin ->
            if (checkLink(plugin) == AppError.Network) offline = true
        }
        if (offline) return AppResult.Err(AppError.Network)
        local.setLastUpdateCheck(now)
        return AppResult.Ok(Unit)
    }

    private suspend fun checkEntry(
        plugin: StoredSource,
        entry: RepositoryIndexEntry,
    ) {
        entry.iconUrl?.let { local.setIcon(plugin.key, it) }
        val known = local.extra(plugin.key)?.updateVersion
        if (!Semver.isNewer(entry.version, plugin.version)) {
            if (known != null) local.setUpdate(plugin.key, null, null, null)
            return
        }
        if (known == entry.version) return
        val verified = verifiedEntry(entry) as? AppResult.Ok ?: return
        local.setUpdate(plugin.key, entry.version, entry.changes, verified.value.json.toString())
    }

    private suspend fun checkLink(plugin: StoredSource): AppError? {
        val url = plugin.originUrl ?: return null
        val bytes =
            when (val fetched = files.fetch(url, PluginLimits.MANIFEST_MAX_BYTES)) {
                is AppResult.Err -> return fetched.error
                is AppResult.Ok -> fetched.value.body
            }
        val info = (ManifestCheck.parse(bytes) as? AppResult.Ok)?.value?.takeIf { it.key == plugin.key } ?: return null
        val known = local.extra(plugin.key)?.updateVersion
        when {
            Semver.isNewer(info.version, plugin.version) ->
                if (known != info.version) local.setUpdate(plugin.key, info.version, null, info.json.toString())
            known != null -> local.setUpdate(plugin.key, null, null, null)
        }
        return null
    }

    private fun unknownRepository(): AppResult.Err = PluginErrors.rejected("REPOSITORY_UNKNOWN", "This repository isn't saved anymore.")

    private fun notThirdParty(): AppResult.Err = PluginErrors.rejected("SOURCE_OFFICIAL", "Only third-party sources can be changed here.")

    private fun settingsInvalid(): AppResult.Err = PluginErrors.rejected("SETTINGS_INVALID", "Check the settings.")

    companion object {
        const val OFFICIAL_CONFLICT = "Already available as an official source."
        const val NEWER_INSTALLED = "A newer version is already installed."
        const val UPDATE_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
}
