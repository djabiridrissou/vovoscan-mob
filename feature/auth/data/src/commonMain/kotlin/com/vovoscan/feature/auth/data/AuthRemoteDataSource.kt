package com.vovoscan.feature.auth.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.network.apiCallUnit
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The signed-in user. Two server generations: `displayName` / `roleName` (vovoscan-be v1) and `name` / `role` (the
 * unified API); both are read, the first non-blank wins.
 */
@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String = "",
    val name: String = "",
    val roleName: String = "",
    val role: String = "",
    val isSuperAdmin: Boolean = false,
    /** "module:action"; "*" for a super-admin. */
    val permissions: List<String> = emptyList(),
    /** Feature flags of the account (`video`…); absent on servers without flags. */
    val features: List<String> = emptyList(),
    val emailVerified: Boolean = true,
    // i18n/SPEC.md §3: absent on older servers.
    val locale: String? = null,
    val region: String? = null,
    val timeZone: String? = null,
    val onboarding: OnboardingDto? = null,
    val deletion: DeletionDto? = null,
    val hasPassword: Boolean = true,
) {
    val shownName: String get() = displayName.ifBlank { name }
    val roleLabel: String get() = roleName.ifBlank { role }
}

@Serializable
data class OnboardingDto(
    val completedAt: String? = null,
    val version: Int = 0,
    val due: Boolean = false,
)

@Serializable
data class DeletionDto(
    val scheduledFor: String,
)

@Serializable
data class DeviceSessionDto(
    val id: String,
    val client: String = "",
    val current: Boolean = false,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val ip: String? = null,
    val device: String = "",
)

@Serializable
internal data class RevokedDto(
    val revoked: Int = 0,
)

@Serializable
internal data class PasswordChangeBody(
    val currentPassword: String,
    val newPassword: String,
) {
    override fun toString(): String = "PasswordChangeBody(<hidden>)"
}

@Serializable
internal data class EmailChangeBody(
    val password: String,
    val newEmail: String,
) {
    override fun toString(): String = "EmailChangeBody(<hidden>)"
}

@Serializable
internal data class PasswordBody(
    val password: String,
) {
    override fun toString(): String = "PasswordBody(<hidden>)"
}

@Serializable
data class SessionDto(
    val token: String,
    val user: UserDto,
)

@Serializable
internal data class SignInBody(
    val email: String,
    val password: String,
)

@Serializable
internal data class RedeemInviteBody(
    val code: String,
    val email: String,
    val password: String,
    val displayName: String,
    val locale: String? = null,
    val region: String? = null,
    val timeZone: String? = null,
    val ageConfirmed: Boolean = true,
) {
    override fun toString(): String = "RedeemInviteBody(email=$email, password=<hidden>)"
}

/** Remote source: the authentication API (`api/v1/auth/…`). */
interface AuthRemoteDataSource {
    suspend fun signIn(
        email: String,
        password: String,
    ): AppResult<SessionDto>

    suspend fun redeemInvite(body: RedeemInvite): AppResult<SessionDto>

    suspend fun me(): AppResult<UserDto>

    suspend fun signOut(): AppResult<Unit>

    /** `PATCH /me` with only the fields to change (`null` clears region and time zone). */
    suspend fun updateMe(patch: JsonObject): AppResult<UserDto>

    suspend fun sessions(): AppResult<List<DeviceSessionDto>>

    suspend fun revokeSession(id: String): AppResult<Unit>

    suspend fun revokeOthers(): AppResult<Int>

    suspend fun changePassword(
        current: String,
        new: String,
    ): AppResult<Unit>

    suspend fun changeEmail(
        password: String,
        newEmail: String,
    ): AppResult<Unit>

    suspend fun scheduleDeletion(password: String): AppResult<DeletionDto>

    suspend fun cancelDeletion(): AppResult<Unit>
}

/** Sign-up fields; the password is never printed. */
class RedeemInvite(
    val code: String,
    val email: String,
    val password: String,
    val displayName: String,
    val locale: String? = null,
    val region: String? = null,
    val timeZone: String? = null,
    val ageConfirmed: Boolean = true,
) {
    override fun toString(): String = "RedeemInvite(email=$email, password=<hidden>)"
}

class KtorAuthRemoteDataSource(
    private val http: HttpClient,
) : AuthRemoteDataSource {
    override suspend fun signIn(
        email: String,
        password: String,
    ): AppResult<SessionDto> =
        http
            .apiCall<ApiData<SessionDto>> {
                post("api/v1/auth/sign-in") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInBody(email, password))
                }
            }.map { it.data }

    override suspend fun redeemInvite(body: RedeemInvite): AppResult<SessionDto> =
        http
            .apiCall<ApiData<SessionDto>> {
                post("api/v1/auth/invites/redeem") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        with(body) { RedeemInviteBody(code, email, password, displayName, locale, region, timeZone, ageConfirmed) },
                    )
                }
            }.map { it.data }

    override suspend fun me(): AppResult<UserDto> = http.apiCall<ApiData<UserDto>> { get("api/v1/auth/me") }.map { it.data }

    override suspend fun signOut(): AppResult<Unit> = http.apiCallUnit { post("api/v1/auth/sign-out") }

    override suspend fun updateMe(patch: JsonObject): AppResult<UserDto> =
        http
            .apiCall<ApiData<UserDto>> {
                patch("api/v1/me") {
                    contentType(ContentType.Application.Json)
                    setBody(patch)
                }
            }.map { it.data }

    override suspend fun sessions(): AppResult<List<DeviceSessionDto>> =
        http.apiCall<ApiData<List<DeviceSessionDto>>> { get("api/v1/me/sessions") }.map { it.data }

    override suspend fun revokeSession(id: String): AppResult<Unit> = http.apiCallUnit { delete("api/v1/me/sessions/$id") }

    override suspend fun revokeOthers(): AppResult<Int> =
        http.apiCall<ApiData<RevokedDto>> { post("api/v1/me/sessions/revoke-others") }.map { it.data.revoked }

    override suspend fun changePassword(
        current: String,
        new: String,
    ): AppResult<Unit> =
        http.apiCallUnit {
            post("api/v1/me/password") {
                contentType(ContentType.Application.Json)
                setBody(PasswordChangeBody(current, new))
            }
        }

    override suspend fun changeEmail(
        password: String,
        newEmail: String,
    ): AppResult<Unit> =
        http.apiCallUnit {
            post("api/v1/me/email") {
                contentType(ContentType.Application.Json)
                setBody(EmailChangeBody(password, newEmail))
            }
        }

    override suspend fun scheduleDeletion(password: String): AppResult<DeletionDto> =
        http
            .apiCall<ApiData<DeletionDto>> {
                post("api/v1/me/deletion") {
                    contentType(ContentType.Application.Json)
                    setBody(PasswordBody(password))
                }
            }.map { it.data }

    override suspend fun cancelDeletion(): AppResult<Unit> = http.apiCallUnit { delete("api/v1/me/deletion") }
}
