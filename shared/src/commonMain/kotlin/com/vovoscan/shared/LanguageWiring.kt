package com.vovoscan.shared

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.UiLanguage
import com.vovoscan.core.common.map
import com.vovoscan.feature.account.domain.ProviderKeysRepository
import com.vovoscan.feature.account.domain.ReadingSettingsRepository
import com.vovoscan.feature.account.domain.WalletRepository
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.OnboardingCredits
import com.vovoscan.feature.auth.domain.OnboardingServices
import com.vovoscan.feature.auth.domain.OnboardingSources
import com.vovoscan.feature.auth.domain.OnboardingTitle
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.sources.domain.SourcesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** The official source every account is offered first and that starts off (ADR-0055). */
internal const val OFFICIAL_SOURCE = "vovoscan"

/**
 * The app speaks the signed-in account's language (and region): started by each app at launch, after
 * `UiLanguage.start` (the device's choice) and before the session is restored.
 */
class LocaleSync(
    private val auth: AuthRepository,
    private val language: UiLanguage,
    private val scope: CoroutineScope,
) {
    fun start() {
        auth.session
            .filterNotNull()
            .map { it.locale to it.region }
            .distinctUntilChanged()
            .onEach { (locale, region) -> language.applyAccount(locale, region) }
            .launchIn(scope)
    }
}

/** The tutorial's port, served by the account (reading language, wallet, keys) and sources slices. */
internal class AppOnboardingServices(
    private val reading: ReadingSettingsRepository,
    private val sourcesRepository: SourcesRepository,
    private val wallet: WalletRepository,
    private val keys: ProviderKeysRepository,
    private val catalog: CatalogRepository,
) : OnboardingServices {
    override fun readingLanguage(): Flow<String?> = reading.observeTargetLanguage()

    override suspend fun saveReadingLanguage(code: String): AppResult<Unit> = reading.setTargetLanguage(code).map { }

    override fun sources(): Flow<OnboardingSources> =
        sourcesRepository.observe().map { list ->
            val official = list.firstOrNull { it.key == OFFICIAL_SOURCE && !it.thirdParty }
            OnboardingSources(
                officialAvailable = official != null,
                officialOn = official?.enabled == true,
                enabledCount = list.count { it.enabled },
            )
        }

    override suspend fun refreshSources() {
        sourcesRepository.refresh()
    }

    /** On and read first; the others keep their order and choice. Offline it is queued like any choice. */
    override suspend fun turnOnOfficial(): AppResult<Unit> {
        val list = sourcesRepository.observe().first().sortedBy { it.position }
        if (list.none { it.key == OFFICIAL_SOURCE && !it.thirdParty }) return AppResult.Ok(Unit)
        val choices = listOf(OFFICIAL_SOURCE to true) + list.filter { it.key != OFFICIAL_SOURCE }.map { it.key to it.enabled }
        return sourcesRepository.save(choices)
    }

    override fun credits(): Flow<OnboardingCredits> =
        combine(wallet.observeWallet(), keys.observeKeys()) { balance, list ->
            OnboardingCredits(balance = balance?.balanceCredits, ownKeys = list?.keys?.isNotEmpty() == true)
        }

    override suspend fun refreshCredits() {
        keys.refreshKeys()
        wallet.refreshWallet()
    }

    override fun featured(): Flow<OnboardingTitle?> =
        catalog
            .observeListing(OFFICIAL_SOURCE)
            .map { listing -> listing.firstOrNull()?.title?.let { OnboardingTitle(it.sourceKey, it.id, it.name, it.coverUrl) } }
            .distinctUntilChanged()

    override suspend fun refreshFeatured() {
        val on = sourcesRepository.observe().first().any { it.key == OFFICIAL_SOURCE && !it.thirdParty && it.enabled }
        if (on) catalog.refreshListing(OFFICIAL_SOURCE)
    }
}
