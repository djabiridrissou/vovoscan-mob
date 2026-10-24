package com.vovoscan.feature.groups.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.groups.data.GroupsRepositoryImpl
import com.vovoscan.feature.groups.data.db.GroupsDatabase
import com.vovoscan.feature.groups.domain.GroupsAccess
import com.vovoscan.feature.groups.domain.GroupsRepository
import com.vovoscan.feature.groups.domain.GroupsUser
import com.vovoscan.feature.groups.presentation.GameViewModel
import com.vovoscan.feature.groups.presentation.GroupAdminViewModel
import com.vovoscan.feature.groups.presentation.GroupViewModel
import com.vovoscan.feature.groups.presentation.GroupsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for groups and their games: the API and the last list and chats read. Expects from the app:
 * `HttpClient`, `DatabaseDriverFactory`, `AppDispatchers`, and the `GroupsAccess` and `GroupsUser` ports.
 */
val groupsModule =
    module {
        single { GroupsDatabase(get<DatabaseDriverFactory>().create(GroupsDatabase.Schema, "groups.db")) }
        single<GroupsRepository> { GroupsRepositoryImpl(get(), get(), get<AppDispatchers>().io, SystemClock) }
        viewModel { GroupsViewModel(get(), getOrNull() ?: GroupsAccess.Off) }
        viewModel { (ref: String) ->
            GroupViewModel(ref, get(), getOrNull() ?: GroupsAccess.Off, getOrNull() ?: GroupsUser.None, SystemClock)
        }
        viewModel { (groupId: String) -> GroupAdminViewModel(groupId, get(), getOrNull() ?: GroupsUser.None) }
        viewModel { (gameId: String) ->
            GameViewModel(gameId, get(), getOrNull() ?: GroupsAccess.Off, getOrNull() ?: GroupsUser.None, SystemClock)
        }
    }
