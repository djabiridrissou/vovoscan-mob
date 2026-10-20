package com.vovoscan.feature.reader.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.reader.data.ApiReaderRemoteDataSource
import com.vovoscan.feature.reader.data.EpubParser
import com.vovoscan.feature.reader.data.OnDeviceReaderRemoteDataSource
import com.vovoscan.feature.reader.data.ReaderLocalDataSource
import com.vovoscan.feature.reader.data.ReaderRemoteDataSource
import com.vovoscan.feature.reader.data.ReaderRepositoryImpl
import com.vovoscan.feature.reader.data.RoutingReaderRemoteDataSource
import com.vovoscan.feature.reader.data.SqlReaderLocalDataSource
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.presentation.ReaderViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Module Koin du lecteur : sources locale (SQLDelight) et distante (Ktor) injectées dans le dépôt.
 * Attend de l'application : `HttpClient`, `DatabaseDriverFactory`, `AppDispatchers`, `ReadingProgressSink`,
 * `ManifestProvider` et `RulesHttp`.
 */
val readerModule =
    module {
        single { ReaderDatabase(get<DatabaseDriverFactory>().create(ReaderDatabase.Schema, "reader.db")) }
        // Source interne par l'API ; sources lues sur l'appareil par le moteur de règles (client HTTP séparé).
        single<ReaderRemoteDataSource> {
            RoutingReaderRemoteDataSource(get(), ApiReaderRemoteDataSource(get()), OnDeviceReaderRemoteDataSource(get(), get()))
        }
        single<ReaderLocalDataSource> { SqlReaderLocalDataSource(get(), get<AppDispatchers>().io) }
        single<ReaderRepository> { ReaderRepositoryImpl(get(), get(), EpubParser(), get<AppDispatchers>().io) }
        viewModel { (sourceKey: String, titleId: String, chapterId: String, startPage: Int) ->
            ReaderViewModel(sourceKey, titleId, chapterId, startPage, get(), get())
        }
    }
