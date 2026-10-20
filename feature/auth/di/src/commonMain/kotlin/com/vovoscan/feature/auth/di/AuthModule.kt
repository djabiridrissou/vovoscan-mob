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

/** Effacement des données locales des autres features à la déconnexion (assemblé dans `shared`). */
fun interface SignOutCleanup {
    suspend fun run()
}

/** Module Koin de la feature. Attend de l'application : `HttpClient`, `SecureStore` et `SignOutCleanup`. */
val authModule =
    module {
        single<AuthRemoteDataSource> { KtorAuthRemoteDataSource(get()) }
        single<SessionLocalDataSource> { SecureSessionLocalDataSource(get()) }
        single<AuthRepository> { AuthRepositoryImpl(get(), get()) { get<SignOutCleanup>().run() } }
        viewModelOf(::SignInViewModel)
        viewModelOf(::SessionViewModel)
    }
