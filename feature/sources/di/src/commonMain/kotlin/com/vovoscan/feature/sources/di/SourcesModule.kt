package com.vovoscan.feature.sources.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.feature.sources.data.KtorSourcesRemoteDataSource
import com.vovoscan.feature.sources.data.SourcesLocalDataSource
import com.vovoscan.feature.sources.data.SourcesRemoteDataSource
import com.vovoscan.feature.sources.data.SourcesRepositoryImpl
import com.vovoscan.feature.sources.data.SqlSourcesLocalDataSource
import com.vovoscan.feature.sources.data.db.SourcesDatabase
import com.vovoscan.feature.sources.domain.SourcesRepository
import com.vovoscan.feature.sources.presentation.SourcesViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin module for the feature: local (SQLDelight, manifests included) and remote (Ktor) sources injected into the
 * repository. Also provides `ManifestProvider` to the catalog and the reader (sources read on the device).
 */
val sourcesModule =
    module {
        single { SourcesDatabase(get<DatabaseDriverFactory>().create(SourcesDatabase.Schema, "sources.db")) }
        single<SourcesRemoteDataSource> { KtorSourcesRemoteDataSource(get()) }
        single<SourcesLocalDataSource> { SqlSourcesLocalDataSource(get(), get<AppDispatchers>().io) }
        single<ManifestProvider> { get<SourcesLocalDataSource>() }
        single<SourcesRepository> { SourcesRepositoryImpl(get(), get()) }
        viewModelOf(::SourcesViewModel)
    }
