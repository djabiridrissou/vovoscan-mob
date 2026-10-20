package com.vovoscan.feature.library.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

data class LibraryEntry(
    val titleId: String,
    val titleName: String,
    val coverUrl: String?,
    val lastChapterId: String?,
    val lastPage: Int,
    /** Heure de la dernière modification (appareil ou serveur), en ms. */
    val updatedAt: Long,
    /** Modifiée sur l'appareil, pas encore envoyée. */
    val pendingSync: Boolean,
)

/** Ce que l'écran d'un titre sait de lui au moment de le suivre. */
data class TitleRef(
    val id: String,
    val name: String,
    val coverUrl: String?,
)

/**
 * Bibliothèque hors ligne d'abord : chaque action s'applique tout de suite à la base locale et part dans une file
 * d'envoi ; [sync] vide la file puis récupère les changements des autres appareils. Le serveur départage
 * (last-write-wins sur l'heure de l'action).
 */
interface LibraryRepository {
    fun observe(): Flow<List<LibraryEntry>>

    fun isFollowed(titleId: String): Flow<Boolean>

    suspend fun follow(title: TitleRef)

    suspend fun unfollow(titleId: String)

    suspend fun saveProgress(
        titleId: String,
        chapterId: String,
        page: Int,
    )

    /** Envoie la file, puis relit les changements ; sans réseau, la file attend la prochaine fois. */
    suspend fun sync(): AppResult<Unit>

    suspend fun clearLocal()
}
