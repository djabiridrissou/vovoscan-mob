package com.vovoscan.feature.forum.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.forum.data.ApiForumRemoteDataSource
import com.vovoscan.feature.forum.data.ForumRepositoryImpl
import com.vovoscan.feature.forum.data.db.ForumDatabase
import com.vovoscan.feature.forum.domain.ForumAccess
import com.vovoscan.feature.forum.domain.ForumRepository
import com.vovoscan.feature.forum.presentation.ForumViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for the margin: the API and the last margin read per chapter. Expects from the app: `HttpClient`,
 * `DatabaseDriverFactory`, `AppDispatchers`, and the `ForumAccess` and `WritingLanguage` ports.
 */
val forumModule =
    module {
        single { ForumDatabase(get<DatabaseDriverFactory>().create(ForumDatabase.Schema, "forum.db")) }
        single<ForumRepository> { ForumRepositoryImpl(ApiForumRemoteDataSource(get()), get(), get<AppDispatchers>().io, SystemClock) }
        viewModel { (chapterId: String) -> ForumViewModel(chapterId, get(), getOrNull() ?: ForumAccess.Off, get(), SystemClock) }
    }
