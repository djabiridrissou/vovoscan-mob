package com.vovoscan.feature.auth.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Utilisateur connecté. [permissions] vient du serveur (« module:action », « * » pour un super-admin) :
 * l'interface s'en sert seulement pour masquer ce qui serait refusé ; le serveur revérifie chaque appel.
 */
data class Session(
    val userId: String,
    val email: String,
    val displayName: String,
    val roles: List<String>,
    val permissions: Set<String>,
) {
    fun can(permission: String): Boolean = permission in permissions || "*" in permissions

    fun can(
        module: String,
        action: String,
    ): Boolean = can("$module:$action")
}

interface AuthRepository {
    /** Session courante : restaurée du coffre au lancement, même hors ligne. */
    val session: StateFlow<Session?>

    /** Relit la session gardée sur l'appareil, puis la confirme auprès du serveur si le réseau répond. */
    suspend fun restore()

    suspend fun signIn(
        email: String,
        password: String,
    ): AppResult<Session>

    /** Inscription sur invitation, à usage unique (marque : « invite-only »). */
    suspend fun redeemInvite(
        code: String,
        email: String,
        password: String,
        displayName: String,
    ): AppResult<Session>

    /** Révoque la session côté serveur si possible, et efface tout ce qui est sur l'appareil. */
    suspend fun signOut()
}
