package com.vovoscan.feature.sources.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** Who reads the source: the vovoscan server (our titles) or the device (rules manifest, content never on our side). */
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
    /** "comic", "book"… */
    val kinds: List<String>,
    val langs: List<String>,
    val enabled: Boolean,
    val position: Int,
    /** Choice made offline, not sent yet. */
    val pendingSync: Boolean,
)

/**
 * My sources, offline first: the list kept on the device (with manifests of the sources read on the device)
 * shows at once; a change (visibility, order) applies locally, then goes to the server.
 */
interface SourcesRepository {
    fun observe(): Flow<List<SourceItem>>

    suspend fun refresh(): AppResult<Unit>

    /** All sources, in the desired order, each visible or not. */
    suspend fun save(choices: List<Pair<String, Boolean>>): AppResult<Unit>

    suspend fun clearLocal()
}
