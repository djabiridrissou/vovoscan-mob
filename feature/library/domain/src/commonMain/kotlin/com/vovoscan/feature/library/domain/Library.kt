package com.vovoscan.feature.library.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** Un titre de la bibliothèque, repéré par sa source et son identifiant chez elle. */
data class LibraryEntry(
    val sourceKey: String,
    val titleRef: String,
    val titleName: String,
    val coverUrl: String?,
    val lastChapterRef: String?,
    val lastPage: Int,
    /** Heure de la dernière modification (appareil ou serveur), en ms. */
    val updatedAt: Long,
    /** Modifiée sur l'appareil, pas encore envoyée. */
    val pendingSync: Boolean,
)

/** Ce que l'écran d'un titre sait de lui au moment de le suivre (nom et couverture gardés pour la bibliothèque). */
data class TitleRef(
    val sourceKey: String,
    val id: String,
    val name: String,
    val coverUrl: String?,
)

/**
 * Bibliothèque hors ligne d'abord, toutes sources confondues : chaque action s'applique tout de suite à la base
 * locale et part dans une file d'envoi ; [sync] vide la file puis récupère les changements des autres appareils.
 * Le serveur départage (last-write-wins sur l'heure de l'action).
 */
interface LibraryRepository {
    fun observe(): Flow<List<LibraryEntry>>

    fun isFollowed(
        sourceKey: String,
        titleRef: String,
    ): Flow<Boolean>

    suspend fun follow(title: TitleRef)

    suspend fun unfollow(
        sourceKey: String,
        titleRef: String,
    )

    suspend fun saveProgress(
        sourceKey: String,
        titleRef: String,
        chapterRef: String,
        page: Int,
    )

    /** Envoie la file, puis relit les changements ; sans réseau, la file attend la prochaine fois. */
    suspend fun sync(): AppResult<Unit>

    suspend fun clearLocal()
}
