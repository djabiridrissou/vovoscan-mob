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
import com.vovoscan.feature.sources.presentation.AddSourceViewModel
import com.vovoscan.feature.sources.presentation.SourceDetailViewModel
import com.vovoscan.feature.sources.presentation.SourcesViewModel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.Foundation.NSData
import platform.posix.memcpy
import kotlin.experimental.ExperimentalNativeApi

/** Called once when the iOS app launches (VovoscanApp.swift). Debug builds may add sources from a local server. */
@OptIn(ExperimentalNativeApi::class)
fun initKoin(apiBaseUrl: String) {
    startKoin {
        modules(
            appModules(
                AppConfig(apiBaseUrl, allowLocalPlugins = Platform.isDebugBinary),
                module {
                    single { DatabaseDriverFactory() }
                    single<SecureStore> { KeychainSecureStore() }
                },
            ),
        )
    }
    // Session kept on the device, before the first screen (a short Keychain read; the network follows).
    runBlocking { KoinPlatform.getKoin().get<AuthRepository>().restore() }
}

/**
 * Provides the Kotlin ViewModels to the SwiftUI views. Each screen keeps its own `ViewModels`
 * and calls `clear()` when it disappears: the ViewModel's coroutines are cancelled.
 */
class ViewModels : KoinComponent {
    private val store = ViewModelStore()

    fun session(): SessionViewModel = remember("session") { get<SessionViewModel>() }

    fun signIn(): SignInViewModel = remember("signIn") { get<SignInViewModel>() }

    fun catalog(): CatalogViewModel = remember("catalog") { get<CatalogViewModel>() }

    fun titleDetail(
        sourceKey: String,
        titleId: String,
    ): TitleDetailViewModel = remember("title:$sourceKey/$titleId") { get<TitleDetailViewModel> { parametersOf(sourceKey, titleId) } }

    fun follow(
        sourceKey: String,
        titleId: String,
    ): FollowViewModel = remember("follow:$sourceKey/$titleId") { get<FollowViewModel> { parametersOf(sourceKey, titleId) } }

    fun sources(): SourcesViewModel = remember("sources") { get<SourcesViewModel>() }

    fun addSource(): AddSourceViewModel = remember("addSource") { get<AddSourceViewModel>() }

    fun sourceDetail(key: String): SourceDetailViewModel = remember("source:$key") { get<SourceDetailViewModel> { parametersOf(key) } }

    fun library(): LibraryViewModel = remember("library") { get<LibraryViewModel>() }

    fun reader(
        sourceKey: String,
        titleId: String,
        chapterId: String,
        startPage: Int,
    ): ReaderViewModel =
        remember("reader:$sourceKey/$chapterId") { get<ReaderViewModel> { parametersOf(sourceKey, titleId, chapterId, startPage) } }

    fun clear() = store.clear()

    @Suppress("UNCHECKED_CAST")
    private fun <T : ViewModel> remember(
        key: String,
        create: () -> T,
    ): T = (store[key] as T?) ?: create().also { store.put(key, it) }
}

/** A file picked in SwiftUI (`Data`) as the bytes `AddSourceIntent.ImportFile` expects. */
@OptIn(ExperimentalForeignApi::class)
fun bytesOf(data: NSData): ByteArray {
    val size = data.length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return bytes
}
