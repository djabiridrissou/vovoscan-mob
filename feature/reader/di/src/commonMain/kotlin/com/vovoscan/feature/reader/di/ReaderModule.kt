package com.vovoscan.feature.reader.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.reader.data.KtorReaderRemoteDataSource
import com.vovoscan.feature.reader.data.ReaderLocalDataSource
import com.vovoscan.feature.reader.data.ReaderRemoteDataSource
import com.vovoscan.feature.reader.data.ReaderRepositoryImpl
import com.vovoscan.feature.reader.data.SqlReaderLocalDataSource
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.presentation.ReaderViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Module Koin du lecteur : sources locale (SQLDelight) et distante (Ktor) injectées dans le dépôt.
 * Attend de l'application : `HttpClient`, `DatabaseDriverFactory`, `AppDispatchers` et `ReadingProgressSink`.
 */
val readerModule =
    module {
        single { ReaderDatabase(get<DatabaseDriverFactory>().create(ReaderDatabase.Schema, "reader.db")) }
        single<ReaderRemoteDataSource> { KtorReaderRemoteDataSource(get()) }
        single<ReaderLocalDataSource> { SqlReaderLocalDataSource(get(), get<AppDispatchers>().io) }
        single<ReaderRepository> { ReaderRepositoryImpl(get(), get()) }
        viewModel { (chapterId: String, startPage: Int) -> ReaderViewModel(chapterId, startPage, get(), get()) }
    }
