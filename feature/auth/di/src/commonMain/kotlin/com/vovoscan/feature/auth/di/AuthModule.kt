package com.vovoscan.feature.auth.di

import com.vovoscan.feature.auth.data.AuthRemoteDataSource
import com.vovoscan.feature.auth.data.AuthRepositoryImpl
import com.vovoscan.feature.auth.data.KtorAuthRemoteDataSource
import com.vovoscan.feature.auth.data.SecureSessionLocalDataSource
import com.vovoscan.feature.auth.data.SessionLocalDataSource
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.presentation.SessionViewModel
import com.vovoscan.feature.auth.presentation.SignInViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Erases the other features' local data on sign-out (wired in `shared`). */
fun interface SignOutCleanup {
    suspend fun run()
}

/** Koin module of the feature. Expects from the app: `HttpClient`, `SecureStore` and `SignOutCleanup`. */
val authModule =
    module {
        single<AuthRemoteDataSource> { KtorAuthRemoteDataSource(get()) }
        single<SessionLocalDataSource> { SecureSessionLocalDataSource(get()) }
        single<AuthRepository> { AuthRepositoryImpl(get(), get()) { get<SignOutCleanup>().run() } }
        viewModelOf(::SignInViewModel)
        viewModelOf(::SessionViewModel)
    }
