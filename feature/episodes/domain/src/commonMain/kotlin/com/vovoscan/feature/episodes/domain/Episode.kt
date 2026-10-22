package com.vovoscan.feature.episodes.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Where an episode's run is (`EpisodeStatus` on the server): `queued` → `analyzing` (ANALYZE) → `reading` (OCR) →
 * `translating` (TRANSLATE, an episode in another language) → `voicing` (VOICE) → `timing` (TIMELINE) → `ready`, or
 * `failed`.
 */
enum class EpisodeStatus(
    val wire: String,
) {
    Queued("queued"),
    Analyzing("analyzing"),
    Reading("reading"),
    Translating("translating"),
    Voicing("voicing"),
    Timing("timing"),
    Ready("ready"),
    Failed("failed"),
    ;

    /** A run is in progress: prepare and reread answer `409 EPISODE_BUSY`. */
    val active: Boolean get() = this != Ready && this != Failed

    companion object {
        fun from(wire: String): EpisodeStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** The stages of an episode run, in the brand's pipeline vocabulary (UPPERCASE in chips); TRANSLATE only in a language. */
enum class PipelineStage(
    val label: String,
) {
    Analyze("ANALYZE"),
    Ocr("OCR"),
    Translate("TRANSLATE"),
    Voice("VOICE"),
    Timeline("TIMELINE"),
    ;

    companion object {
        fun from(wire: String?): PipelineStage? = entries.firstOrNull { it.label == wire }

        fun of(status: EpisodeStatus): PipelineStage? =
            when (status) {
                EpisodeStatus.Queued, EpisodeStatus.Analyzing -> Analyze
                EpisodeStatus.Reading -> Ocr
                EpisodeStatus.Translating -> Translate
                EpisodeStatus.Voicing -> Voice
                EpisodeStatus.Timing -> Timeline
                EpisodeStatus.Ready, EpisodeStatus.Failed -> null
            }
    }
}

/** `{stage, step?, done?, total?}`: e.g. OCR, "pages", 3 of 12; ANALYZE "queued"; READY once done. */
data class StageProgress(
    val stage: String,
    val step: String?,
    val done: Int?,
    val total: Int?,
)

/** The render an episode plays: which engines made it, and whether it is degraded or silent. */
data class RenderSummary(
    val id: String,
    val renderKey: String,
    val analyzer: String?,
    val ocr: String?,
    val voice: String?,
    val degraded: Boolean,
    val silent: Boolean,
    val createdAtMillis: Long,
)

/** Where the MP4 of the render an episode plays is (`ExportStatus` on the server). */
enum class ExportStatus(
    val wire: String,
) {
    /** Never made (or made by an older export version). */
    None("none"),
    Queued("queued"),
    Rendering("rendering"),
    Ready("ready"),
    Failed("failed"),
    ;

    /** Being made: the live stream keeps going until it is ready or failed. */
    val active: Boolean get() = this == Queued || this == Rendering

    companion object {
        fun from(wire: String): ExportStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The MP4 of an episode's render (`ExportOut`): [progress] 0-100; once ready, [url] (a signed download, saved as an
 * attachment named after the chapter) and [streamUrl] (the same file inline). Shared like renders: CPU only, no key.
 */
data class EpisodeExport(
    val status: ExportStatus,
    val progress: Int,
    val url: String?,
    val streamUrl: String?,
    val error: String?,
    /** The render was built by older code: preparing again rebuilds it first. */
    val outdated: Boolean,
)

/**
 * A chapter's episode, shared with everyone who can see the chapter. [degraded] and [silent] come from the render
 * it plays (null without one); [outdated]: the pages changed or the render was built by older code; [canReread]:
 * the server says this user may run it again with their own keys (the truth: never computed on the device);
 * [export]: the MP4 of its render (null without a render of the chapter's pages as they are now).
 */
data class Episode(
    val chapterId: String,
    val status: EpisodeStatus,
    val progress: StageProgress?,
    val error: String?,
    val pageCount: Int,
    val degraded: Boolean?,
    val silent: Boolean?,
    val render: RenderSummary?,
    val outdated: Boolean,
    val canReread: Boolean,
    val updatedAtMillis: Long,
    val export: EpisodeExport? = null,
    /** "" = the chapter's own language; else the language code of this episode. */
    val language: String = "",
    /** How an episode in another language sounds; null for the original. */
    val audio: EpisodeAudio? = null,
) {
    /** Something is still being made (the run, or its MP4): worth following live. */
    val inProgress: Boolean get() = status.active || export?.status?.active == true

    /** The stage running now; null once ready or failed. */
    val stage: PipelineStage?
        get() = if (status.active) PipelineStage.from(progress?.stage) ?: PipelineStage.of(status) else null
}

/** An episode in another language: voiced in it, or the original voices with captions in it. */
enum class EpisodeAudio(
    val wire: String,
) {
    /** ElevenLabs speaks the language: the lines are performed in it. */
    Dub("dub"),

    /** The original voices, with the translation as captions. */
    Sub("sub"),
    ;

    companion object {
        fun from(wire: String?): EpisodeAudio? = entries.firstOrNull { it.wire == wire }
    }
}

/** What `POST …/episodes/chapters/{id}` (prepare) or `…/reread` led to. */
sealed interface PrepareResult {
    /** A run was queued, paid with the user's own keys. */
    data class Queued(
        val episode: Episode,
    ) : PrepareResult

    /** A usable render of these pages already existed: nothing was queued, nobody paid twice. */
    data class AlreadyReady(
        val episode: Episode,
    ) : PrepareResult

    /** `409 EPISODE_BUSY`: a run is already in progress (someone else's, or an earlier tap); follow it. */
    data class Busy(
        val episode: Episode?,
    ) : PrepareResult
}

/** What the player needs (`GET …/watch`): the timeline, a signed URL per page and per sound, and their expiry. */
data class Watch(
    val chapterId: String,
    val renderKey: String,
    val timeline: Timeline,
    /** One per timeline page, in order. */
    val pageUrls: List<String>,
    /** Audio key → signed URL (voice lines, panel sounds, beds, music cues); empty without sound. */
    val audioUrls: Map<String, String>,
    val expiresAtMillis: Long,
)

/** Which of the user's own keys an episode they prepare would use. Hints only (last 4 characters). */
data class KeyStatus(
    /** False: the server stores no keys at all. */
    val enabled: Boolean,
    val anthropicHint: String?,
    val elevenLabsHint: String?,
)

/**
 * The user's provider keys, as the episodes need to know them. A feature port: the app wires it to the account
 * slice, without episodes knowing that feature.
 */
interface KeyStatusProvider {
    /** Null until the key list was read once. */
    fun observe(): Flow<KeyStatus?>

    /** Reads the list again from the server (errors ignored: the list on the device stays). */
    suspend fun refresh()
}

/**
 * Whether this account may use episodes (the `video` feature flag): otherwise the episode card says "coming soon"
 * and offers nothing. A feature port wired by the app to the session; the server checks every call again.
 */
fun interface EpisodeAccess {
    fun observe(): Flow<Boolean>

    companion object {
        /** Every account may use episodes (tests, servers without feature flags wired). */
        val Open = EpisodeAccess { flowOf(true) }
    }
}

/** What preparing an episode would cost this account, from the credits wallet. */
data class RunCost(
    val credits: Long,
    val coveredByOwnKeys: Boolean,
    val alreadyAvailable: Boolean,
)

/** Cost quotes before a run. A feature port wired by the app to the account's wallet. */
fun interface RunQuotes {
    /**
     * The cost of the episode in [lang] ("" = the original). Null: no quote (a server without credits, offline): the
     * card says nothing about credits.
     */
    suspend fun episode(
        chapterId: String,
        lang: String,
    ): RunCost?

    companion object {
        val None = RunQuotes { _, _ -> null }
    }
}

/**
 * Episodes, offline first: the last known episode of a chapter and its last watch are kept on the device. Live
 * progress comes from server-sent events (`…/events`), with polling as a fallback. Every call takes the episode's
 * language (`?lang=`, "" = the original): the same pages in the same language are one episode, shared by everyone.
 */
interface EpisodesRepository {
    /** The episode kept on the device; null when none is known (never fetched, or no episode yet). */
    fun observe(
        chapterId: String,
        lang: String = "",
    ): Flow<Episode?>

    /** `Ok(null)`: the chapter has no episode yet (`404 EPISODE_NOT_FOUND`). */
    suspend fun refresh(
        chapterId: String,
        lang: String = "",
    ): AppResult<Episode?>

    suspend fun prepare(
        chapterId: String,
        lang: String = "",
    ): AppResult<PrepareResult>

    suspend fun reread(
        chapterId: String,
        lang: String = "",
    ): AppResult<PrepareResult>

    /**
     * Follows the episode live until it is ready or failed (or the caller is cancelled: a screen that stops stops
     * following), saving each snapshot on the device. Reconnects with backoff; polls every 10 s when events fail.
     * `Ok(null)`: the episode went away.
     */
    suspend fun follow(
        chapterId: String,
        lang: String = "",
    ): AppResult<Episode?>

    /** The watch kept on the device while its URLs are valid for a while, else a fresh one ([forceRefresh] always). */
    suspend fun watch(
        chapterId: String,
        forceRefresh: Boolean = false,
        lang: String = "",
    ): AppResult<Watch>

    /**
     * `POST …/export`: makes the MP4 of the render the chapter plays (or returns the one ready or being made), and
     * saves it on the stored episode so the screen shows its progress at once.
     */
    suspend fun requestExport(
        chapterId: String,
        lang: String = "",
    ): AppResult<EpisodeExport>

    /** `GET …/export`: the MP4's state with freshly signed URLs (a download needs a link that has not expired). */
    suspend fun export(
        chapterId: String,
        lang: String = "",
    ): AppResult<EpisodeExport>

    suspend fun clearLocal()
}
