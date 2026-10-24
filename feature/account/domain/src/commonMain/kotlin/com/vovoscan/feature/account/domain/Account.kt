package com.vovoscan.feature.account.domain

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import kotlinx.coroutines.flow.Flow

/** A paid provider the user brings their own key for (`core/providers.py` on the server). */
enum class Provider(
    val wire: String,
    val label: String,
) {
    Anthropic("anthropic", "Anthropic"),
    ElevenLabs("elevenlabs", "ElevenLabs"),
    ;

    companion object {
        fun from(wire: String): Provider? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One of the user's active keys as the server describes it: never the secret, only its last 4 characters ([hint]).
 * Times are epoch milliseconds.
 */
data class ProviderKey(
    val id: String,
    val provider: Provider,
    val hint: String,
    val label: String?,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long?,
)

/** `enabled` false: the server has no key encryption key, so keys can't be stored there (`BYOK_DISABLED`). */
data class ProviderKeys(
    val enabled: Boolean,
    val keys: List<ProviderKey>,
) {
    fun keyFor(provider: Provider): ProviderKey? = keys.firstOrNull { it.provider == provider }
}

/** Rules the server applies to a new key, checked before anything is sent (`AddProviderKeyBody`). */
object KeyRules {
    const val MIN_LENGTH = 20
    const val MAX_LENGTH = 512
    const val LABEL_MAX = 60

    /** Printable ASCII without spaces, 20–512 characters once trimmed. */
    fun secretLooksValid(secret: String): Boolean {
        val trimmed = secret.trim()
        return trimmed.length in MIN_LENGTH..MAX_LENGTH && trimmed.all { it.code in 0x21..0x7e }
    }

    fun labelValid(label: String): Boolean = label.trim().length <= LABEL_MAX
}

/** The Claude model that reads the bubbles of the chapters the user prepares, cheapest first. */
enum class ReadingModel(
    val wire: String,
    private val key: String,
) {
    Haiku("claude-haiku-5-5", "keys.modelHaiku"),
    Sonnet("claude-sonnet-5-5", "keys.modelSonnet"),
    Opus("claude-opus-5-5", "keys.modelOpus"),
    ;

    /** "Haiku — fastest, cheapest", in the UI locale. */
    val label: String get() = t(key)

    companion object {
        val Default = Haiku

        fun from(wire: String): ReadingModel? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a cost-log row counts. */
enum class UnitType(
    val wire: String,
) {
    Chars("chars"),
    Tokens("tokens"),
    Seconds("seconds"),
    Requests("requests"),
    ;

    companion object {
        fun from(wire: String): UnitType? = entries.firstOrNull { it.wire == wire }
    }
}

data class UsageTotal(
    val provider: Provider,
    val costUsd: Double,
    val units: Map<UnitType, Double>,
)

data class UsageEntry(
    val id: String,
    val provider: Provider,
    val model: String?,
    val unitType: UnitType,
    val units: Double,
    val costUsd: Double,
    val chapterId: String?,
    val createdAtMillis: Long,
)

/** The user's own cost log over a period: totals per provider and the latest 100 rows. */
data class Usage(
    val totals: List<UsageTotal>,
    val entries: List<UsageEntry>,
)

/** Keys stored on the server for the user. Only the list (hint, label, dates) is kept on the device. */
interface ProviderKeysRepository {
    /** The list kept on the device; null until the first successful refresh. */
    fun observeKeys(): Flow<ProviderKeys?>

    suspend fun refreshKeys(): AppResult<Unit>

    /**
     * Sends [secret] once, in the request body, and keeps nothing of it: the server checks it with the provider,
     * then answers with the hint. Replaces the user's previous key for that provider. Needs the network.
     */
    suspend fun add(
        provider: Provider,
        secret: String,
        label: String?,
    ): AppResult<ProviderKey>

    /** Revokes a key. Idempotent: a key already gone counts as removed. */
    suspend fun remove(id: String): AppResult<Unit>

    suspend fun clearLocal()
}

interface ReadingSettingsRepository {
    /** The model kept on the device; null until the first successful refresh. */
    fun observeReadingModel(): Flow<ReadingModel?>

    suspend fun refreshReadingModel(): AppResult<Unit>

    /** Needs the network; the local copy follows the server's answer. */
    suspend fun set(model: ReadingModel): AppResult<ReadingModel>

    /** The content language chapters are translated into (`Language.ALL` code), kept on the device; null until chosen. */
    fun observeTargetLanguage(): Flow<String?>

    /** Needs the network, like the model. */
    suspend fun setTargetLanguage(code: String): AppResult<String>

    /**
     * The reading formats (`manga`, `webtoon`, `comic`) whose reader guide this account has seen (reader SPEC §10);
     * `Rejected(COACHED_UNSUPPORTED)` from a server without that slot (the device keeps its own copy meanwhile).
     */
    suspend fun coached(): AppResult<Set<String>> = AppResult.Ok(emptySet())

    /** One more guide seen, on the account (idempotent). */
    suspend fun markCoached(kind: String): AppResult<Unit> = AppResult.Ok(Unit)

    /**
     * The reading mode chosen per series on the account, `"<sourceKey>:<titleRef>"` → mode (kebab case);
     * `Rejected(SERIES_MODES_UNSUPPORTED)` from a server without that slot.
     */
    suspend fun seriesModes(): AppResult<Map<String, String>> = AppResult.Err(AppError.Rejected(SERIES_MODES_UNSUPPORTED, ""))

    /** One series' mode on the account (null: back to the format's default). Idempotent. */
    suspend fun setSeriesMode(
        sourceKey: String,
        titleRef: String,
        mode: String?,
    ): AppResult<Unit> = AppResult.Err(AppError.Rejected(SERIES_MODES_UNSUPPORTED, ""))
}

interface UsageRepository {
    /** `from` inclusive, `to` exclusive, epoch milliseconds. Not kept on the device. */
    suspend fun usage(
        fromMillis: Long?,
        toMillis: Long?,
    ): AppResult<Usage>
}

/** A server without the account's reader-guide slot (`coached`): nothing to read there, nothing to send. */
const val COACHED_UNSUPPORTED = "COACHED_UNSUPPORTED"

/** A server without the per-series reading modes (`seriesModes`): the device's own choices stand. */
const val SERIES_MODES_UNSUPPORTED = "SERIES_MODES_UNSUPPORTED"
