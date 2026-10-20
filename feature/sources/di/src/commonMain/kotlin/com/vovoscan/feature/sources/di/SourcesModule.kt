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
 * Module Koin de la feature : sources locale (SQLDelight, manifestes compris) et distante (Ktor) injectées dans le
 * dépôt. Fournit aussi `ManifestProvider` au catalogue et au lecteur (sources lues sur l'appareil).
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
