package com.vovoscan.shared

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.network.ApiConfig
import com.vovoscan.core.network.createHttpClient
import com.vovoscan.core.network.platformHttpEngine
import com.vovoscan.core.security.SecureKeys
import com.vovoscan.core.security.SecureStore
import com.vovoscan.core.sources.PluginLinkPolicy
import com.vovoscan.core.sources.RulesHttp
import com.vovoscan.core.sources.createRulesHttpClient
import com.vovoscan.feature.account.di.accountModule
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.ProviderKeysRepository
import com.vovoscan.feature.auth.di.SignOutCleanup
import com.vovoscan.feature.auth.di.authModule
import com.vovoscan.feature.catalog.di.catalogModule
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.episodes.di.episodesModule
import com.vovoscan.feature.episodes.domain.EpisodesRepository
import com.vovoscan.feature.episodes.domain.KeyStatus
import com.vovoscan.feature.episodes.domain.KeyStatusProvider
import com.vovoscan.feature.library.di.libraryModule
import com.vovoscan.feature.library.domain.LibraryRepository
import com.vovoscan.feature.reader.di.readerModule
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReadingProgressSink
import com.vovoscan.feature.sources.di.sourcesModule
import com.vovoscan.feature.sources.domain.SourceRemovedListener
import com.vovoscan.feature.sources.domain.SourcesRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * `apiBaseUrl` ends with "/" ("https://api.vovoscan.app/"). `allowLocalPlugins`: debug builds may add sources from a
 * local server (`http://localhost`, `http://127.0.0.1`, `http://10.0.2.2`).
 */
data class AppConfig(
    val apiBaseUrl: String,
    val allowLocalPlugins: Boolean = false,
)

/**
 * All Koin modules of the app; `platform` provides `DatabaseDriverFactory` and `SecureStore`.
 * Each feature gets its local and remote sources from its own module (`di`).
 */
fun appModules(
    config: AppConfig,
    platform: Module,
): List<Module> =
    listOf(
        platform,
        module {
            single { AppDispatchers() }
            // Application scope: writes that must outlive the screen that starts them.
            single { CoroutineScope(SupervisorJob() + get<AppDispatchers>().io) }
            // Sources read on the device: a separate client, without the vovoscan session or automatic redirects.
            single { RulesHttp(createRulesHttpClient(platformHttpEngine())) }
            single { PluginLinkPolicy(config.allowLocalPlugins) }
            single<HttpClient> {
                val store = get<SecureStore>()
                createHttpClient(platformHttpEngine(), ApiConfig(config.apiBaseUrl)) { store.get(SecureKeys.SESSION_TOKEN) }
            }
            // On sign-out, each feature erases its local copy (nothing stays on the device).
            single {
                SignOutCleanup {
                    get<CatalogRepository>().clearLocal()
                    get<LibraryRepository>().clearLocal()
                    get<ReaderRepository>().clearLocal()
                    get<SourcesRepository>().clearLocal()
                    get<ProviderKeysRepository>().clearLocal()
                    get<EpisodesRepository>().clearLocal()
                }
            }
            // A source left the list (third-party source removed, here or on another device): its catalog and
            // reader caches go; library entries stay ("Source removed").
            single {
                SourceRemovedListener { key ->
                    get<CatalogRepository>().clearSource(key)
                    get<ReaderRepository>().clearSource(key)
                }
            }
            // The reader saves progress in the library without knowing that feature.
            single {
                val library = get<LibraryRepository>()
                val scope = get<CoroutineScope>()
                ReadingProgressSink { sourceKey, titleId, chapterId, page ->
                    scope.launch { library.saveProgress(sourceKey, titleId, chapterId, page) }
                }
            }
            // Episodes show which of the user's keys a run would use, without knowing the account slice.
            single<KeyStatusProvider> { AccountKeyStatus(get()) }
        },
        accountModule,
        authModule,
        catalogModule,
        episodesModule,
        libraryModule,
        readerModule,
        sourcesModule,
    )

/** The episodes' `KeyStatusProvider` port, served by the account slice's key list (hints only, never a secret). */
private class AccountKeyStatus(
    private val keys: ProviderKeysRepository,
) : KeyStatusProvider {
    override fun observe(): Flow<KeyStatus?> =
        keys.observeKeys().map { list ->
            list?.let { KeyStatus(it.enabled, it.keyFor(Provider.Anthropic)?.hint, it.keyFor(Provider.ElevenLabs)?.hint) }
        }

    override suspend fun refresh() {
        keys.refreshKeys()
    }
}
