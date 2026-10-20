package com.vovoscan.shared

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.network.ApiConfig
import com.vovoscan.core.network.createHttpClient
import com.vovoscan.core.network.platformHttpEngine
import com.vovoscan.core.security.SecureKeys
import com.vovoscan.core.security.SecureStore
import com.vovoscan.core.sources.RulesHttp
import com.vovoscan.core.sources.createRulesHttpClient
import com.vovoscan.feature.auth.di.SignOutCleanup
import com.vovoscan.feature.auth.di.authModule
import com.vovoscan.feature.catalog.di.catalogModule
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.library.di.libraryModule
import com.vovoscan.feature.library.domain.LibraryRepository
import com.vovoscan.feature.reader.di.readerModule
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReadingProgressSink
import com.vovoscan.feature.sources.di.sourcesModule
import com.vovoscan.feature.sources.domain.SourcesRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.module

/** `apiBaseUrl` se termine par « / » (« https://api.vovoscan.app/ »). */
data class AppConfig(
    val apiBaseUrl: String,
)

/**
 * Tous les modules Koin de l'application ; `platform` fournit `DatabaseDriverFactory` et `SecureStore`.
 * Chaque feature reçoit ses sources locale et distante de son propre module (`di`).
 */
fun appModules(
    config: AppConfig,
    platform: Module,
): List<Module> =
    listOf(
        platform,
        module {
            single { AppDispatchers() }
            // Portée de l'application : écritures qui doivent survivre à l'écran qui les lance.
            single { CoroutineScope(SupervisorJob() + get<AppDispatchers>().io) }
            // Sources lues sur l'appareil : client séparé, sans la session vovoscan ni redirection automatique.
            single { RulesHttp(createRulesHttpClient(platformHttpEngine())) }
            single<HttpClient> {
                val store = get<SecureStore>()
                createHttpClient(platformHttpEngine(), ApiConfig(config.apiBaseUrl)) { store.get(SecureKeys.SESSION_TOKEN) }
            }
            // À la déconnexion, chaque feature efface sa copie locale (rien ne reste sur l'appareil).
            single {
                SignOutCleanup {
                    get<CatalogRepository>().clearLocal()
                    get<LibraryRepository>().clearLocal()
                    get<ReaderRepository>().clearLocal()
                    get<SourcesRepository>().clearLocal()
                }
            }
            // Le lecteur enregistre la progression dans la bibliothèque, sans connaître la feature.
            single {
                val library = get<LibraryRepository>()
                val scope = get<CoroutineScope>()
                ReadingProgressSink { sourceKey, titleId, chapterId, page ->
                    scope.launch { library.saveProgress(sourceKey, titleId, chapterId, page) }
                }
            }
        },
        authModule,
        catalogModule,
        libraryModule,
        readerModule,
        sourcesModule,
    )
