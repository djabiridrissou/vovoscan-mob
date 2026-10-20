package com.vovoscan.feature.library.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.library.data.KtorLibraryRemoteDataSource
import com.vovoscan.feature.library.data.LibraryLocalDataSource
import com.vovoscan.feature.library.data.LibraryRemoteDataSource
import com.vovoscan.feature.library.data.LibraryRepositoryImpl
import com.vovoscan.feature.library.data.SqlLibraryLocalDataSource
import com.vovoscan.feature.library.data.db.LibraryDatabase
import com.vovoscan.feature.library.domain.LibraryRepository
import com.vovoscan.feature.library.presentation.FollowViewModel
import com.vovoscan.feature.library.presentation.LibraryViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin module for the feature: local (SQLDelight + outbox) and remote (Ktor) sources injected into the repository.
 * Expects from the app: `HttpClient`, `DatabaseDriverFactory` and `AppDispatchers`.
 */
val libraryModule =
    module {
        single { LibraryDatabase(get<DatabaseDriverFactory>().create(LibraryDatabase.Schema, "library.db")) }
        single<LibraryRemoteDataSource> { KtorLibraryRemoteDataSource(get()) }
        single<LibraryLocalDataSource> { SqlLibraryLocalDataSource(get(), get<AppDispatchers>().io) }
        single<LibraryRepository> { LibraryRepositoryImpl(get(), get(), SystemClock) }
        viewModelOf(::LibraryViewModel)
        viewModel { (sourceKey: String, titleId: String) -> FollowViewModel(sourceKey, titleId, get()) }
    }
