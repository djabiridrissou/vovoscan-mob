package com.vovoscan.feature.episodes.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.Language
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.episodes.domain.EpisodeAudio
import com.vovoscan.feature.episodes.domain.ProviderFunding
import com.vovoscan.feature.episodes.domain.RunCost
import com.vovoscan.feature.episodes.domain.StageProgress

/** Every user-facing string of the episode card and the player (brand tone: calm, short, ✦ separator). */
object EpisodeText {
    const val NONE = "No episode yet."
    const val NONE_DETAIL = "Prepare it once and everyone who can read this chapter can watch it."
    const val RUNNING = "Preparing the episode"
    const val READY = "Episode ready."
    const val FAILED = "Preparation failed."
    const val LOADING = "Checking the episode…"
    const val OFFLINE_COPY = "Offline: showing what's saved on this device."
    const val UNAVAILABLE_OFFLINE = "You're offline. The episode shows once you're back online."
    const val PREPARE = "Prepare episode"
    const val PREPARE_AGAIN = "Prepare again"
    const val TRY_AGAIN = "Try again"
    const val REREAD = "Run again with my keys"
    const val WATCH = "Watch →"
    const val QUEUED = "Queued"
    const val BUSY = "Already in progress. Following it."
    const val ALREADY_READY = "Ready. It was already made, so nothing was charged."
    const val STARTED = "Started. Your keys pay for this run; everyone can watch the result."
    const val BADGE_DEGRADED = "Degraded"
    const val BADGE_SILENT = "Silent"
    const val BADGE_OUTDATED = "Outdated"
    const val NOTE_DEGRADED = "Some bubbles weren't read or voiced."
    const val NOTE_SILENT = "No voices: it was prepared without an ElevenLabs key."
    const val NOTE_OUTDATED = "The chapter or the pipeline changed since this episode was made. Prepare it again to catch up."
    const val NOTE_REREAD = "Your keys can improve it."

    // Languages (`?lang=`): the original, or the episode dubbed / subtitled in another language.
    const val ORIGINAL = "Original"
    const val ORIGINAL_DETAIL = "The chapter's own language"
    const val DUBBED = "Dubbed"
    const val SUBTITLED = "Subtitled"
    const val BETA = "beta"

    fun languageDetail(language: Language): String =
        listOfNotNull(language.name, if (language.dub) DUBBED else SUBTITLED, BETA.takeIf { language.beta }).joinToString(" ✦ ")

    /** Under a ready episode in another language: how it sounds. */
    fun audioNote(
        language: Language,
        audio: EpisodeAudio?,
    ): String =
        if (audio == EpisodeAudio.Dub) {
            "Voiced in ${language.name}."
        } else {
            "The original voices, with ${language.name} captions."
        }

    // The key lines already say what each missing key costs; this only says where to fix it.
    const val KEY_WARNING = "Add the missing key in Settings."
    const val KEYS_DISABLED = "Keys are turned off on this server: the episode will be unread and silent."
    const val COST_NOTE = "Your own keys pay for this run. Everyone can watch the result."
    const val PLATFORM_NOTE =
        "What your keys don't cover runs on the platform's keys, paid from your wallet. Everyone can watch the result."
    const val UNFUNDED_NOTE = "Only your own keys pay for runs here. Everyone can watch the result."
    const val UNFUNDED_WARNING = "Without keys the episode stays unread and silent. Add yours in Settings, or ask the owner."
    const val OFFLINE_WRITE = "You're offline. Connect to the internet to prepare this episode."
    const val NO_PAGES = "This chapter has no pages yet."
    const val CHAPTER_NOT_FOUND = "This chapter isn't available."
    const val COMING_SOON = "Episodes are coming soon."
    const val COMING_SOON_DETAIL = "Voiced episodes open to more readers soon. The chapter reads as usual meanwhile."
    const val NO_CREDITS = "Not enough credits for this run. Add your own keys in Settings, or ask for a top-up."
    const val CREDITS_PAUSED = "Runs on the platform's keys are paused right now. Your own keys still work."
    const val DAILY_CAP = "Today's limit is reached. Try again tomorrow, or use your own keys."

    /** Codes saying the feature isn't open to this account (the server's feature flags). */
    val FEATURE_OFF_CODES = setOf("FEATURE_DISABLED", "FEATURE_COMING_SOON")

    /** Who reads the bubbles: the account's key, the platform's (credits), or nobody. */
    fun anthropicLine(
        hint: String?,
        funding: ProviderFunding = ProviderFunding.None,
    ): String =
        when {
            hint != null -> "Your Anthropic key (•••• $hint) reads the bubbles."
            funding == ProviderFunding.Platform -> "The platform's key reads the bubbles, paid in credits."
            else -> "No Anthropic key: the bubbles stay unread."
        }

    fun elevenLabsLine(
        hint: String?,
        funding: ProviderFunding = ProviderFunding.None,
    ): String =
        when {
            hint != null -> "Your ElevenLabs key (•••• $hint) voices the lines."
            funding == ProviderFunding.Platform -> "The platform's key voices the lines, paid in credits."
            else -> "No ElevenLabs key: the episode stays silent."
        }

    /** "3 / 12 pages", "4 / 30 lines", "Queued", "Step 2 / 5". */
    fun progress(progress: StageProgress?): String? {
        if (progress == null) return null
        if (progress.step == "queued") return QUEUED
        val done = progress.done
        val total = progress.total
        if (done == null || total == null || total <= 0) return null
        return when (progress.step) {
            "pages" -> "$done / $total pages"
            "lines" -> "$done / $total lines"
            else -> "Step $done / $total"
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
            cost.alreadyAvailable -> "Already made: it costs nothing."
            cost.coveredByOwnKeys -> "Your own keys pay: no credit spent."
            !cost.meteredRun -> null
            !cost.fundable -> "Not enough credits: this run needs ${cost.maxCredits} available."
            cost.credits == 0L -> "Costs less than 1 credit from your wallet."
            cost.credits == 1L -> "Costs 1 credit from your wallet."
            else -> "Costs ${cost.credits} credits from your wallet."
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
    const val MAKE_VIDEO = "Make a video"
    const val SAVE_VIDEO = "Save video"
    const val GETTING_VIDEO = "Getting the video…"
    const val VIDEO_NONE = "No video yet."
    const val VIDEO_NONE_DETAIL = "Make an MP4 of this episode to keep or share. It costs no key."
    const val VIDEO_QUEUED_LABEL = "Video queued"
    const val VIDEO_QUEUED = "Video queued. It takes a few minutes; you can leave this screen."
    const val VIDEO_READY = "Video ready."
    const val VIDEO_READY_DETAIL = "An MP4 of the episode, 1080 × 1920."
    const val VIDEO_OUTDATED = "Made from the episode as it was prepared. Prepare it again for the latest version."
    const val VIDEO_FAILED = "The video couldn't be made."
    const val VIDEO_GONE = "This video isn't available anymore. Make it again."
    const val VIDEO_DOWNLOAD_FAILED = "The video couldn't be downloaded. Try again."
    const val VIDEO_OFFLINE = "You're offline. Connect to the internet to get the video."
    const val VIDEO_RATE_LIMITED = "You can ask for 10 videos an hour. Try again later."
    const val VIDEO_FILE = "vovoscan episode.mp4"

    /** "Making the video ✦ 42%". */
    fun videoProgress(percent: Int): String = "Making the video ✦ ${percent.coerceIn(0, 100)}%"

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
    const val PLAYER_LOADING = "Loading the episode…"
    const val NOT_READY = "This episode isn't ready yet."
    const val OUTDATED = "The chapter changed since this episode was prepared. Prepare it again."
    const val NO_EPISODE = "This chapter has no episode yet."
    const val PLAYER_OFFLINE = "You're offline. An episode plays offline once it has been opened online."
    const val END = "End of chapter"
    const val REPLAY = "Replay"
    const val NEXT_CHAPTER = "Next chapter →"
    const val SOUND_ON = "Sound on"
    const val SOUND_OFF = "Sound off"
    const val SILENT = "Silent"
    const val SILENT_HINT = "Add an ElevenLabs key in Settings to hear the voices."
    const val PLAY = "Play"
    const val PAUSE = "Pause"

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
