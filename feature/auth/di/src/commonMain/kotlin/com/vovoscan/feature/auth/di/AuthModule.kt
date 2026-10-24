package com.vovoscan.feature.auth.di

import com.vovoscan.feature.auth.data.AuthRemoteDataSource
import com.vovoscan.feature.auth.data.AuthRepositoryImpl
import com.vovoscan.feature.auth.data.KtorAuthRemoteDataSource
import com.vovoscan.feature.auth.data.SecureSessionLocalDataSource
import com.vovoscan.feature.auth.data.SessionLocalDataSource
import com.vovoscan.feature.auth.domain.AccountRepository
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.presentation.AccountViewModel
import com.vovoscan.feature.auth.presentation.OnboardingViewModel
import com.vovoscan.feature.auth.presentation.PreferencesViewModel
import com.vovoscan.feature.auth.presentation.SessionViewModel
import com.vovoscan.feature.auth.presentation.SignInViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Erases the other features' local data on sign-out (wired in `shared`). */
fun interface SignOutCleanup {
    suspend fun run()
}

/** Runs before signing out, while the session still opens the API (wired in `shared`; optional). */
fun interface BeforeSignOut {
    suspend fun run()
}

/**
 * Koin module of the feature. Expects from the app: `HttpClient`, `SecureStore`, `SignOutCleanup`, `UiLanguage`,
 * `DevicePreferences` and `OnboardingServices`.
 */
val authModule =
    module {
        single<AuthRemoteDataSource> { KtorAuthRemoteDataSource(get()) }
        single<SessionLocalDataSource> { SecureSessionLocalDataSource(get()) }
        single {
            // Resolved when signing out: what it needs may itself read the session.
            AuthRepositoryImpl(
                get(),
                get(),
                onSignedOut = { get<SignOutCleanup>().run() },
                beforeSignOut = { getOrNull<BeforeSignOut>()?.run() },
            )
        }
        single<AuthRepository> { get<AuthRepositoryImpl>() }
        single<AccountRepository> { get<AuthRepositoryImpl>() }
        viewModelOf(::SignInViewModel)
        viewModelOf(::SessionViewModel)
        viewModelOf(::AccountViewModel)
        viewModel { PreferencesViewModel(get(), get(), get()) }
        viewModel { OnboardingViewModel(get(), get(), get(), get()) }
    }
