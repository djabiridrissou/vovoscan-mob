package com.vovoscan.feature.together.di

import com.vovoscan.core.common.SystemClock
import com.vovoscan.feature.together.data.TogetherRepositoryImpl
import com.vovoscan.feature.together.domain.MutedReaders
import com.vovoscan.feature.together.domain.TogetherAccess
import com.vovoscan.feature.together.domain.TogetherLanguage
import com.vovoscan.feature.together.domain.TogetherRepository
import com.vovoscan.feature.together.presentation.TogetherInviteViewModel
import com.vovoscan.feature.together.presentation.TogetherSession
import com.vovoscan.feature.together.presentation.TogetherViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for reading circles (一緒に読む): the API and the circle kept while the app runs. Expects from the app:
 * `HttpClient`, `CoroutineScope` (the app's), `ThisDevice`, and the `TogetherAccess`, `TogetherLanguage` and
 * `MutedReaders` ports.
 */
val togetherModule =
    module {
        single<TogetherRepository> { TogetherRepositoryImpl(get()) }
        single { TogetherSession(get(), get()) }
        viewModel { (chapterId: String) ->
            TogetherViewModel(
                chapterId,
                get(),
                get(),
                getOrNull() ?: TogetherAccess.Off,
                getOrNull() ?: TogetherLanguage.Original,
                getOrNull() ?: MutedReaders.None,
                get(),
                SystemClock,
            )
        }
        viewModel { (link: String) ->
            TogetherInviteViewModel(link, get(), get(), getOrNull() ?: TogetherAccess.Off, getOrNull() ?: TogetherLanguage.Original, get())
        }
    }
