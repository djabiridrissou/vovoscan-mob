package com.vovoscan.feature.account.di

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.feature.account.data.AccountLocalDataSource
import com.vovoscan.feature.account.data.AccountRemoteDataSource
import com.vovoscan.feature.account.data.AccountRepositoryImpl
import com.vovoscan.feature.account.data.KtorAccountRemoteDataSource
import com.vovoscan.feature.account.data.KtorWalletRemoteDataSource
import com.vovoscan.feature.account.data.SqlAccountLocalDataSource
import com.vovoscan.feature.account.data.WalletRemoteDataSource
import com.vovoscan.feature.account.data.WalletRepositoryImpl
import com.vovoscan.feature.account.data.db.AccountDatabase
import com.vovoscan.feature.account.domain.ProviderKeysRepository
import com.vovoscan.feature.account.domain.ReadingSettingsRepository
import com.vovoscan.feature.account.domain.UsageRepository
import com.vovoscan.feature.account.domain.WalletRepository
import com.vovoscan.feature.account.presentation.ProviderKeysViewModel
import com.vovoscan.feature.account.presentation.ReadingSettingsViewModel
import com.vovoscan.feature.account.presentation.UsageViewModel
import com.vovoscan.feature.account.presentation.WalletViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Koin module for the account slice (the user's own provider keys, reading model, costs and credits wallet): local (SQLDelight, no
 * secret ever) and remote (Ktor) sources injected into one repository. Expects from the app: `HttpClient`,
 * `DatabaseDriverFactory` and `AppDispatchers`.
 */
val accountModule =
    module {
        single { AccountDatabase(get<DatabaseDriverFactory>().create(AccountDatabase.Schema, "account.db")) }
        single<AccountRemoteDataSource> { KtorAccountRemoteDataSource(get()) }
        single<AccountLocalDataSource> { SqlAccountLocalDataSource(get(), get<AppDispatchers>().io) }
        single { AccountRepositoryImpl(get(), get()) } binds
            arrayOf(ProviderKeysRepository::class, ReadingSettingsRepository::class, UsageRepository::class)
        viewModel { ProviderKeysViewModel(get()) }
        viewModel { ReadingSettingsViewModel(get()) }
        viewModel { UsageViewModel(get()) }
        single<WalletRemoteDataSource> { KtorWalletRemoteDataSource(get()) }
        single<WalletRepository> { WalletRepositoryImpl(get()) }
        viewModel { WalletViewModel(get()) }
    }
