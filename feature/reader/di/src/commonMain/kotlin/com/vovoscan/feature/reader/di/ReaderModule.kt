package com.vovoscan.feature.reader.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.common.ChapterImports
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.database.AppFolders
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.reader.data.ApiReaderRemoteDataSource
import com.vovoscan.feature.reader.data.DownloadedPages
import com.vovoscan.feature.reader.data.DownloadsLocalDataSource
import com.vovoscan.feature.reader.data.DownloadsRepositoryImpl
import com.vovoscan.feature.reader.data.EpubParser
import com.vovoscan.feature.reader.data.OnDeviceReaderRemoteDataSource
import com.vovoscan.feature.reader.data.PageFiles
import com.vovoscan.feature.reader.data.ReaderLocalDataSource
import com.vovoscan.feature.reader.data.ReaderRemoteDataSource
import com.vovoscan.feature.reader.data.ReaderRepositoryImpl
import com.vovoscan.feature.reader.data.RoutingReaderRemoteDataSource
import com.vovoscan.feature.reader.data.SqlBookReadingRepository
import com.vovoscan.feature.reader.data.SqlDownloadsLocalDataSource
import com.vovoscan.feature.reader.data.SqlReaderLocalDataSource
import com.vovoscan.feature.reader.data.SqlReaderSettingsRepository
import com.vovoscan.feature.reader.data.TranslationsRepositoryImpl
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.BookReadingRepository
import com.vovoscan.feature.reader.domain.ChapterPanels
import com.vovoscan.feature.reader.domain.CoachSync
import com.vovoscan.feature.reader.domain.DownloadScheduler
import com.vovoscan.feature.reader.domain.DownloadsRepository
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReaderSettingsRepository
import com.vovoscan.feature.reader.domain.TranslationQuotes
import com.vovoscan.feature.reader.domain.TranslationsRepository
import com.vovoscan.feature.reader.presentation.DownloadsViewModel
import com.vovoscan.feature.reader.presentation.ReaderViewModel
import com.vovoscan.feature.reader.presentation.TitleDownloadsViewModel
import com.vovoscan.feature.reader.presentation.TranslationViewModel
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Koin module for the reader: local (SQLDelight) and remote (Ktor) sources injected into the repository.
 * Expects from the app: `HttpClient`, `DatabaseDriverFactory`, `AppDispatchers`, `ReadingProgressSink`,
 * `ManifestProvider`, `RulesHttp`, `ChapterHeadings`, and for downloads `AppFolders`, `NetworkMonitor`, the app's
 * `CoroutineScope` and `TitleChapters`; optionally a `DownloadScheduler` (background downloads).
 */
val readerModule =
    module {
        single { ReaderDatabase(get<DatabaseDriverFactory>().create(ReaderDatabase.Schema, "reader.db")) }
        // Internal source through the API; on-device sources through the rules engine (separate HTTP client).
        single<ReaderRemoteDataSource> {
            RoutingReaderRemoteDataSource(get(), ApiReaderRemoteDataSource(get()), OnDeviceReaderRemoteDataSource(get(), get()))
        }
        single<ReaderLocalDataSource> { SqlReaderLocalDataSource(get(), get<AppDispatchers>().io) }
        single { PageFiles(get<AppFolders>().downloads) }
        single<DownloadsLocalDataSource> { SqlDownloadsLocalDataSource(get(), get<AppDispatchers>().io) }
        // A downloaded chapter's pages are read from the device first.
        single<ReaderRepository> {
            ReaderRepositoryImpl(get(), get(), EpubParser(), get<AppDispatchers>().io, DownloadedPages(get(), get()))
        }
        // The queue runs in the app's scope: it outlives the screen that starts a download. The platform's scheduler
        // (Android WorkManager) keeps it going in the background; none on iOS (its background tasks are in Swift).
        single<DownloadsRepository> {
            DownloadsRepositoryImpl(
                remote = get(),
                readerLocal = get(),
                local = get(),
                files = get(),
                network = get(),
                scope = get<CoroutineScope>(),
                io = get<AppDispatchers>().io,
                scheduler = getOrNull<DownloadScheduler>() ?: DownloadScheduler.None,
                clock = SystemClock,
            )
        }
        // Reading modes, tap zones and the other reader settings: on this device only.
        single<ReaderSettingsRepository> { SqlReaderSettingsRepository(get(), get<AppDispatchers>().io) }
        // Books: the page's typography and the position inside a section, on this device only (written in the app's
        // scope, so the last position is kept once the reader has closed).
        single<BookReadingRepository> { SqlBookReadingRepository(get(), get<AppDispatchers>().io, get<CoroutineScope>()) }
        viewModel { (sourceKey: String, titleId: String, chapterId: String, startPage: Int) ->
            ReaderViewModel(
                sourceKey,
                titleId,
                chapterId,
                startPage,
                get(),
                get(),
                get(),
                panels = get(),
                coachSync = getOrNull() ?: CoachSync.None,
                books = get(),
                parts = get(),
                progress = get(),
            )
        }
        viewModel { (sourceKey: String, titleId: String) -> TitleDownloadsViewModel(sourceKey, titleId, get(), get()) }
        viewModelOf(::DownloadsViewModel)
        // Translations of our catalog's chapters: read fresh from the API, lettering laid out in shared code.
        single { TranslationsRepositoryImpl(get()) } binds arrayOf(TranslationsRepository::class, ChapterPanels::class)
        // (source, chapter, title, language to open in): a source's chapter is translated on its copy on the server.
        viewModel { params ->
            TranslationViewModel(
                params.values[0] as String,
                params.values[1] as String,
                get(),
                getOrNull() ?: TranslationQuotes.None,
                titleId = params.values.getOrNull(2) as String? ?: "",
                imports = getOrNull() ?: ChapterImports.None,
                initialLanguage = params.values.getOrNull(3) as String?,
            )
        }
    }
