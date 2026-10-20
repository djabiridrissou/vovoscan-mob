package com.vovoscan.feature.catalog.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.catalog.data.CatalogLocalDataSource
import com.vovoscan.feature.catalog.data.CatalogRemoteDataSource
import com.vovoscan.feature.catalog.data.CatalogRepositoryImpl
import com.vovoscan.feature.catalog.data.KtorCatalogRemoteDataSource
import com.vovoscan.feature.catalog.data.SqlCatalogLocalDataSource
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.presentation.CatalogViewModel
import com.vovoscan.feature.catalog.presentation.TitleDetailViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Module Koin de la feature : sources locale (SQLDelight) et distante (Ktor) injectées dans le dépôt.
 * Attend de l'application : `HttpClient`, `DatabaseDriverFactory` et `AppDispatchers`.
 */
val catalogModule =
    module {
        single { CatalogDatabase(get<DatabaseDriverFactory>().create(CatalogDatabase.Schema, "catalog.db")) }
        single<CatalogRemoteDataSource> { KtorCatalogRemoteDataSource(get()) }
        single<CatalogLocalDataSource> { SqlCatalogLocalDataSource(get(), get<AppDispatchers>().io) }
        single<CatalogRepository> { CatalogRepositoryImpl(get(), get()) }
        viewModelOf(::CatalogViewModel)
        viewModel { (titleId: String) -> TitleDetailViewModel(titleId, get()) }
    }
