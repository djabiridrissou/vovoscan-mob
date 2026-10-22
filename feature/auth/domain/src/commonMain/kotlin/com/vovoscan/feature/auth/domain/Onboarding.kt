package com.vovoscan.feature.auth.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** The first-launch tutorial's version (i18n/SPEC.md §4): a higher one shows it again once. */
const val ONBOARDING_VERSION = 1

/** The tutorial's steps, in order. Skip is on every step, Back from [Language]. */
enum class OnboardingStep { Welcome, Language, Reading, Sources, Credits, Done }

/** The account's sources as the tutorial shows them: the official "vovoscan" source first (ADR-0055). */
data class OnboardingSources(
    /** The server offers the vovoscan source to this account (it starts off). */
    val officialAvailable: Boolean,
    val officialOn: Boolean,
    /** Every visible source, official or third-party. */
    val enabledCount: Int,
)

/** The credits card: [balance] null on a server without credits. */
data class OnboardingCredits(
    val balance: Long?,
    /** At least one of the account's own provider keys is stored. */
    val ownKeys: Boolean,
)

/**
 * Port: what the tutorial reads and writes in other features (reading settings, sources, wallet and keys), wired by
 * the app; the auth slice never sees them.
 */
interface OnboardingServices {
    /** The content language chapters are translated into; null until chosen. */
    fun readingLanguage(): Flow<String?>

    suspend fun saveReadingLanguage(code: String): AppResult<Unit>

    fun sources(): Flow<OnboardingSources>

    suspend fun refreshSources()

    /** One tap = the vovoscan source on and read first (`PUT /catalog/sources`). */
    suspend fun turnOnOfficial(): AppResult<Unit>

    fun credits(): Flow<OnboardingCredits>

    suspend fun refreshCredits()
}
