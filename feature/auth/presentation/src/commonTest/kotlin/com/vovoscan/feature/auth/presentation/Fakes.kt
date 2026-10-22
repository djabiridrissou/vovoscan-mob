package com.vovoscan.feature.auth.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.auth.domain.AccountRepository
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Change
import com.vovoscan.feature.auth.domain.DeviceSession
import com.vovoscan.feature.auth.domain.OnboardingCredits
import com.vovoscan.feature.auth.domain.OnboardingServices
import com.vovoscan.feature.auth.domain.OnboardingSources
import com.vovoscan.feature.auth.domain.ProfileChange
import com.vovoscan.feature.auth.domain.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun testSession(
    onboardingDue: Boolean = false,
    timeZone: String? = "Africa/Lome",
) = Session("u1", "r@v.app", "Reader", emptyList(), emptySet(), onboardingDue = onboardingDue, timeZone = timeZone)

/** Auth and account in one fake, like `AuthRepositoryImpl`. */
internal class FakeAuth(
    var next: AppResult<Session> = AppResult.Err(AppError.Network),
    signedIn: Session? = null,
) : AuthRepository,
    AccountRepository {
    var calls = 0
    val current = MutableStateFlow(signedIn)
    override val session = current
    val changes = mutableListOf<ProfileChange>()
    var profileError: AppError? = null
    var redeemed: List<Any?> = emptyList()

    override suspend fun restore() = Unit

    override suspend fun signIn(
        email: String,
        password: String,
    ) = next.also { calls++ }

    override suspend fun redeemInvite(
        code: String,
        email: String,
        password: String,
        displayName: String,
        locale: String?,
        region: String?,
        timeZone: String?,
        ageConfirmed: Boolean,
    ): AppResult<Session> {
        redeemed = listOf(code, locale, region, timeZone, ageConfirmed)
        return next.also { calls++ }
    }

    override suspend fun updateProfile(change: ProfileChange): AppResult<Session> {
        changes += change
        profileError?.let { return AppResult.Err(it) }
        val session = current.value ?: return AppResult.Err(AppError.Unauthorized)
        val updated =
            session.copy(
                displayName = change.displayName ?: session.displayName,
                locale = change.locale ?: session.locale,
                region = (change.region as? Change.Set)?.value ?: session.region.takeIf { change.region == Change.Keep },
                timeZone = (change.timeZone as? Change.Set)?.value ?: session.timeZone.takeIf { change.timeZone == Change.Keep },
                onboardingDue = if (change.onboardingCompleted == true) false else session.onboardingDue,
            )
        current.value = updated
        return AppResult.Ok(updated)
    }

    override suspend fun signOut() {
        current.value = null
    }

    var devices =
        mutableListOf(
            DeviceSession("me", "mobile", true, 1_000L, 5_000L, null, "iPhone"),
            DeviceSession("mac", "web", false, 1_000L, 4_000L, "203.0.113.0", "Mac · Chrome"),
        )
    var accountError: AppError? = null
    val passwords = mutableListOf<String>()

    override suspend fun sessions(): AppResult<List<DeviceSession>> =
        accountError?.let { AppResult.Err(it) } ?: AppResult.Ok(devices.toList())

    override suspend fun revokeSession(id: String): AppResult<Unit> {
        accountError?.let { return AppResult.Err(it) }
        devices.removeAll { it.id == id }
        return AppResult.Ok(Unit)
    }

    override suspend fun revokeOtherSessions(): AppResult<Int> {
        val others = devices.count { !it.current }
        devices.removeAll { !it.current }
        return AppResult.Ok(others)
    }

    override suspend fun changePassword(
        current: String,
        new: String,
    ): AppResult<Unit> {
        passwords += current
        return accountError?.let { AppResult.Err(it) } ?: AppResult.Ok(Unit)
    }

    override suspend fun changeEmail(
        password: String,
        newEmail: String,
    ): AppResult<Unit> {
        passwords += password
        return accountError?.let { AppResult.Err(it) } ?: AppResult.Ok(Unit)
    }

    override suspend fun scheduleDeletion(password: String): AppResult<Long> {
        passwords += password
        accountError?.let { return AppResult.Err(it) }
        current.value = current.value?.copy(deletionScheduledAtMillis = DELETION_AT)
        return AppResult.Ok(DELETION_AT)
    }

    override suspend fun cancelDeletion(): AppResult<Unit> {
        accountError?.let { return AppResult.Err(it) }
        current.value = current.value?.copy(deletionScheduledAtMillis = null)
        return AppResult.Ok(Unit)
    }

    companion object {
        /** 2026-11-21T14:03:00Z. */
        const val DELETION_AT = 1_795_269_780_000L
    }
}

internal class FakeOnboarding : OnboardingServices {
    val reading = MutableStateFlow<String?>(null)
    val sourcesFlow = MutableStateFlow(OnboardingSources(officialAvailable = true, officialOn = false, enabledCount = 0))
    val creditsFlow = MutableStateFlow(OnboardingCredits(balance = 120, ownKeys = false))
    var readingError: AppError? = null

    override fun readingLanguage(): Flow<String?> = reading

    override suspend fun saveReadingLanguage(code: String): AppResult<Unit> {
        readingError?.let { return AppResult.Err(it) }
        reading.value = code
        return AppResult.Ok(Unit)
    }

    override fun sources(): Flow<OnboardingSources> = sourcesFlow

    override suspend fun refreshSources() = Unit

    override suspend fun turnOnOfficial(): AppResult<Unit> {
        sourcesFlow.value = sourcesFlow.value.copy(officialOn = true, enabledCount = sourcesFlow.value.enabledCount + 1)
        return AppResult.Ok(Unit)
    }

    override fun credits(): Flow<OnboardingCredits> = creditsFlow

    override suspend fun refreshCredits() = Unit
}
