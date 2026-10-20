package com.vovoscan.feature.auth.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hors ligne d'abord : la session du coffre est rendue tout de suite, puis confirmée par `GET /auth/me`.
 * Seul un 401 la retire (session révoquée) ; une coupure réseau la garde.
 */
class AuthRepositoryImpl(
    private val remote: AuthRemoteDataSource,
    private val local: SessionLocalDataSource,
    /** Appelé à la déconnexion : les autres features effacent leurs données locales. */
    private val onSignedOut: suspend () -> Unit = {},
) : AuthRepository {
    private val current = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = current.asStateFlow()

    override suspend fun restore() {
        if (local.token() == null) return
        current.value = local.user()?.toDomain()
        when (val me = remote.me()) {
            is AppResult.Ok -> {
                local.saveUser(me.value)
                current.value = me.value.toDomain()
            }
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
    ): AppResult<Session> = remote.redeemInvite(code, email.trim(), password, displayName.trim()).keep()

    override suspend fun signOut() {
        remote.signOut()
        clearEverything()
    }

    private suspend fun AppResult<SessionDto>.keep(): AppResult<Session> {
        if (this is AppResult.Ok) local.save(value)
        return map { it.user.toDomain() }.also { result -> if (result is AppResult.Ok) current.value = result.value }
    }

    private suspend fun clearEverything() {
        local.clear()
        current.value = null
        onSignedOut()
    }
}

internal fun UserDto.toDomain() = Session(id, email, displayName, listOfNotNull(roleName.ifBlank { null }), permissions.toSet())
