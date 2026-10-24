package com.vovoscan.feature.notifications.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.notifications.data.NotificationsRepositoryImpl
import com.vovoscan.feature.notifications.data.db.NotificationsDatabase
import com.vovoscan.feature.notifications.domain.NotificationsRepository
import com.vovoscan.feature.notifications.presentation.NotificationsViewModel
import org.koin.dsl.module

/**
 * Koin module for the inbox: the API and the last inbox read. One view model for the app (the bell and the screen
 * share it). Expects from the app: `HttpClient`, `DatabaseDriverFactory`, `AppDispatchers`. The push registrar is
 * wired by the app (it needs the session and the platform's `PushDevice`).
 */
val notificationsModule =
    module {
        single { NotificationsDatabase(get<DatabaseDriverFactory>().create(NotificationsDatabase.Schema, "notifications.db")) }
        single<NotificationsRepository> { NotificationsRepositoryImpl(get(), get(), get<AppDispatchers>().io, SystemClock) }
        single { NotificationsViewModel(get(), SystemClock) }
    }
