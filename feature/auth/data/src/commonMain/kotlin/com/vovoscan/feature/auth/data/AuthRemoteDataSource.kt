package com.vovoscan.feature.auth.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.network.apiCallUnit
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    val roleName: String = "",
    val isSuperAdmin: Boolean = false,
    /** « module:action » ; « * » pour un super-admin. */
    val permissions: List<String> = emptyList(),
)

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
)

/** Source distante : l'API d'authentification (`api/v1/auth/…`). */
interface AuthRemoteDataSource {
    suspend fun signIn(
        email: String,
        password: String,
    ): AppResult<SessionDto>

    suspend fun redeemInvite(
        code: String,
        email: String,
        password: String,
        displayName: String,
    ): AppResult<SessionDto>

    suspend fun me(): AppResult<UserDto>

    suspend fun signOut(): AppResult<Unit>
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

    override suspend fun redeemInvite(
        code: String,
        email: String,
        password: String,
        displayName: String,
    ): AppResult<SessionDto> =
        http
            .apiCall<ApiData<SessionDto>> {
                post("api/v1/auth/invites/redeem") {
                    contentType(ContentType.Application.Json)
                    setBody(RedeemInviteBody(code, email, password, displayName))
                }
            }.map { it.data }

    override suspend fun me(): AppResult<UserDto> = http.apiCall<ApiData<UserDto>> { get("api/v1/auth/me") }.map { it.data }

    override suspend fun signOut(): AppResult<Unit> = http.apiCallUnit { post("api/v1/auth/sign-out") }
}
