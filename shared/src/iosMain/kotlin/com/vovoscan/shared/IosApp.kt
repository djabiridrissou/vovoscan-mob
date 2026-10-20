package com.vovoscan.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.core.security.KeychainSecureStore
import com.vovoscan.core.security.SecureStore
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.presentation.SessionViewModel
import com.vovoscan.feature.auth.presentation.SignInViewModel
import com.vovoscan.feature.catalog.presentation.CatalogViewModel
import com.vovoscan.feature.catalog.presentation.TitleDetailViewModel
import com.vovoscan.feature.library.presentation.FollowViewModel
import com.vovoscan.feature.library.presentation.LibraryViewModel
import com.vovoscan.feature.reader.presentation.ReaderViewModel
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** Appelé une fois au lancement de l'app iOS (VovoscanApp.swift). */
fun initKoin(apiBaseUrl: String) {
    startKoin {
        modules(
            appModules(
                AppConfig(apiBaseUrl),
                module {
                    single { DatabaseDriverFactory() }
                    single<SecureStore> { KeychainSecureStore() }
                },
            ),
        )
    }
    // Session gardée sur l'appareil, avant le premier écran (lecture du Keychain, courte ; le réseau suit).
    runBlocking { KoinPlatform.getKoin().get<AuthRepository>().restore() }
}

/**
 * Fournit les ViewModels Kotlin aux vues SwiftUI. Chaque écran garde son propre `ViewModels`
 * et appelle `clear()` en disparaissant : les coroutines du ViewModel sont annulées.
 */
class ViewModels : KoinComponent {
    private val store = ViewModelStore()

    fun session(): SessionViewModel = remember("session") { get<SessionViewModel>() }

    fun signIn(): SignInViewModel = remember("signIn") { get<SignInViewModel>() }

    fun catalog(): CatalogViewModel = remember("catalog") { get<CatalogViewModel>() }

    fun titleDetail(titleId: String): TitleDetailViewModel =
        remember("title:$titleId") { get<TitleDetailViewModel> { parametersOf(titleId) } }

    fun follow(titleId: String): FollowViewModel = remember("follow:$titleId") { get<FollowViewModel> { parametersOf(titleId) } }

    fun library(): LibraryViewModel = remember("library") { get<LibraryViewModel>() }

    fun reader(
        chapterId: String,
        startPage: Int,
    ): ReaderViewModel = remember("reader:$chapterId") { get<ReaderViewModel> { parametersOf(chapterId, startPage) } }

    fun clear() = store.clear()

    @Suppress("UNCHECKED_CAST")
    private fun <T : ViewModel> remember(
        key: String,
        create: () -> T,
    ): T = (store[key] as T?) ?: create().also { store.put(key, it) }
}
