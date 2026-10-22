package com.vovoscan.feature.catalog.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.catalog.data.ApiCatalogRemoteDataSource
import com.vovoscan.feature.catalog.data.ApiSearchRemoteDataSource
import com.vovoscan.feature.catalog.data.CatalogLocalDataSource
import com.vovoscan.feature.catalog.data.CatalogRemoteDataSource
import com.vovoscan.feature.catalog.data.CatalogRepositoryImpl
import com.vovoscan.feature.catalog.data.OnDeviceCatalogRemoteDataSource
import com.vovoscan.feature.catalog.data.RoutingCatalogRemoteDataSource
import com.vovoscan.feature.catalog.data.SearchRepositoryImpl
import com.vovoscan.feature.catalog.data.SqlCatalogLocalDataSource
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.ChapterTranslations
import com.vovoscan.feature.catalog.domain.EpisodeFeature
import com.vovoscan.feature.catalog.domain.SearchRepository
import com.vovoscan.feature.catalog.presentation.CatalogViewModel
import com.vovoscan.feature.catalog.presentation.SearchPrivacyViewModel
import com.vovoscan.feature.catalog.presentation.TitleDetailViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin module of the feature: local (SQLDelight) and remote (Ktor) sources injected into the repository.
 * Expects from the app: `HttpClient`, `DatabaseDriverFactory`, `AppDispatchers`, `ManifestProvider` and `RulesHttp`.
 */
val catalogModule =
    module {
        single { CatalogDatabase(get<DatabaseDriverFactory>().create(CatalogDatabase.Schema, "catalog.db")) }
        // Internal source through the API; on-device sources through the rules engine (separate HTTP client).
        single<CatalogRemoteDataSource> {
            RoutingCatalogRemoteDataSource(get(), ApiCatalogRemoteDataSource(get()), OnDeviceCatalogRemoteDataSource(get(), get()))
        }
        single<CatalogLocalDataSource> { SqlCatalogLocalDataSource(get(), get<AppDispatchers>().io) }
        single<CatalogRepository> { CatalogRepositoryImpl(get(), get()) }
        // Search learned from picks (server `search`); picks wait in the catalog's database while offline.
        single<SearchRepository> { SearchRepositoryImpl(ApiSearchRemoteDataSource(get()), get(), get<AppDispatchers>().io) }
        viewModelOf(::CatalogViewModel)
        viewModelOf(::SearchPrivacyViewModel)
        viewModel { (sourceKey: String, titleId: String) ->
            TitleDetailViewModel(
                sourceKey,
                titleId,
                get(),
                get(),
                getOrNull() ?: ChapterTranslations.None,
                getOrNull() ?: EpisodeFeature.Off,
            )
        }
    }
