package com.vovoscan.feature.episodes.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.episodes.domain.StageProgress

/** Every user-facing string of the episode card and the player (brand tone: calm, short, ✦ separator). */
object EpisodeText {
    const val NONE = "No episode yet."
    const val NONE_DETAIL = "Prepare it once and everyone who can read this chapter can watch it."
    const val RUNNING = "Preparing the episode"
    const val READY = "Episode ready."
    const val FAILED = "Preparation failed."
    const val LOADING = "Checking the episode…"
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
    const val KEY_WARNING = "Without an Anthropic key the bubbles stay unread; without an ElevenLabs key the episode stays silent."
    const val KEYS_DISABLED = "Keys are turned off on this server: the episode will be unread and silent."
    const val COST_NOTE = "Your own keys pay for this run. Everyone can watch the result."
    const val OFFLINE_WRITE = "You're offline. Connect to the internet to prepare this episode."
    const val NO_PAGES = "This chapter has no pages yet."
    const val CHAPTER_NOT_FOUND = "This chapter isn't available."

    fun anthropicLine(hint: String?): String =
        if (hint != null) "Your Anthropic key (•••• $hint) reads the bubbles." else "No Anthropic key: the bubbles stay unread."

    fun elevenLabsLine(hint: String?): String =
        if (hint != null) "Your ElevenLabs key (•••• $hint) voices the lines." else "No ElevenLabs key: the episode stays silent."

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
            else -> error.userMessage()
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
