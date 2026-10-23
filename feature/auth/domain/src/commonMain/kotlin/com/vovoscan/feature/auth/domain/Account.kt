package com.vovoscan.feature.auth.domain

import com.vovoscan.core.common.AppResult

/** A value a change sets, or leaves as it is ([Keep]); `Set(null)` clears it (region, time zone). */
sealed interface Change<out T> {
    data object Keep : Change<Nothing>

    data class Set<T>(
        val value: T,
    ) : Change<T>
}

/** `PATCH /me`: any subset (i18n/SPEC.md §3). */
data class ProfileChange(
    val displayName: String? = null,
    val locale: String? = null,
    val region: Change<String?> = Change.Keep,
    val timeZone: Change<String?> = Change.Keep,
    val onboardingCompleted: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = displayName == null && locale == null && region == Change.Keep && timeZone == Change.Keep && onboardingCompleted == null
}

/** A place the account is signed in (`GET /me/sessions`); [ip] is masked by the server, [id] is never the token. */
data class DeviceSession(
    val id: String,
    val client: String,
    val current: Boolean,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long?,
    val ip: String?,
    /** "Mac · Chrome", "iPhone · vovoscan". */
    val device: String,
)

/** The account's own profile, sign-in and devices. Every write needs the network, except [AuthRepository.updateProfile]. */
interface AccountRepository {
    suspend fun sessions(): AppResult<List<DeviceSession>>

    /** Signs another device out; `422 SELF` for this one (sign out instead), `404 SESSION_NOT_FOUND` when gone. */
    suspend fun revokeSession(id: String): AppResult<Unit>

    /** Signs out every other device: how many were. */
    suspend fun revokeOtherSessions(): AppResult<Int>

    /** The other devices are signed out; this one stays. `401 WRONG_PASSWORD` is not an ended session. */
    suspend fun changePassword(
        current: String,
        new: String,
    ): AppResult<Unit>

    /** A confirmation link goes to the new address; nothing changes until it is opened. */
    suspend fun changeEmail(
        password: String,
        newEmail: String,
    ): AppResult<Unit>

    /** Erased after 30 days unless cancelled; the session's `deletionScheduledAtMillis` follows. */
    suspend fun scheduleDeletion(password: String): AppResult<Long>

    suspend fun cancelDeletion(): AppResult<Unit>
}
