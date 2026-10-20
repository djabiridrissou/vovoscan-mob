package com.vovoscan.feature.sources.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** Qui lit la source : le serveur vovoscan (nos titres) ou l'appareil (manifeste de règles, contenu jamais chez nous). */
enum class SourceEngine {
    Internal,
    OnDevice,
    ;

    companion object {
        fun fromApi(value: String): SourceEngine = if (value == "internal") Internal else OnDevice
    }
}

data class SourceItem(
    val key: String,
    val name: String,
    val engine: SourceEngine,
    val version: String,
    /** « comic », « book »… */
    val kinds: List<String>,
    val langs: List<String>,
    val enabled: Boolean,
    val position: Int,
    /** Choix fait hors ligne, pas encore envoyé. */
    val pendingSync: Boolean,
)

/**
 * Mes sources, hors ligne d'abord : la liste gardée sur l'appareil (avec les manifestes des sources lues sur
 * l'appareil) s'affiche tout de suite ; un changement (visible, ordre) s'applique en local puis part au serveur.
 */
interface SourcesRepository {
    fun observe(): Flow<List<SourceItem>>

    suspend fun refresh(): AppResult<Unit>

    /** Toutes les sources, dans l'ordre voulu, chacune visible ou non. */
    suspend fun save(choices: List<Pair<String, Boolean>>): AppResult<Unit>

    suspend fun clearLocal()
}
