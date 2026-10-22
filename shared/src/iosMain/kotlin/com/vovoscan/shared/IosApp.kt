package com.vovoscan.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.vovoscan.core.common.INTERNAL_SOURCE_KEY
import com.vovoscan.core.common.NetworkMonitor
import com.vovoscan.core.common.NetworkState
import com.vovoscan.core.common.i18n.DevicePreferences
import com.vovoscan.core.common.i18n.UiLanguage
import com.vovoscan.core.database.AppFolders
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.core.database.appFolders
import com.vovoscan.core.network.IosNetworkMonitor
import com.vovoscan.core.security.KeychainSecureStore
import com.vovoscan.core.security.SecureStore
import com.vovoscan.feature.account.presentation.ProviderKeysViewModel
import com.vovoscan.feature.account.presentation.ReadingSettingsViewModel
import com.vovoscan.feature.account.presentation.UsageViewModel
import com.vovoscan.feature.account.presentation.WalletViewModel
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.presentation.AccountViewModel
import com.vovoscan.feature.auth.presentation.OnboardingViewModel
import com.vovoscan.feature.auth.presentation.PreferencesViewModel
import com.vovoscan.feature.auth.presentation.SessionViewModel
import com.vovoscan.feature.auth.presentation.SignInViewModel
import com.vovoscan.feature.catalog.presentation.CatalogViewModel
import com.vovoscan.feature.catalog.presentation.SearchPrivacyViewModel
import com.vovoscan.feature.catalog.presentation.TitleDetailViewModel
import com.vovoscan.feature.episodes.presentation.EpisodeViewModel
import com.vovoscan.feature.episodes.presentation.PlayerViewModel
import com.vovoscan.feature.library.presentation.FollowViewModel
import com.vovoscan.feature.library.presentation.LibraryViewModel
import com.vovoscan.feature.reader.domain.DownloadActivity
import com.vovoscan.feature.reader.domain.DownloadBlock
import com.vovoscan.feature.reader.domain.DownloadsRepository
import com.vovoscan.feature.reader.presentation.DownloadsViewModel
import com.vovoscan.feature.reader.presentation.NextChapterViewModel
import com.vovoscan.feature.reader.presentation.ReaderViewModel
import com.vovoscan.feature.reader.presentation.TitleDownloadsViewModel
import com.vovoscan.feature.reader.presentation.TranslationViewModel
import com.vovoscan.feature.sources.presentation.AddSourceViewModel
import com.vovoscan.feature.sources.presentation.SourceDetailViewModel
import com.vovoscan.feature.sources.presentation.SourcesViewModel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
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

/**
 * Called once when the iOS app launches (VovoscanApp.swift). Debug builds may add sources from a local server, and
 * may simulate having no network (`simulateOffline`, from the `VOVOSCAN_OFFLINE=1` launch environment): the simulator
 * shares the Mac's network and has no switch of its own.
 */
@OptIn(ExperimentalNativeApi::class)
fun initKoin(
    apiBaseUrl: String,
    simulateOffline: Boolean = false,
) {
    val offline = simulateOffline && Platform.isDebugBinary
    startKoin {
        modules(
            appModules(
                AppConfig(apiBaseUrl, allowLocalPlugins = Platform.isDebugBinary, simulateOffline = offline),
                module {
                    single { DatabaseDriverFactory() }
                    single<SecureStore> { KeychainSecureStore() }
                    single<NetworkMonitor> {
                        if (offline) NetworkMonitor.Fixed(NetworkState(online = false, unmetered = false)) else IosNetworkMonitor()
                    }
                    single<AppFolders> { appFolders() }
                    single<DevicePreferences> { IosDevicePreferences() }
                },
            ),
        )
    }
    // The app's language before the first screen: the device's choice, then the account's once it is known.
    KoinPlatform.getKoin().get<UiLanguage>().start()
    KoinPlatform.getKoin().get<LocaleSync>().start()
    // Session kept on the device, before the first screen (a short Keychain read; the network follows).
    runBlocking { KoinPlatform.getKoin().get<AuthRepository>().restore() }
    // Chapters left downloading when the app stopped carry on.
    KoinPlatform.getKoin().get<DownloadsRepository>().resume()
    // Page images of sources that ask for a Referer (the URL protocol of the iOS app reads `ImageReferers`).
    KoinPlatform.getKoin().get<ImageRefererSync>().start()
}

/**
 * The download queue for iOS's background work (`BackgroundDownloads.swift`): Swift only asks for time and runs this;
 * the shared queue downloads, keeps its states and resumes at the first missing page.
 */
class BackgroundQueue : KoinComponent {
    private val downloads: DownloadsRepository = get()

    fun activity(): Flow<DownloadActivity> = downloads.observeActivity()

    /** Back on screen, or launched for a background task: the queue carries on. */
    fun resume() = downloads.resume()

    /** Background time is running out: the chapter downloading goes back to the queue, nothing fails. */
    suspend fun pause() = downloads.pause()

    /** Runs the queue until nothing is left (null) or it waits for the network (why). */
    suspend fun drain(): DownloadBlock? = downloads.drain()
}

/**
 * Provides the Kotlin ViewModels to the SwiftUI views. Each screen keeps its own `ViewModels`
 * and calls `clear()` when it disappears: the ViewModel's coroutines are cancelled.
 */
class ViewModels : KoinComponent {
    private val store = ViewModelStore()

    fun session(): SessionViewModel = remember("session") { get<SessionViewModel>() }

    fun signIn(): SignInViewModel = remember("signIn") { get<SignInViewModel>() }

    fun account(): AccountViewModel = remember("account") { get<AccountViewModel>() }

    fun preferences(): PreferencesViewModel = remember("preferences") { get<PreferencesViewModel>() }

    fun onboarding(): OnboardingViewModel = remember("onboarding") { get<OnboardingViewModel>() }

    fun catalog(): CatalogViewModel = remember("catalog") { get<CatalogViewModel>() }

    fun searchPrivacy(): SearchPrivacyViewModel = remember("searchPrivacy") { get<SearchPrivacyViewModel>() }

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

    fun titleDownloads(
        sourceKey: String,
        titleId: String,
    ): TitleDownloadsViewModel =
        remember("titleDownloads:$sourceKey/$titleId") { get<TitleDownloadsViewModel> { parametersOf(sourceKey, titleId) } }

    fun downloads(): DownloadsViewModel = remember("downloads") { get<DownloadsViewModel>() }

    fun translation(
        sourceKey: String,
        chapterId: String,
    ): TranslationViewModel =
        remember("translation:$sourceKey/$chapterId") { get<TranslationViewModel> { parametersOf(sourceKey, chapterId) } }

    /**
     * The language button of a chapter of any source: [titleId] lets a source's chapter be brought to the server first;
     * [language] (a translated copy opened in it) is chosen at once when it is ready.
     */
    fun translation(
        sourceKey: String,
        chapterId: String,
        titleId: String,
        language: String?,
    ): TranslationViewModel =
        remember("translation:$sourceKey/$chapterId~${language.orEmpty()}") {
            get<TranslationViewModel> { parametersOf(sourceKey, chapterId, titleId, language) }
        }

    /** What comes after a chapter of our catalog in the language on the page (end card, balloon, toast, series setting). */
    fun next(
        sourceKey: String,
        chapterId: String,
    ): NextChapterViewModel = remember("next:$sourceKey/$chapterId") { get<NextChapterViewModel> { parametersOf(sourceKey, chapterId) } }

    fun providerKeys(): ProviderKeysViewModel = remember("providerKeys") { get<ProviderKeysViewModel>() }

    fun readingSettings(): ReadingSettingsViewModel = remember("readingSettings") { get<ReadingSettingsViewModel>() }

    fun usage(): UsageViewModel = remember("usage") { get<UsageViewModel>() }

    fun wallet(): WalletViewModel = remember("wallet") { get<WalletViewModel>() }

    fun episode(chapterId: String): EpisodeViewModel = remember("episode:$chapterId") { get<EpisodeViewModel> { parametersOf(chapterId) } }

    /** The card of the next chapter, opened in the language just watched ("" = the original). */
    fun episode(
        chapterId: String,
        language: String,
    ): EpisodeViewModel =
        if (language.isEmpty()) {
            episode(chapterId)
        } else {
            remember("episode:$chapterId~$language") { get<EpisodeViewModel> { parametersOf(chapterId, language) } }
        }

    /** The card of a chapter of a source read on the device: its pages are brought to the server before anything runs. */
    fun episode(
        chapterId: String,
        language: String,
        sourceKey: String,
        titleId: String,
    ): EpisodeViewModel =
        if (sourceKey == INTERNAL_SOURCE_KEY) {
            episode(chapterId, language)
        } else {
            remember("episode:$sourceKey/$chapterId~$language") {
                get<EpisodeViewModel> { parametersOf(chapterId, language, sourceKey, titleId) }
            }
        }

    /** `nextChapterId`: the chapter after this one when the opening screen knows it ("Next chapter →" at the end). */
    fun player(
        chapterId: String,
        nextChapterId: String?,
    ): PlayerViewModel = player(chapterId, nextChapterId, "")

    /** The player of the episode in [language] ("" = the original): the card's `language`. */
    fun player(
        chapterId: String,
        nextChapterId: String?,
        language: String,
    ): PlayerViewModel =
        remember("player:$chapterId~$language") { get<PlayerViewModel> { parametersOf(chapterId, nextChapterId, language) } }

    /** The player opened from the reader ("Watch from here"): the episode in [language], from the first beat of [startPage]. */
    fun playerFrom(
        chapterId: String,
        language: String,
        startPage: Int,
    ): PlayerViewModel =
        remember("player:$chapterId~$language@$startPage") {
            get<PlayerViewModel> { parametersOf(chapterId, null, language, startPage) }
        }

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
