package com.vovoscan.feature.episodes.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.Language
import com.vovoscan.core.common.i18n.displayName
import com.vovoscan.core.common.i18n.displayTitle
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.episodes.domain.EpisodeAudio
import com.vovoscan.feature.episodes.domain.ProviderFunding
import com.vovoscan.feature.episodes.domain.RunCost
import com.vovoscan.feature.episodes.domain.StageProgress

/** Every user-facing string of the episode card and the player (brand tone: calm, short, ✦ separator). */
object EpisodeText {
    val NONE: String get() = t("episode.none")
    val NONE_DETAIL: String get() = t("episode.noneDetail")
    val RUNNING: String get() = t("episode.running")
    val READY: String get() = t("episode.ready")
    val FAILED: String get() = t("episode.failed")
    val LOADING: String get() = t("episode.loading")
    val IMPORTING: String get() = t("episode.importing")
    val IMPORTING_DETAIL: String get() = t("episode.importingDetail")
    val IMPORT_NOTE: String get() = t("episode.importNote")
    val IMPORTED: String get() = t("episode.imported")

    fun importedPages(count: Int): String = t("episode.importedPages", "count" to count)

    val OFFLINE_COPY: String get() = t("episode.offlineCopy")
    val UNAVAILABLE_OFFLINE: String get() = t("episode.unavailableOffline")
    val PREPARE: String get() = t("episode.prepare")
    val PREPARE_AGAIN: String get() = t("episode.prepareAgain")
    val TRY_AGAIN: String get() = t("episode.tryAgain")
    val REREAD: String get() = t("episode.reread")
    val WATCH: String get() = t("episode.watch")
    val QUEUED: String get() = t("episode.queued")
    val BUSY: String get() = t("episode.busy")
    val ALREADY_READY: String get() = t("episode.alreadyReady")
    val STARTED: String get() = t("episode.started")
    val BADGE_DEGRADED: String get() = t("episode.badgeDegraded")
    val BADGE_SILENT: String get() = t("episode.badgeSilent")
    val BADGE_OUTDATED: String get() = t("episode.badgeOutdated")
    val NOTE_DEGRADED: String get() = t("episode.noteDegraded")
    val NOTE_SILENT: String get() = t("episode.noteSilent")
    val NOTE_OUTDATED: String get() = t("episode.noteOutdated")
    val NOTE_REREAD: String get() = t("episode.noteReread")

    // Languages (`?lang=`): the original, or the episode dubbed / subtitled in another language.
    val ORIGINAL: String get() = t("episode.original")
    val ORIGINAL_DETAIL: String get() = t("episode.originalDetail")
    val READY_FREE: String get() = t("episode.readyFree")
    val DUBBED: String get() = t("episode.dubbed")
    val SUBTITLED: String get() = t("episode.subtitled")
    val BETA: String get() = t("episode.beta")
    val CHECKING: String get() = t("translation.offer.checking")
    val KEEP_WATCHING: String get() = t("player.lang.keepWatching")
    val NOT_NOW: String get() = t("translation.offer.cancel")
    val CANCEL: String get() = t("common.cancel")
    val CLOSE: String get() = t("common.close")

    /** "Switching to Français…". */
    fun switching(code: String): String = t("player.lang.switching", "language" to (Language.of(code)?.native ?: ORIGINAL))

    fun languageDetail(language: Language): String =
        listOfNotNull(language.displayTitle(), if (language.dub) DUBBED else SUBTITLED, BETA.takeIf { language.beta }).joinToString(" ✦ ")

    /** Under a ready episode in another language: how it sounds. */
    fun audioNote(
        language: Language,
        audio: EpisodeAudio?,
    ): String = t(if (audio == EpisodeAudio.Dub) "episode.audioDub" else "episode.audioSub", "language" to language.displayName())

    // The key lines already say what each missing key costs; this only says where to fix it.
    val KEY_WARNING: String get() = t("episode.keyWarning")
    val KEYS_DISABLED: String get() = t("episode.keysDisabled")
    val COST_NOTE: String get() = t("episode.costNote")
    val PLATFORM_NOTE: String get() = t("episode.platformNote")
    val UNFUNDED_NOTE: String get() = t("episode.unfundedNote")
    val UNFUNDED_WARNING: String get() = t("episode.unfundedWarning")
    val OFFLINE_WRITE: String get() = t("episode.offlineWrite")
    val NO_PAGES: String get() = t("episode.noPages")
    val CHAPTER_NOT_FOUND: String get() = t("episode.chapterNotFound")
    val COMING_SOON: String get() = t("episode.comingSoon")
    val COMING_SOON_DETAIL: String get() = t("episode.comingSoonDetail")
    val NO_CREDITS: String get() = t("episode.noCredits")
    val CREDITS_PAUSED: String get() = t("episode.creditsPaused")
    val DAILY_CAP: String get() = t("episode.dailyCap")

    /** Codes saying the feature isn't open to this account (the server's feature flags). */
    val FEATURE_OFF_CODES = setOf("FEATURE_DISABLED", "FEATURE_COMING_SOON")

    /** Who reads the bubbles: the account's key, the platform's (credits), or nobody. */
    fun anthropicLine(
        hint: String?,
        funding: ProviderFunding = ProviderFunding.None,
    ): String =
        when {
            hint != null -> t("episode.anthropicOwn", "hint" to hint)
            funding == ProviderFunding.Platform -> t("episode.anthropicPlatform")
            else -> t("episode.anthropicNone")
        }

    fun elevenLabsLine(
        hint: String?,
        funding: ProviderFunding = ProviderFunding.None,
    ): String =
        when {
            hint != null -> t("episode.elevenLabsOwn", "hint" to hint)
            funding == ProviderFunding.Platform -> t("episode.elevenLabsPlatform")
            else -> t("episode.elevenLabsNone")
        }

    /** "3 / 12 pages", "4 / 30 lines", "Queued", "Step 2 / 5". */
    fun progress(progress: StageProgress?): String? {
        if (progress == null) return null
        if (progress.step == "queued") return QUEUED
        val done = progress.done
        val total = progress.total
        if (done == null || total == null || total <= 0) return null
        return when (progress.step) {
            "pages" -> t("episode.progressPages", "done" to done, "total" to total)
            "lines" -> t("episode.progressLines", "done" to done, "total" to total)
            else -> t("episode.progressStep", "done" to done, "total" to total)
        }
    }

    /** Prepare or reread. */
    fun runError(error: AppError): String =
        when {
            error == AppError.Network -> OFFLINE_WRITE
            error is AppError.Rejected && error.code == "EPISODE_NO_PAGES" -> NO_PAGES
            error is AppError.Rejected && error.code == "CHAPTER_NOT_FOUND" -> CHAPTER_NOT_FOUND
            else -> creditError(error) ?: error.userMessage()
        }

    /** The quote shown with Prepare; null when nothing is charged and nothing is covered (the key lines say why). */
    fun cost(cost: RunCost): String? =
        when {
            cost.alreadyAvailable -> t("episode.costAlreadyMade")
            cost.coveredByOwnKeys -> t("episode.costOwnKeys")
            !cost.meteredRun -> null
            // A price of 0 on an unfundable quote is no price (a server that leaves it out): the balance and what to do.
            !cost.fundable && cost.maxCredits <= 0 ->
                cost.balance?.let { t("episode.costShortNoPrice", "balance" to it) } ?: t("episode.noCredits")
            !cost.fundable ->
                cost.balance?.let { t("episode.costShortBalance", "count" to cost.maxCredits, "balance" to it) }
                    ?: t("episode.costShort", "count" to cost.maxCredits)
            else -> t("episode.costCredits", "count" to cost.credits)
        }

    /** The credits wallet refused a run on the platform's keys; null for any other error. */
    fun creditError(error: AppError): String? =
        when ((error as? AppError.Rejected)?.code) {
            "INSUFFICIENT_CREDITS" -> NO_CREDITS
            "PLATFORM_PAUSED" -> CREDITS_PAUSED
            "DAILY_CAP" -> DAILY_CAP
            else -> null
        }

    // Video (MP4 export).
    val MAKE_VIDEO: String get() = t("episode.makeVideo")
    val SAVE_VIDEO: String get() = t("episode.saveVideo")
    val GETTING_VIDEO: String get() = t("episode.gettingVideo")
    val VIDEO_NONE: String get() = t("episode.videoNone")
    val VIDEO_NONE_DETAIL: String get() = t("episode.videoNoneDetail")
    val VIDEO_QUEUED_LABEL: String get() = t("episode.videoQueuedLabel")
    val VIDEO_QUEUED: String get() = t("episode.videoQueued")
    val VIDEO_READY: String get() = t("episode.videoReady")
    val VIDEO_READY_DETAIL: String get() = t("episode.videoReadyDetail")
    val VIDEO_OUTDATED: String get() = t("episode.videoOutdated")
    val VIDEO_FAILED: String get() = t("episode.videoFailed")
    val VIDEO_GONE: String get() = t("episode.videoGone")
    val VIDEO_DOWNLOAD_FAILED: String get() = t("episode.videoDownloadFailed")
    val VIDEO_OFFLINE: String get() = t("episode.videoOffline")
    val VIDEO_RATE_LIMITED: String get() = t("episode.videoRateLimited")
    const val VIDEO_FILE = "vovoscan episode.mp4"

    /** "Making the video ✦ 42%". */
    fun videoProgress(percent: Int): String = t("episode.videoProgress", "percent" to percent.coerceIn(0, 100))

    /** Making or saving the video. */
    fun exportError(error: AppError): String =
        when {
            error == AppError.Network -> VIDEO_OFFLINE
            error == AppError.RateLimited -> VIDEO_RATE_LIMITED
            error is AppError.Rejected && error.code == "EPISODE_NOT_READY" -> NOT_READY
            error is AppError.Rejected && error.code == "EPISODE_OUTDATED" -> OUTDATED
            error is AppError.Rejected && error.code == "CHAPTER_NOT_FOUND" -> CHAPTER_NOT_FOUND
            else -> error.userMessage()
        }

    /** The file name the server signed into the download link (`name=`), else a plain one. Always `.mp4`. */
    fun videoFileName(url: String): String {
        val query = url.substringAfter('?', "").substringBefore('#')
        val raw =
            query
                .split('&')
                .firstOrNull { it.startsWith("name=") }
                ?.removePrefix("name=")
                ?.let(::percentDecode)
                // No path separators, reserved or control characters in a file name.
                ?.map { if (it < ' ' || it in "/\\:*?\"<>|") ' ' else it }
                ?.joinToString("")
                ?.trim()
                .orEmpty()
        if (raw.isEmpty()) return VIDEO_FILE
        return if (raw.endsWith(".mp4", ignoreCase = true)) raw else "$raw.mp4"
    }

    private fun percentDecode(text: String): String {
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '+' -> bytes += ' '.code.toByte()
                c == '%' && i + 2 < text.length -> {
                    val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes += hex.toByte()
                        i += 3
                        continue
                    }
                    bytes += c.code.toByte()
                }
                else -> c.toString().encodeToByteArray().forEach { bytes += it }
            }
            i += 1
        }
        return bytes.toByteArray().decodeToString()
    }

    // Player.
    val PLAYER_LOADING: String get() = t("episode.playerLoading")
    val NOT_READY: String get() = t("episode.notReady")
    val OUTDATED: String get() = t("episode.outdated")
    val NO_EPISODE: String get() = t("episode.noEpisode")
    val PLAYER_OFFLINE: String get() = t("episode.playerOffline")
    val END: String get() = t("episode.end")
    val REPLAY: String get() = t("episode.replay")
    val NEXT_CHAPTER: String get() = t("episode.nextChapter")
    val SOUND_ON: String get() = t("episode.soundOn")
    val SOUND_OFF: String get() = t("episode.soundOff")
    val SILENT: String get() = t("episode.silent")
    val SILENT_HINT: String get() = t("episode.silentHint")
    val PLAY: String get() = t("episode.play")
    val PAUSE: String get() = t("episode.pause")

    fun watchError(error: AppError): String =
        when {
            error == AppError.Network -> PLAYER_OFFLINE
            error is AppError.Rejected && error.code == "EPISODE_NOT_READY" -> NOT_READY
            error is AppError.Rejected && error.code == "EPISODE_OUTDATED" -> OUTDATED
            error is AppError.Rejected && error.code == "EPISODE_NOT_FOUND" -> NO_EPISODE
            error is AppError.Rejected && error.code == "CHAPTER_NOT_FOUND" -> CHAPTER_NOT_FOUND
            else -> error.userMessage()
        }

    /** "1:05". */
    fun clock(ms: Double): String {
        val total = kotlin.math.round(kotlin.math.max(0.0, ms) / 1000).toLong()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /** "0.75×", "1×", "1.25×". */
    fun speed(value: Double): String {
        val text = if (value == kotlin.math.floor(value)) value.toLong().toString() else value.toString().trimEnd('0')
        return "$text×"
    }
}
