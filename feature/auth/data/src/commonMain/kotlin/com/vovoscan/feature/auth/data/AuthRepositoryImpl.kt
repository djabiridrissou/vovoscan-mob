package com.vovoscan.feature.auth.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.core.network.ApiJson
import com.vovoscan.feature.auth.domain.AccountRepository
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Change
import com.vovoscan.feature.auth.domain.DeviceSession
import com.vovoscan.feature.auth.domain.ProfileChange
import com.vovoscan.feature.auth.domain.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Offline first: the session from the secure store is returned at once, then confirmed by `GET /auth/me`.
 * Only a 401 removes it (session revoked); a network failure keeps it. Profile changes made offline (language,
 * tutorial done) are kept as one merged `PATCH /me` and sent before the next `GET /auth/me`.
 */
class AuthRepositoryImpl(
    private val remote: AuthRemoteDataSource,
    private val local: SessionLocalDataSource,
    /** Called on sign-out: the other features erase their local data. */
    private val onSignedOut: suspend () -> Unit = {},
    /** Called before the session ends, while it still opens the API (this device's push registration goes). */
    private val beforeSignOut: suspend () -> Unit = {},
) : AuthRepository,
    AccountRepository {
    private val current = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = current.asStateFlow()

    override suspend fun restore() {
        if (local.token() == null) return
        current.value = local.user()?.toDomain()
        if (sendPending() == AppError.Unauthorized) return clearEverything()
        when (val me = remote.me()) {
            is AppResult.Ok -> keepUser(me.value)
            is AppResult.Err -> if (me.error == AppError.Unauthorized) clearEverything()
        }
    }

    override suspend fun signIn(
        email: String,
        password: String,
    ): AppResult<Session> = remote.signIn(email.trim(), password).keep()

    override suspend fun redeemInvite(
        code: String,
        email: String,
        password: String,
        displayName: String,
        locale: String?,
        region: String?,
        timeZone: String?,
        ageConfirmed: Boolean,
    ): AppResult<Session> =
        remote
            .redeemInvite(RedeemInvite(code, email.trim(), password, displayName.trim(), locale, region, timeZone, ageConfirmed))
            .keep()

    override suspend fun updateProfile(change: ProfileChange): AppResult<Session> {
        val user = local.user() ?: return AppResult.Err(AppError.Unauthorized)
        if (change.isEmpty) return AppResult.Ok(user.toDomain())
        val patch = merge(local.pendingProfile()?.let(::decode), change.toJson())
        keepUser(user.applying(change))
        return when (val result = remote.updateMe(patch)) {
            is AppResult.Ok -> {
                local.savePendingProfile(null)
                AppResult.Ok(keepUser(result.value))
            }
            is AppResult.Err ->
                when (result.error) {
                    // Sent at the next launch; the device already shows the change.
                    AppError.Network -> {
                        local.savePendingProfile(ApiJson.encodeToString(JsonObject.serializer(), patch))
                        AppResult.Ok(current.value ?: user.applying(change).toDomain())
                    }
                    else -> {
                        local.savePendingProfile(null)
                        keepUser(user)
                        result
                    }
                }
        }
    }

    override suspend fun signOut() {
        beforeSignOut()
        remote.signOut()
        clearEverything()
    }

    // Account (always online).

    override suspend fun sessions(): AppResult<List<DeviceSession>> =
        remote.sessions().map { list ->
            list
                .map {
                    DeviceSession(
                        id = it.id,
                        client = it.client,
                        current = it.current,
                        createdAtMillis = parseIsoMillis(it.createdAt) ?: 0L,
                        lastUsedAtMillis = it.lastUsedAt?.let(::parseIsoMillis),
                        ip = it.ip,
                        device = it.device,
                    )
                }.sortedWith(
                    compareByDescending<DeviceSession> { it.current }.thenByDescending { it.lastUsedAtMillis ?: it.createdAtMillis },
                )
        }

    override suspend fun revokeSession(id: String): AppResult<Unit> = remote.revokeSession(id)

    override suspend fun revokeOtherSessions(): AppResult<Int> = remote.revokeOthers()

    override suspend fun changePassword(
        current: String,
        new: String,
    ): AppResult<Unit> = remote.changePassword(current, new)

    override suspend fun changeEmail(
        password: String,
        newEmail: String,
    ): AppResult<Unit> = remote.changeEmail(password, newEmail.trim())

    override suspend fun scheduleDeletion(password: String): AppResult<Long> {
        val result = remote.scheduleDeletion(password)
        if (result !is AppResult.Ok) return result as AppResult.Err
        local.user()?.let { keepUser(it.copy(deletion = result.value)) }
        return AppResult.Ok(parseIsoMillis(result.value.scheduledFor) ?: 0L)
    }

    override suspend fun cancelDeletion(): AppResult<Unit> {
        val result = remote.cancelDeletion()
        if (result is AppResult.Ok) local.user()?.let { keepUser(it.copy(deletion = null)) }
        return result
    }

    override suspend fun setShowAdult(on: Boolean): AppResult<Session> {
        val user = local.user() ?: return AppResult.Err(AppError.Unauthorized)
        return when (val result = remote.putContentSettings(on)) {
            is AppResult.Ok -> AppResult.Ok(keepUser(user.copy(showAdult = result.value.showAdult)))
            is AppResult.Err -> result
        }
    }

    private suspend fun AppResult<SessionDto>.keep(): AppResult<Session> {
        if (this is AppResult.Ok) {
            local.save(value)
            local.savePendingProfile(null)
        }
        return map { it.user.toDomain() }.also { result -> if (result is AppResult.Ok) current.value = result.value }
    }

    private suspend fun keepUser(user: UserDto): Session {
        local.saveUser(user)
        return user.toDomain().also { current.value = it }
    }

    /** Sends a profile change left from an offline moment; the error when it could not be sent. */
    private suspend fun sendPending(): AppError? {
        val patch = local.pendingProfile()?.let(::decode) ?: return null
        return when (val result = remote.updateMe(patch)) {
            is AppResult.Ok -> {
                local.savePendingProfile(null)
                keepUser(result.value)
                null
            }
            is AppResult.Err -> {
                // Refused for good (an unknown value): dropped, the server's profile stands.
                if (result.error != AppError.Network && result.error != AppError.Unauthorized) local.savePendingProfile(null)
                result.error
            }
        }
    }

    private suspend fun clearEverything() {
        local.clear()
        current.value = null
        onSignedOut()
    }

    private fun decode(json: String): JsonObject? = runCatching { ApiJson.decodeFromString(JsonObject.serializer(), json) }.getOrNull()

    private fun merge(
        older: JsonObject?,
        newer: JsonObject,
    ): JsonObject = JsonObject(older.orEmpty() + newer)
}

/** Only the fields the change sets; `null` clears region and time zone. */
internal fun ProfileChange.toJson(): JsonObject =
    buildJsonObject {
        displayName?.let { put("displayName", JsonPrimitive(it)) }
        locale?.let { put("locale", JsonPrimitive(it)) }
        (region as? Change.Set)?.let { put("region", it.value?.let(::JsonPrimitive) ?: JsonNull) }
        (timeZone as? Change.Set)?.let { put("timeZone", it.value?.let(::JsonPrimitive) ?: JsonNull) }
        onboardingCompleted?.let { put("onboardingCompleted", JsonPrimitive(it)) }
    }

/** The change as the server will apply it, shown on the device at once. */
internal fun UserDto.applying(change: ProfileChange): UserDto =
    copy(
        displayName = change.displayName ?: displayName,
        name = change.displayName ?: name,
        locale = change.locale ?: locale,
        region = (change.region as? Change.Set)?.value ?: region.takeIf { change.region == Change.Keep },
        timeZone = (change.timeZone as? Change.Set)?.value ?: timeZone.takeIf { change.timeZone == Change.Keep },
        onboarding = if (change.onboardingCompleted == true) (onboarding ?: OnboardingDto()).copy(due = false) else onboarding,
    )

internal fun UserDto.toDomain() =
    Session(
        userId = id,
        email = email,
        displayName = shownName,
        roles = listOfNotNull(roleLabel.ifBlank { null }),
        permissions = permissions.toSet(),
        isSuperAdmin = isSuperAdmin,
        features = features.toSet(),
        emailVerified = emailVerified,
        locale = locale,
        region = region,
        timeZone = timeZone,
        onboardingDue = onboarding?.due == true,
        deletionScheduledAtMillis = deletion?.scheduledFor?.let(::parseIsoMillis),
        hasPassword = hasPassword,
        showAdult = showAdult,
    )
