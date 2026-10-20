package com.vovoscan.feature.sources.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.core.network.platformHttpEngine
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.PluginFetcher
import com.vovoscan.core.sources.PluginLinkPolicy
import com.vovoscan.core.sources.createPluginHttpClient
import com.vovoscan.feature.sources.data.KtorPluginFilesRemoteDataSource
import com.vovoscan.feature.sources.data.KtorSourcesRemoteDataSource
import com.vovoscan.feature.sources.data.PluginFilesRemoteDataSource
import com.vovoscan.feature.sources.data.SourcesLocalDataSource
import com.vovoscan.feature.sources.data.SourcesRemoteDataSource
import com.vovoscan.feature.sources.data.SourcesRepositoryImpl
import com.vovoscan.feature.sources.data.SqlSourcesLocalDataSource
import com.vovoscan.feature.sources.data.db.SourcesDatabase
import com.vovoscan.feature.sources.domain.PluginsRepository
import com.vovoscan.feature.sources.domain.SourceRemovedListener
import com.vovoscan.feature.sources.domain.SourcesRepository
import com.vovoscan.feature.sources.presentation.AddSourceViewModel
import com.vovoscan.feature.sources.presentation.SourceDetailViewModel
import com.vovoscan.feature.sources.presentation.SourcesViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Koin module for the feature: local (SQLDelight, manifests included) and remote (Ktor: our API, and plugin files
 * from third-party hosts) sources injected into the repository. Also provides `ManifestProvider` to the catalog and
 * the reader (sources read on the device, user plugins included).
 * Expects from the app: `HttpClient`, `DatabaseDriverFactory`, `AppDispatchers`, `SourceRemovedListener`, and
 * optionally `PluginLinkPolicy` (local plugin servers in debug builds).
 */
val sourcesModule =
    module {
        single { SourcesDatabase(get<DatabaseDriverFactory>().create(SourcesDatabase.Schema, "sources.db")) }
        single<SourcesRemoteDataSource> { KtorSourcesRemoteDataSource(get()) }
        single<SourcesLocalDataSource> { SqlSourcesLocalDataSource(get(), get<AppDispatchers>().io) }
        single<ManifestProvider> { get<SourcesLocalDataSource>() }
        // Plugin files: a separate client, without the vovoscan session or cookies.
        single { PluginFetcher(createPluginHttpClient(platformHttpEngine()), getOrNull<PluginLinkPolicy>() ?: PluginLinkPolicy(false)) }
        single<PluginFilesRemoteDataSource> { KtorPluginFilesRemoteDataSource(get()) }
        single {
            SourcesRepositoryImpl(
                remote = get(),
                local = get(),
                files = get(),
                clock = SystemClock,
                removedListener = get<SourceRemovedListener>(),
                allowLocal = getOrNull<PluginLinkPolicy>()?.allowLocal ?: false,
            )
        } binds arrayOf(SourcesRepository::class, PluginsRepository::class)
        viewModelOf(::SourcesViewModel)
        viewModelOf(::AddSourceViewModel)
        viewModel { (key: String) -> SourceDetailViewModel(key, get(), get()) }
    }
