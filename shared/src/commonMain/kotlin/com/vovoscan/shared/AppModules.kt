package com.vovoscan.shared

import com.vovoscan.core.common.AppDispatchers
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.ChapterImports
import com.vovoscan.core.common.INTERNAL_SOURCE_KEY
import com.vovoscan.core.common.Language
import com.vovoscan.core.common.NetworkMonitor
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.UiLanguage
import com.vovoscan.core.network.ApiChapterImports
import com.vovoscan.core.network.ApiConfig
import com.vovoscan.core.network.createHttpClient
import com.vovoscan.core.network.platformHttpEngine
import com.vovoscan.core.network.simulatingOffline
import com.vovoscan.core.security.SecureKeys
import com.vovoscan.core.security.SecureStore
import com.vovoscan.core.sources.ImageReferers
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.PluginLinkPolicy
import com.vovoscan.core.sources.RulesHttp
import com.vovoscan.core.sources.createRulesHttpClient
import com.vovoscan.core.sources.sourceLanguage
import com.vovoscan.feature.account.di.accountModule
import com.vovoscan.feature.account.domain.COACHED_UNSUPPORTED
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.ProviderKeysRepository
import com.vovoscan.feature.account.domain.QuoteKind
import com.vovoscan.feature.account.domain.ReadingSettingsRepository
import com.vovoscan.feature.account.domain.WalletRepository
import com.vovoscan.feature.auth.di.SignOutCleanup
import com.vovoscan.feature.auth.di.authModule
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.OnboardingServices
import com.vovoscan.feature.catalog.di.catalogModule
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.ChapterTranslationBadge
import com.vovoscan.feature.catalog.domain.ChapterTranslations
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.ContentPolicy
import com.vovoscan.feature.catalog.domain.EnabledSources
import com.vovoscan.feature.catalog.domain.EpisodeFeature
import com.vovoscan.feature.catalog.domain.Favorites
import com.vovoscan.feature.catalog.domain.LibraryMarks
import com.vovoscan.feature.catalog.domain.MarkedTitle
import com.vovoscan.feature.catalog.domain.ReadingResume
import com.vovoscan.feature.catalog.domain.ResumePoint
import com.vovoscan.feature.catalog.domain.ResumePoints
import com.vovoscan.feature.catalog.domain.SeriesRepository
import com.vovoscan.feature.catalog.domain.TitleTranslationBadges
import com.vovoscan.feature.episodes.di.episodesModule
import com.vovoscan.feature.episodes.domain.EpisodeAccess
import com.vovoscan.feature.episodes.domain.EpisodesRepository
import com.vovoscan.feature.episodes.domain.KeyStatus
import com.vovoscan.feature.episodes.domain.KeyStatusProvider
import com.vovoscan.feature.episodes.domain.MadeEpisodeLanguages
import com.vovoscan.feature.episodes.domain.RunCost
import com.vovoscan.feature.episodes.domain.RunQuotes
import com.vovoscan.feature.forum.di.forumModule
import com.vovoscan.feature.forum.domain.ForumAccess
import com.vovoscan.feature.forum.domain.ForumRepository
import com.vovoscan.feature.forum.domain.WritingLanguage
import com.vovoscan.feature.library.di.libraryModule
import com.vovoscan.feature.library.domain.LibraryRepository
import com.vovoscan.feature.library.domain.LibrarySeries
import com.vovoscan.feature.library.domain.NewChapters
import com.vovoscan.feature.library.domain.TitleRef
import com.vovoscan.feature.reader.di.readerModule
import com.vovoscan.feature.reader.domain.ChapterHeading
import com.vovoscan.feature.reader.domain.ChapterHeadings
import com.vovoscan.feature.reader.domain.CoachKind
import com.vovoscan.feature.reader.domain.CoachSync
import com.vovoscan.feature.reader.domain.DownloadableChapter
import com.vovoscan.feature.reader.domain.DownloadableTitle
import com.vovoscan.feature.reader.domain.DownloadsRepository
import com.vovoscan.feature.reader.domain.HistoryTitle
import com.vovoscan.feature.reader.domain.HistoryTitles
import com.vovoscan.feature.reader.domain.LastRead
import com.vovoscan.feature.reader.domain.NextChaptersRepository
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReaderSettingsRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ReadingProgressSink
import com.vovoscan.feature.reader.domain.SeriesKey
import com.vovoscan.feature.reader.domain.SeriesModeSync
import com.vovoscan.feature.reader.domain.TitleChapters
import com.vovoscan.feature.reader.domain.TranslationQuote
import com.vovoscan.feature.reader.domain.TranslationQuotes
import com.vovoscan.feature.reader.domain.TranslationStatus
import com.vovoscan.feature.reader.domain.chapterLabel
import com.vovoscan.feature.reader.domain.readingModeOfWire
import com.vovoscan.feature.reader.domain.wire
import com.vovoscan.feature.sources.di.sourcesModule
import com.vovoscan.feature.sources.domain.SourceEngine
import com.vovoscan.feature.sources.domain.SourceRemovedListener
import com.vovoscan.feature.sources.domain.SourcesRepository
import com.vovoscan.feature.together.di.togetherModule
import com.vovoscan.feature.together.domain.MutedReaders
import com.vovoscan.feature.together.domain.TogetherAccess
import com.vovoscan.feature.together.domain.TogetherLanguage
import com.vovoscan.feature.together.presentation.TogetherSession
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * `apiBaseUrl` ends with "/" ("https://api.vovoscan.app/"). `allowLocalPlugins`: debug builds may add sources from a
 * local server (`http://localhost`, `http://127.0.0.1`, `http://10.0.2.2`). `simulateOffline`: debug builds only,
 * every request fails as with no network (the iOS simulator has no network switch of its own).
 */
data class AppConfig(
    val apiBaseUrl: String,
    val allowLocalPlugins: Boolean = false,
    val simulateOffline: Boolean = false,
)

/**
 * All Koin modules of the app; `platform` provides `DatabaseDriverFactory`, `SecureStore`, `NetworkMonitor`,
 * `AppFolders` and `DevicePreferences`.
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
            single {
                val client = createRulesHttpClient(platformHttpEngine())
                RulesHttp(
                    if (config.simulateOffline) client.simulatingOffline() else client,
                    getOrNull<NetworkMonitor>() ?: NetworkMonitor.AssumeOnline,
                )
            }
            single { PluginLinkPolicy(config.allowLocalPlugins) }
            single<HttpClient> {
                val store = get<SecureStore>()
                val client = createHttpClient(platformHttpEngine(), ApiConfig(config.apiBaseUrl)) { store.get(SecureKeys.SESSION_TOKEN) }
                if (config.simulateOffline) client.simulatingOffline() else client
            }
            // On sign-out, each feature erases its local copy (nothing stays on the device).
            single {
                SignOutCleanup {
                    // Downloaded pages first: their files go with the rows.
                    get<DownloadsRepository>().clearLocal()
                    get<CatalogRepository>().clearLocal()
                    get<LibraryRepository>().clearLocal()
                    get<ReaderRepository>().clearLocal()
                    get<SourcesRepository>().clearLocal()
                    get<ProviderKeysRepository>().clearLocal()
                    get<WalletRepository>().clear()
                    get<EpisodesRepository>().clearLocal()
                    get<ForumRepository>().clearLocal()
                    get<TogetherSession>().clear()
                }
            }
            // A source left the list (third-party source removed, here or on another device): its catalog and
            // reader caches go; library entries stay ("Source removed").
            single {
                SourceRemovedListener { key ->
                    get<DownloadsRepository>().clearSource(key)
                    get<CatalogRepository>().clearSource(key)
                    get<ReaderRepository>().clearSource(key)
                }
            }
            // The reader saves progress in the library without knowing that feature.
            // Every title read is also kept on the device (followed or not), for "Continue reading".
            single {
                val library = get<LibraryRepository>()
                val history = get<ReaderSettingsRepository>()
                val scope = get<CoroutineScope>()
                ReadingProgressSink { sourceKey, titleId, chapterId, page ->
                    scope.launch {
                        history.recordRead(LastRead(sourceKey, titleId, chapterId, page, SystemClock.nowMillis()))
                        library.saveProgress(sourceKey, titleId, chapterId, page)
                    }
                }
            }
            // The series page's badges: each chapter's translation in the language our catalog was last read in.
            single<ChapterTranslations> {
                val settings = get<ReaderSettingsRepository>()
                val next = get<NextChaptersRepository>()
                ChapterTranslations { sourceKey, titleId ->
                    val lang = settings.lastLanguage()
                    if (sourceKey != INTERNAL_SOURCE_KEY || lang == null) return@ChapterTranslations null
                    when (val badges = next.titleBadges(titleId, lang)) {
                        is AppResult.Err -> null
                        is AppResult.Ok ->
                            TitleTranslationBadges(
                                lang,
                                badges.value
                                    .filter { it.status != TranslationStatus.None && it.status != TranslationStatus.Failed }
                                    .associate { b ->
                                        b.chapterId to
                                            ChapterTranslationBadge(
                                                ready = b.status == TranslationStatus.Ready,
                                                progress = b.progress.takeIf { b.status == TranslationStatus.Running },
                                            )
                                    },
                            )
                    }
                }
            }
            // "Continue reading": this device's reading and the library's progress (synced from the account's other
            // devices), the most recent per title.
            // "Continue reading" is the account's (reader/SPEC.md §9): each screen that shows it sends this device's
            // places and takes the other devices' first.
            single<ReadingResume> {
                val library = get<LibraryRepository>()
                val history = get<ReaderSettingsRepository>()
                val scope = get<CoroutineScope>()
                ReadingResume {
                    combine(
                        history.observeHistory().onStart { scope.launch { history.syncHistory() } },
                        library.observe(),
                    ) { reads, entries ->
                        ResumePoints.merge(
                            reads.map { ResumePoint(it.sourceKey, it.titleId, it.chapterId, it.page, it.readAt) },
                            entries.mapNotNull { e ->
                                e.lastChapterRef?.let { ResumePoint(e.sourceKey, e.titleRef, it, e.lastPage, e.updatedAt) }
                            },
                        )
                    }.distinctUntilChanged()
                }
            }
            // The account's history names a title read on the device from the title page the device keeps.
            single<HistoryTitles> {
                val catalog = get<CatalogRepository>()
                HistoryTitles { sourceKey, titleId ->
                    catalog
                        .observeTitle(sourceKey, titleId)
                        .first()
                        ?.summary
                        ?.let { HistoryTitle(it.name, it.coverUrl) }
                }
            }
            // The reader's chapter transitions name the next / previous chapter from the title page kept on the device.
            single {
                val catalog = get<CatalogRepository>()
                ChapterHeadings { sourceKey, titleId, chapterId ->
                    catalog
                        .observeTitle(sourceKey, titleId)
                        .first()
                        ?.chapters
                        ?.firstOrNull { it.id == chapterId }
                        ?.let { ChapterHeading(it.number, it.name) }
                }
            }
            // Downloads on the title page: its chapters (catalog) and the chapter being read (library), for "Download next 5".
            single<TitleChapters> {
                val catalog = get<CatalogRepository>()
                val library = get<LibraryRepository>()
                TitleChapters { sourceKey, titleId ->
                    combine(catalog.observeTitle(sourceKey, titleId), library.observe()) { detail, entries ->
                        detail?.let {
                            DownloadableTitle(
                                sourceKey = sourceKey,
                                titleId = titleId,
                                titleName = it.summary.name,
                                isBook = it.summary.kind == ContentKind.Book,
                                // The title page runs newest to oldest; downloads go in reading order.
                                chapters =
                                    it.chapters.reversed().map { chapter ->
                                        val label = listOfNotNull(chapterLabel(chapter.number), chapter.name).joinToString(" · ")
                                        DownloadableChapter(
                                            chapter.id,
                                            chapter.number,
                                            if (it.summary.kind ==
                                                ContentKind.Book
                                            ) {
                                                chapter.name ?: label
                                            } else {
                                                label
                                            },
                                        )
                                    },
                                resumeChapterId =
                                    entries
                                        .firstOrNull { e ->
                                            e.sourceKey == sourceKey && e.titleRef == titleId
                                        }?.lastChapterRef,
                            )
                        }
                    }
                }
            }
            // Episodes show which of the user's keys a run would use, without knowing the account slice.
            single<KeyStatusProvider> { AccountKeyStatus(get(), get()) }
            // Episodes open to the account (the `video` feature flag, every feature for a super-admin), else "coming
            // soon": read from the session, without episodes knowing the auth slice.
            single<EpisodeAccess> {
                val auth = get<AuthRepository>()
                EpisodeAccess { auth.session.map { it?.has(VIDEO_FEATURE) == true }.distinctUntilChanged() }
            }
            // The margin shows only with the `forum` flag (gated by default; every feature for a super-admin).
            single<ForumAccess> {
                val auth = get<AuthRepository>()
                ForumAccess { auth.session.map { it?.has(FORUM_FEATURE) == true }.distinctUntilChanged() }
            }
            // Reading circles show only with the `shared_reading` flag (gated by default).
            single<TogetherAccess> {
                val auth = get<AuthRepository>()
                TogetherAccess { auth.session.map { it?.has(SHARED_READING_FEATURE) == true }.distinctUntilChanged() }
            }
            // The others see the language this reader chose to read in (null: the original).
            single<TogetherLanguage> {
                val reading = get<ReadingSettingsRepository>()
                TogetherLanguage { reading.observeTargetLanguage().first() }
            }
            // A reader muted in the margin is hidden from one's circle signals too (they are not told).
            single<MutedReaders> {
                val forum = get<ForumRepository>()
                MutedReaders {
                    (forum.mutes() as? AppResult.Ok)
                        ?.value
                        ?.map { it.userId }
                        ?.toSet()
                        .orEmpty()
                }
            }
            // Readers write in their reading language when they chose one, else in the app's language.
            single<WritingLanguage> {
                val reading = get<ReadingSettingsRepository>()
                WritingLanguage { reading.observeTargetLanguage().first() ?: I18n.locale.value.substringBefore('-') }
            }
            // The same flag for the catalog: the Episodes filter and the episode badges on cards and chapter rows.
            single<EpisodeFeature> {
                val auth = get<AuthRepository>()
                EpisodeFeature { auth.session.map { it?.has(VIDEO_FEATURE) == true }.distinctUntilChanged() }
            }
            // Safe mode (the account's `showAdult`, off by default): the catalog filters titles read on the device by the
            // same rule as the server, without knowing the auth slice. Signed out = safe.
            single<ContentPolicy> {
                val auth = get<AuthRepository>()
                ContentPolicy { auth.session.map { it?.showAdult == true }.distinctUntilChanged() }
            }
            // The heart on catalog cards, kept by the library (giving it follows the title), without the catalog knowing it.
            single<Favorites> {
                val library = get<LibraryRepository>()
                object : Favorites {
                    override fun observe() =
                        library
                            .observe()
                            .map { entries ->
                                LibraryMarks(
                                    favorites = entries.filter { it.favorite }.mapTo(HashSet()) { "${it.sourceKey}/${it.titleRef}" },
                                    followed = entries.mapTo(HashSet()) { "${it.sourceKey}/${it.titleRef}" },
                                )
                            }.distinctUntilChanged()

                    override suspend fun set(
                        title: MarkedTitle,
                        name: String,
                        coverUrl: String?,
                        favorite: Boolean,
                    ) = library.setFavorite(TitleRef(title.sourceKey, title.id, name, coverUrl), favorite)
                }
            }
            // Series members at a source this account turned off are left out of the title page (the sources slice).
            single<EnabledSources> {
                val sources = get<SourcesRepository>()
                EnabledSources {
                    sources
                        .observe()
                        .map { items -> items.filter { it.enabled }.sortedBy { it.position }.associate { it.key to it.name } }
                        .distinctUntilChanged()
                }
            }
            // One library row per series: the catalog's series, asked about the library's titles (the server knows them).
            single<LibrarySeries> {
                val series = get<SeriesRepository>()
                object : LibrarySeries {
                    override fun observe() = series.observe().map { known -> known.mapValues { it.value.id } }.distinctUntilChanged()

                    override suspend fun resolve(keys: List<Pair<String, String>>) {
                        series.resolve(keys.map { (sourceKey, titleRef) -> MarkedTitle(sourceKey, titleRef) })
                    }
                }
            }
            // "+3 new" in the library for a source read on the device: chapters numbered after the last one read, from
            // the device's copy of the title page (the server counts our own catalog's).
            single<NewChapters> {
                val catalog = get<CatalogRepository>()
                NewChapters { sourceKey, titleId, lastChapterRef ->
                    val chapters = catalog.observeTitle(sourceKey, titleId).first()?.chapters ?: return@NewChapters null
                    val last = lastChapterRef?.let { ref -> chapters.firstOrNull { it.id == ref } } ?: return@NewChapters null
                    chapters.count { it.number > last.number }
                }
            }
            // What a run would cost, from the account's credits wallet (a server without credits: no quote).
            // The balance comes with it (read again with each quote), and every run asked for, finished or refused reads
            // it again: the credits shown on every screen follow what this device spends.
            single<RunQuotes> {
                val wallet = get<WalletRepository>()
                val scope = get<CoroutineScope>()
                object : RunQuotes {
                    override suspend fun episode(
                        chapterId: String,
                        lang: String,
                    ): RunCost? =
                        (wallet.quote(QuoteKind.Episode, chapterId, lang.ifEmpty { null }) as? AppResult.Ok)?.value?.let {
                            RunCost(
                                it.credits,
                                it.coveredByOwnKeys,
                                it.alreadyAvailable,
                                it.maxCredits,
                                it.fundable,
                                it.metered,
                                it.unfunded,
                                balance = wallet.balance(),
                            )
                        }

                    override fun changed() {
                        scope.launch { wallet.refreshWallet() }
                    }
                }
            }
            // The languages an episode is made in, as the title page last read them: first in the card's picker.
            single<MadeEpisodeLanguages> {
                val catalog = get<CatalogRepository>()
                MadeEpisodeLanguages { sourceKey, chapterId -> catalog.observeEpisodeLanguages(sourceKey, chapterId) }
            }
            // Translating or voicing a chapter of a source read on the device: its pages are copied to the server first,
            // read there in the same `{{lang}}` as on the device (a language the server doesn't take reads in English).
            single<ChapterImports> {
                ApiChapterImports(get()) { sourceLanguage().takeIf { Language.of(it) != null } ?: "en" }
            }
            // The reader guide seen per format, on the account (the device keeps its own list too).
            single<CoachSync> {
                val reading = get<ReadingSettingsRepository>()
                object : CoachSync {
                    // A server without the slot (no `coached`, or 404 on a mark): the device copy is the truth until
                    // the app runs against one that has it; nothing is sent meanwhile and nothing shows.
                    private var slotMissing = false

                    override suspend fun seen(): Set<CoachKind>? =
                        when (val result = reading.coached()) {
                            is AppResult.Ok -> {
                                slotMissing = false
                                result.value.mapNotNull(CoachKind::fromWire).toSet()
                            }
                            is AppResult.Err -> {
                                if ((result.error as? AppError.Rejected)?.code == COACHED_UNSUPPORTED) slotMissing = true
                                null
                            }
                        }

                    override suspend fun mark(kind: CoachKind) {
                        if (slotMissing) return
                        val result = reading.markCoached(kind.wire)
                        if (result is AppResult.Err && result.error.isMissingRoute()) slotMissing = true
                    }
                }
            }
            // The reading mode chosen per series, on the account (the reader's database keeps a copy).
            single<SeriesModeSync> {
                val reading = get<ReadingSettingsRepository>()
                object : SeriesModeSync {
                    override suspend fun pull(): Map<SeriesKey, ReadingMode>? =
                        (reading.seriesModes() as? AppResult.Ok)
                            ?.value
                            ?.mapNotNull { (key, mode) ->
                                val series = SeriesKey.of(key) ?: return@mapNotNull null
                                readingModeOfWire(mode)?.let { series to it }
                            }?.toMap()

                    override suspend fun push(
                        series: SeriesKey,
                        mode: ReadingMode?,
                    ): Boolean = reading.setSeriesMode(series.sourceKey, series.titleId, mode?.wire) is AppResult.Ok
                }
            }
            // What translating a chapter would cost (the reader's language picker quotes before paying).
            single<TranslationQuotes> {
                val wallet = get<WalletRepository>()
                val scope = get<CoroutineScope>()
                object : TranslationQuotes {
                    override suspend fun quote(
                        chapterId: String,
                        lang: String,
                    ): TranslationQuote? =
                        (wallet.quote(QuoteKind.Translation, chapterId, lang) as? AppResult.Ok)?.value?.let {
                            TranslationQuote(
                                it.credits,
                                it.maxCredits,
                                it.coveredByOwnKeys,
                                it.alreadyAvailable,
                                it.fundable,
                                wallet.balance(),
                                unfunded = it.unfunded?.isNotEmpty() == true,
                            )
                        }

                    override fun changed() {
                        scope.launch { wallet.refreshWallet() }
                    }
                }
            }
            // Image loaders (Coil, the iOS URL session) send a source's page-image Referer to its hosts: the table
            // follows the user's sources and their manifests (started by each app at launch).
            single { ImageRefererSync(get(), get(), get()) }
            // The app's language: the device's choice at launch, then the signed-in account's.
            single { UiLanguage(get()) }
            single { LocaleSync(get(), get(), get(), get()) }
            single<OnboardingServices> { AppOnboardingServices(get(), get(), get(), get(), get()) }
        },
        accountModule,
        authModule,
        catalogModule,
        episodesModule,
        forumModule,
        libraryModule,
        readerModule,
        sourcesModule,
        togetherModule,
    )

/**
 * The Referer to send with an image request to [url] (a source's page images, `content.comic.referer`), or null:
 * read by Coil's interceptor (Android) and the URL protocol behind AsyncImage (iOS).
 */
fun imageRefererFor(url: String): String? = ImageReferers.refererFor(url)

/** Keeps `ImageReferers` up to date with the rule-based sources on the device, in the user's order. */
class ImageRefererSync(
    private val sources: SourcesRepository,
    private val manifests: ManifestProvider,
    private val scope: CoroutineScope,
) {
    fun start() {
        sources
            .observe()
            .map { list -> list.filter { it.engine == SourceEngine.OnDevice }.sortedBy { it.position }.map { it.key } }
            .distinctUntilChanged()
            .onEach { keys -> ImageReferers.update(keys.mapNotNull { manifests.manifest(it) }) }
            .launchIn(scope)
    }
}

/** The feature flag that opens episodes and their MP4 (the server's name for it). */
private const val VIDEO_FEATURE = "video"
private const val FORUM_FEATURE = "forum"
private const val SHARED_READING_FEATURE = "shared_reading"

/** The episodes' `KeyStatusProvider` port, served by the account slice's key list (hints only, never a secret). */
private class AccountKeyStatus(
    private val keys: ProviderKeysRepository,
    private val reading: ReadingSettingsRepository,
) : KeyStatusProvider {
    // The reading step runs on the first text key in the account's order (Claude, Gemini, Kimi; ADR-0060).
    override fun observe(): Flow<KeyStatus?> =
        combine(keys.observeKeys(), reading.observeTextProviders()) { list, order ->
            list?.let {
                val text = (order ?: Provider.TEXT_DEFAULT).firstNotNullOfOrNull(it::keyFor)
                KeyStatus(it.enabled, text?.hint, it.keyFor(Provider.ElevenLabs)?.hint, (text?.provider ?: Provider.Anthropic).label)
            }
        }

    override suspend fun refresh() {
        keys.refreshKeys()
    }
}

/** The route isn't on this server (an older one): 404 / 405 with the framework's generic code. */
private fun AppError.isMissingRoute(): Boolean =
    (this as? AppError.Rejected)?.code in setOf("NOT_FOUND", "METHOD_NOT_ALLOWED") || (this is AppError.Unknown && detail == "HTTP 404")

/** The balance read now (each quote refreshes the wallet the screens show); null on a server without credits. */
private suspend fun WalletRepository.balance(): Long? = (refreshWallet() as? AppResult.Ok)?.value?.balanceCredits
