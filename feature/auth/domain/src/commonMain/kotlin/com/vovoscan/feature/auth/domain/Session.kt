package com.vovoscan.feature.auth.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Signed-in user. [permissions] comes from the server ("module:action", "*" for a super-admin):
 * the UI only uses it to hide what would be refused; the server checks every call again.
 * [features] are the account's feature flags (`video`: episodes and their MP4); a feature not granted shows as
 * "coming soon". A super-admin has every feature.
 */
data class Session(
    val userId: String,
    val email: String,
    val displayName: String,
    val roles: List<String>,
    val permissions: Set<String>,
    val isSuperAdmin: Boolean = false,
    val features: Set<String> = emptySet(),
    val emailVerified: Boolean = true,
    /** UI locale ("en", "fr"), region (ISO, formatting only) and IANA time zone; null when not set. */
    val locale: String? = null,
    val region: String? = null,
    val timeZone: String? = null,
    /** Show the first-launch tutorial once (never done, or an older version). */
    val onboardingDue: Boolean = false,
    /** The account is erased at this moment unless the deletion is cancelled. */
    val deletionScheduledAtMillis: Long? = null,
    /** False for an account that only signs in by link: password change asks for none. */
    val hasPassword: Boolean = true,
    /** Safe mode off: titles for adults (and unrated ones) are shown. Off by default; never on without the account's say. */
    val showAdult: Boolean = false,
) {
    fun can(permission: String): Boolean = permission in permissions || "*" in permissions

    fun has(feature: String): Boolean = isSuperAdmin || feature in features

    /** Publishes series in the creator studio (web only: the apps link to it, they never upload). */
    val isCreator: Boolean get() = can("titles", "create")

    fun can(
        module: String,
        action: String,
    ): Boolean = can("$module:$action")
}

interface AuthRepository {
    /** Current session: restored from the secure store at launch, even offline. */
    val session: StateFlow<Session?>

    /** Reads the session kept on the device, then confirms it with the server if the network answers. */
    suspend fun restore()

    suspend fun signIn(
        email: String,
        password: String,
    ): AppResult<Session>

    /**
     * Sign-up with a single-use invite (brand: "invite-only"). [locale], [region] and [timeZone] come from the device;
     * [ageConfirmed] is the "I am {age} or older." box (`422 AGE_NOT_CONFIRMED` without it).
     */
    suspend fun redeemInvite(
        code: String,
        email: String,
        password: String,
        displayName: String,
        locale: String? = null,
        region: String? = null,
        timeZone: String? = null,
        ageConfirmed: Boolean = true,
    ): AppResult<Session>

    /**
     * `PATCH /me`: applied to the session on the device at once; without network it is kept and sent at the next
     * launch (idempotent, last write wins). Other refusals come back as errors and the server's profile stays.
     */
    suspend fun updateProfile(change: ProfileChange): AppResult<Session>

    /** Revokes the session on the server if possible, and erases everything on the device. */
    suspend fun signOut()
}
