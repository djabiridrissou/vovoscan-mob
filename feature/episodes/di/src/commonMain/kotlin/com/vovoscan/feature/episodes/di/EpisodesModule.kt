package com.vovoscan.feature.episodes.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.episodes.data.EpisodesLocalDataSource
import com.vovoscan.feature.episodes.data.EpisodesRemoteDataSource
import com.vovoscan.feature.episodes.data.EpisodesRepositoryImpl
import com.vovoscan.feature.episodes.data.KtorEpisodesRemoteDataSource
import com.vovoscan.feature.episodes.data.SqlEpisodesLocalDataSource
import com.vovoscan.feature.episodes.data.db.EpisodesDatabase
import com.vovoscan.feature.episodes.domain.EpisodesRepository
import com.vovoscan.feature.episodes.presentation.EpisodeViewModel
import com.vovoscan.feature.episodes.presentation.PlayerViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for episodes: local (SQLDelight: last episode and watch per chapter) and remote (Ktor, server-sent
 * events included) sources injected into the repository, and the screen and player ViewModels.
 * Expects from the app: `HttpClient`, `DatabaseDriverFactory`, `AppDispatchers`, `KeyStatusProvider`,
 * `EpisodeAccess` and `RunQuotes`.
 */
val episodesModule =
    module {
        single { EpisodesDatabase(get<DatabaseDriverFactory>().create(EpisodesDatabase.Schema, "episodes.db")) }
        single<EpisodesRemoteDataSource> { KtorEpisodesRemoteDataSource(get()) }
        single<EpisodesLocalDataSource> { SqlEpisodesLocalDataSource(get(), get<AppDispatchers>().io) }
        single<EpisodesRepository> { EpisodesRepositoryImpl(get(), get(), SystemClock) }
        viewModel { (chapterId: String) -> EpisodeViewModel(chapterId, get(), get(), get(), get()) }
        // The next chapter (optional) comes from the screen that opens the player: the reader or the title page know it.
        viewModel { params -> PlayerViewModel(params.values[0] as String, params.values.getOrNull(1) as String?, get()) }
    }
