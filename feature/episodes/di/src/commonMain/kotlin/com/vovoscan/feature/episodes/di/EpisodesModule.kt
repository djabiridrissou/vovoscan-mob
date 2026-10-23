package com.vovoscan.feature.episodes.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.common.ChapterImports
import com.vovoscan.core.common.INTERNAL_SOURCE_KEY
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.episodes.data.EpisodesLocalDataSource
import com.vovoscan.feature.episodes.data.EpisodesRemoteDataSource
import com.vovoscan.feature.episodes.data.EpisodesRepositoryImpl
import com.vovoscan.feature.episodes.data.KtorEpisodesRemoteDataSource
import com.vovoscan.feature.episodes.data.SqlEpisodesLocalDataSource
import com.vovoscan.feature.episodes.data.db.EpisodesDatabase
import com.vovoscan.feature.episodes.domain.EpisodesRepository
import com.vovoscan.feature.episodes.domain.MadeEpisodeLanguages
import com.vovoscan.feature.episodes.presentation.EpisodeViewModel
import com.vovoscan.feature.episodes.presentation.PlayerViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for episodes: local (SQLDelight: last episode and watch per chapter) and remote (Ktor, server-sent
 * events included) sources injected into the repository, and the screen and player ViewModels.
 * Expects from the app: `HttpClient`, `DatabaseDriverFactory`, `AppDispatchers`, `KeyStatusProvider`,
 * `EpisodeAccess` and `RunQuotes` (and `ChapterImports`, optional).
 */
val episodesModule =
    module {
        single { EpisodesDatabase(get<DatabaseDriverFactory>().create(EpisodesDatabase.Schema, "episodes.db")) }
        single<EpisodesRemoteDataSource> { KtorEpisodesRemoteDataSource(get()) }
        single<EpisodesLocalDataSource> { SqlEpisodesLocalDataSource(get(), get<AppDispatchers>().io) }
        single<EpisodesRepository> { EpisodesRepositoryImpl(get(), get(), SystemClock) }
        // The language ("" = the original) is optional: a card opens on the original, the next chapter's in the language watched.
        // A source's chapter also names its source and title: its pages are brought to the server first.
        viewModel { params ->
            EpisodeViewModel(
                params.values[0] as String,
                get(),
                get(),
                get(),
                get(),
                language = params.values.getOrNull(1) as String? ?: "",
                sourceKey = params.values.getOrNull(2) as String? ?: INTERNAL_SOURCE_KEY,
                titleId = params.values.getOrNull(3) as String? ?: "",
                imports = getOrNull() ?: ChapterImports.None,
                made = getOrNull() ?: MadeEpisodeLanguages.None,
            )
        }
        // The next chapter (optional) comes from the screen that opens the player: the reader or the title page know it.
        viewModel { params ->
            PlayerViewModel(
                params.values[0] as String,
                params.values.getOrNull(1) as String?,
                get(),
                language = params.values.getOrNull(2) as String? ?: "",
                startPage = params.values.getOrNull(3) as Int?,
                quotes = get(),
            )
        }
    }
