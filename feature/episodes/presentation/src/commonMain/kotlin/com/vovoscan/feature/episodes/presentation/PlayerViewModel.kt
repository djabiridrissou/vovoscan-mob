package com.vovoscan.feature.episodes.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.Language
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.episodes.domain.Episode
import com.vovoscan.feature.episodes.domain.EpisodeStatus
import com.vovoscan.feature.episodes.domain.EpisodesRepository
import com.vovoscan.feature.episodes.domain.PipelineStage
import com.vovoscan.feature.episodes.domain.PrepareResult
import com.vovoscan.feature.episodes.domain.RunCost
import com.vovoscan.feature.episodes.domain.RunQuotes
import com.vovoscan.feature.episodes.domain.Watch
import com.vovoscan.feature.episodes.domain.player.CaptionPlacement
import com.vovoscan.feature.episodes.domain.player.PlaybackPlan
import com.vovoscan.feature.episodes.domain.player.PlayerFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

data class SpeedOption(
    val value: Double,
    /** "1.25×". */
    val label: String,
    val selected: Boolean,
)

/**
 * The panel docked over a playing episode while another language is not ready (reader SPEC §11): its cost first, then
 * its progress while the episode keeps playing; once ready the player switches to it at the same page. Never a dead
 * end: Make it, Add your own key, Try again, or back to the language playing.
 */
data class PlayerLanguageDock(
    val code: String,
    val title: String,
    val line: String?,
    /** 0..1 for the bar while it is made; null: no bar. */
    val progress: Float?,
    /** A spinner: the cost is being checked, the run waits in the queue, or the switch loads. */
    val busy: Boolean,
    /** "Make it", when the run can be paid. */
    val startLabel: String?,
    /** "Add your own key" when the account's key would pay (always when nothing else can). */
    val addKeyLabel: String?,
    val retryLabel: String?,
    /** "Not now" / "Cancel" / "Close" ([PlayerIntent.CloseLanguageDock]). */
    val closeLabel: String,
    val failed: Boolean,
)

data class PlayerUiState(
    val loading: Boolean = true,
    /** Nothing to play ("This episode isn't ready yet."…); [loading] false. */
    val error: String? = null,
    /** A plan is loaded: call `frame(…)` every display frame. */
    val ready: Boolean = false,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val muted: Boolean = false,
    val speed: Double = 1.0,
    /** Updated about every second while playing (and on every seek): the scrubber and the clock. */
    val positionMs: Double = 0.0,
    val durationMs: Double = 0.0,
    val beat: Int = 0,
    val beatCount: Int = 0,
    val pageNumber: Int = 0,
    val pageCount: Int = 0,
    /** No sound at all in this episode: show [soundLabel] "Silent" and [silentHint] instead of a mute toggle. */
    val silent: Boolean = false,
    /** The watch comes from the device and the network failed: media may not load. */
    val offline: Boolean = false,
    val nextChapterId: String? = null,
    /** Page images to warm in the image cache. */
    val pageUrls: List<String> = emptyList(),
    /** The language playing ("" = the original). */
    val language: String = "",
    /** The language button: "Original", "Français". */
    val languageLabel: String = EpisodeText.ORIGINAL,
    /** "Dubbed" / "Subtitled" for another language; null for the original. */
    val languageMode: String? = null,
    /** The picker's rows, filtered by [languageQuery]. */
    val languageOptions: List<EpisodeLanguageOption> = emptyList(),
    val languageQuery: String = "",
    val languageDock: PlayerLanguageDock? = null,
) {
    val speeds: List<SpeedOption> get() = PlayerViewModel.SPEEDS.map { SpeedOption(it, EpisodeText.speed(it), it == speed) }

    /** "0:12 / 3:40". */
    val timeLabel: String get() = "${EpisodeText.clock(positionMs)} / ${EpisodeText.clock(durationMs)}"

    /** "Page 3 / 12". */
    val pageLabel: String get() = if (pageCount > 0) t("episode.pageOf", "n" to pageNumber, "total" to pageCount) else ""

    val soundLabel: String get() =
        if (silent) {
            EpisodeText.SILENT
        } else if (muted) {
            EpisodeText.SOUND_OFF
        } else {
            EpisodeText.SOUND_ON
        }

    val silentHint: String? get() = if (silent) EpisodeText.SILENT_HINT else null

    /** Accessibility label of the play button. */
    val playLabel: String get() = if (playing) EpisodeText.PAUSE else EpisodeText.PLAY

    val endTitle: String get() = EpisodeText.END

    val replayLabel: String get() = EpisodeText.REPLAY

    /** "Next chapter →" at the end, when the screen that opened the player gave a next chapter. */
    val nextLabel: String? get() = if (ended && nextChapterId != null) EpisodeText.NEXT_CHAPTER else null

    val canGoBack: Boolean get() = beat > 0

    val canGoForward: Boolean get() = beat < beatCount - 1
}

sealed interface PlayerIntent {
    data object TogglePlay : PlayerIntent

    data object Play : PlayerIntent

    data object Pause : PlayerIntent

    /** The scrubber: a position in ms. */
    data class SeekTo(
        val positionMs: Double,
    ) : PlayerIntent

    data class SeekToBeat(
        val beat: Int,
    ) : PlayerIntent

    data object NextBeat : PlayerIntent

    data object PreviousBeat : PlayerIntent

    data object NextPage : PlayerIntent

    data object PreviousPage : PlayerIntent

    /** One of [PlayerViewModel.SPEEDS]. */
    data class SetSpeed(
        val speed: Double,
    ) : PlayerIntent

    data object ToggleMute : PlayerIntent

    /** At the end: from the top. */
    data object Replay : PlayerIntent

    /** A page image or a sound failed to load (its signed URL may have expired): fetch fresh URLs. */
    data class MediaFailed(
        val url: String,
    ) : PlayerIntent

    data object Retry : PlayerIntent

    /** Another language (null: the original): at once when it exists, else quoted in the dock first. */
    data class ChooseLanguage(
        val code: String?,
    ) : PlayerIntent

    data class LanguageQuery(
        val text: String,
    ) : PlayerIntent

    /** The dock's "Make it". */
    data object StartLanguage : PlayerIntent

    /** The dock's "Try again". */
    data object RetryLanguage : PlayerIntent

    /** The dock's "Not now" / "Cancel" / "Close": the episode goes on in the language playing. */
    data object CloseLanguageDock : PlayerIntent
}

/**
 * The episode player, shared by Compose and SwiftUI. It loads the watch (from the device while its URLs last), builds
 * the [PlaybackPlan] and keeps the clock: the native side calls [frame] on every display frame with its frame time and
 * draws / plays exactly what the returned [PlayerFrame] says. Play, pause, seek, speed (0.75–1.5×), mute, beat and page
 * navigation are intents. Signed URLs are refetched before they expire, or when a media request fails.
 */
class PlayerViewModel(
    private val chapterId: String,
    private val nextChapterId: String?,
    private val repository: EpisodesRepository,
    private val clock: Clock = SystemClock,
    /** The episode's language, from the card that opened the player ("" = the original). */
    language: String = "",
    /** Opened from the reader ("Watch the episode from here"): start on the first beat of this chapter page. */
    private val startPage: Int? = null,
    /** What another language would cost this account (wired in `shared` to the wallet). */
    private val quotes: RunQuotes = RunQuotes.None,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PlayerUiState(nextChapterId = nextChapterId))

    /** The language playing; changes when another one is ready ([switchTo]). */
    private var lang = language.takeIf { Language.of(it) != null }.orEmpty()
    private var languageJob: Job? = null
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    /** The loaded plan; null until the watch is there. */
    var plan: PlaybackPlan? = null
        private set

    /** Precise episode time (ms); [PlayerUiState.positionMs] follows it about once a second. */
    private var position = 0.0
    private var lastTick = Double.NaN
    private var expiryJob: Job? = null
    private var loadJob: Job? = null
    private var lastMediaRefresh: Long? = null

    init {
        renderLanguages()
        load(force = false)
    }

    fun onIntent(intent: PlayerIntent) {
        when (intent) {
            PlayerIntent.TogglePlay -> if (state.value.playing) pause() else play()
            PlayerIntent.Play -> play()
            PlayerIntent.Pause -> pause()
            is PlayerIntent.SeekTo -> seek(intent.positionMs)
            is PlayerIntent.SeekToBeat -> plan?.let { seek(it.beatStartMs(intent.beat)) }
            PlayerIntent.NextBeat -> plan?.let { p -> if (state.value.beat + 1 < p.beatCount) seek(p.beatStartMs(state.value.beat + 1)) }
            PlayerIntent.PreviousBeat -> plan?.let { p -> seek(p.beatStartMs(max(0, state.value.beat - 1))) }
            PlayerIntent.NextPage -> plan?.let { p -> p.nextPageBeat(state.value.beat)?.let { seek(p.beatStartMs(it)) } }
            PlayerIntent.PreviousPage -> plan?.let { p -> seek(p.beatStartMs(p.previousPageBeat(state.value.beat))) }
            is PlayerIntent.SetSpeed -> if (intent.speed in SPEEDS) mutableState.update { it.copy(speed = intent.speed) }
            PlayerIntent.ToggleMute -> mutableState.update { it.copy(muted = !it.muted) }
            PlayerIntent.Replay -> {
                seek(0.0)
                play()
            }
            is PlayerIntent.MediaFailed -> {
                val now = clock.nowMillis()
                if (lastMediaRefresh?.let { now - it < MEDIA_RETRY_MS } == true) return
                lastMediaRefresh = now
                load(force = true)
            }
            PlayerIntent.Retry -> load(force = true)
            is PlayerIntent.ChooseLanguage -> chooseLanguage(intent.code?.takeIf { Language.of(it) != null }.orEmpty())
            is PlayerIntent.LanguageQuery -> {
                mutableState.update { it.copy(languageQuery = intent.text) }
                renderLanguages()
            }
            PlayerIntent.StartLanguage ->
                state.value.languageDock
                    ?.takeIf { it.startLabel != null }
                    ?.let { start(it.code) }
            PlayerIntent.RetryLanguage -> state.value.languageDock?.let { chooseLanguage(it.code, retry = true) }
            PlayerIntent.CloseLanguageDock -> {
                languageJob?.cancel()
                mutableState.update { it.copy(languageDock = null) }
            }
        }
    }

    /**
     * Reader SPEC §11 in the player. A language already made switches at once, at the same page; one being made is
     * followed; one nobody made yet is quoted in the dock first (nothing is paid before "Make it"). The episode plays on
     * meanwhile.
     */
    private fun chooseLanguage(
        code: String,
        retry: Boolean = false,
    ) {
        if (!retry &&
            (
                code == lang ||
                    state.value.languageDock
                        ?.takeIf { !it.failed }
                        ?.code == code
            )
        ) {
            return
        }
        languageJob?.cancel()
        dock(code, title = EpisodeText.switching(code), busy = true)
        languageJob =
            viewModelScope.launch {
                when (val known = repository.refresh(chapterId, code)) {
                    is AppResult.Err -> failed(code, EpisodeText.runError(known.error))
                    is AppResult.Ok -> {
                        val episode = known.value
                        when {
                            episode != null && episode.status == EpisodeStatus.Ready && !episode.outdated -> switchTo(code)
                            episode != null && episode.status.active -> follow(code)
                            else -> offer(code)
                        }
                    }
                }
            }
    }

    private suspend fun offer(code: String) {
        dock(code, title = EpisodeText.CHECKING, busy = true)
        val cost = quotes.episode(chapterId, code)
        if (cost?.alreadyAvailable == true) return start(code)
        val payable = cost == null || cost.coveredByOwnKeys || cost.fundable
        dock(
            code,
            title = t("player.lang.offer", "language" to native(code)),
            line = if (payable) offerLine(cost) else t("player.lang.unfunded"),
            startLabel = t("player.lang.start").takeIf { payable },
            addKeyLabel = t("player.lang.addKey").takeIf { cost != null && !cost.coveredByOwnKeys },
            closeLabel = EpisodeText.NOT_NOW,
        )
    }

    private fun offerLine(cost: RunCost?): String? =
        when {
            cost == null -> null
            cost.coveredByOwnKeys -> EpisodeText.cost(cost)
            !cost.meteredRun -> t("player.lang.free")
            else -> EpisodeText.cost(cost)
        }

    private fun start(code: String) {
        languageJob?.cancel()
        dock(code, title = making(code), line = EpisodeText.KEEP_WATCHING, busy = true)
        languageJob =
            viewModelScope.launch {
                when (val asked = repository.prepare(chapterId, code)) {
                    is AppResult.Err ->
                        failed(code, EpisodeText.runError(asked.error), addKey = EpisodeText.creditError(asked.error) != null)
                    is AppResult.Ok ->
                        when (asked.value) {
                            is PrepareResult.AlreadyReady -> switchTo(code)
                            is PrepareResult.Queued, is PrepareResult.Busy -> follow(code)
                        }
                }
            }
    }

    /** Follows the run (its progress in the dock) while the episode plays, then switches. */
    private suspend fun follow(code: String) {
        dock(code, title = making(code), line = EpisodeText.KEEP_WATCHING, busy = true)
        val result =
            coroutineScope {
                // A child of the follow: closing the dock stops both.
                val progress =
                    launch {
                        repository.observe(chapterId, code).collect { episode ->
                            if (episode != null && episode.status.active) {
                                val share = overall(episode)
                                dock(
                                    code,
                                    title = making(code),
                                    line =
                                        listOfNotNull(
                                            share?.let { t("player.lang.percent", "percent" to (it * 100).toInt()) },
                                            EpisodeText.KEEP_WATCHING,
                                        ).joinToString(" ✦ "),
                                    progress = share,
                                    busy = share == null,
                                )
                            }
                        }
                    }
                repository.follow(chapterId, code).also { progress.cancel() }
            }
        when {
            result is AppResult.Err -> failed(code, EpisodeText.runError(result.error))
            (result as AppResult.Ok).value?.status == EpisodeStatus.Ready -> switchTo(code)
            else -> failed(code, result.value?.error ?: t("player.lang.failed"))
        }
    }

    /** The new language's watch, at the page playing now; playback goes on as it was (playing or paused). */
    private suspend fun switchTo(code: String) {
        dock(code, title = EpisodeText.switching(code), busy = true)
        when (val watch = repository.watch(chapterId, lang = code)) {
            is AppResult.Err -> failed(code, EpisodeText.watchError(watch.error))
            is AppResult.Ok -> {
                val page =
                    plan?.let { p ->
                        p.watch.timeline.bubbles
                            .getOrNull(p.beatAt(position))
                            ?.page
                    }
                lang = code
                plan = null
                show(watch.value, atPage = page)
                mutableState.update { it.copy(languageDock = null) }
                renderLanguages()
            }
        }
    }

    private fun failed(
        code: String,
        message: String,
        addKey: Boolean = false,
    ) = dock(
        code,
        title = native(code),
        line = message,
        addKeyLabel = t("player.lang.addKey").takeIf { addKey },
        retryLabel = EpisodeText.TRY_AGAIN,
        closeLabel = EpisodeText.CLOSE,
        failed = true,
    )

    private fun dock(
        code: String,
        title: String,
        line: String? = null,
        progress: Float? = null,
        busy: Boolean = false,
        startLabel: String? = null,
        addKeyLabel: String? = null,
        retryLabel: String? = null,
        closeLabel: String = if (busy || progress != null) EpisodeText.CANCEL else EpisodeText.NOT_NOW,
        failed: Boolean = false,
    ) = mutableState.update {
        it.copy(
            languageDock = PlayerLanguageDock(code, title, line, progress, busy, startLabel, addKeyLabel, retryLabel, closeLabel, failed),
        )
    }

    private fun making(code: String) = t("player.lang.making", "language" to native(code))

    private fun native(code: String) = Language.of(code)?.native ?: EpisodeText.ORIGINAL

    private fun renderLanguages() {
        val chosen = Language.of(lang)
        val needle =
            state.value.languageQuery
                .trim()
                .lowercase()
        val original = EpisodeLanguageOption(null, EpisodeText.ORIGINAL, EpisodeText.ORIGINAL_DETAIL, chosen == null)
        val languages =
            Language.ALL
                .sortedBy { it.name }
                .filter {
                    needle.isEmpty() ||
                        it.native.lowercase().contains(needle) ||
                        it.name.lowercase().contains(needle) ||
                        it.code == needle
                }.map { EpisodeLanguageOption(it.code, it.native, EpisodeText.languageDetail(it), it.code == lang) }
        val showOriginal = needle.isEmpty() || EpisodeText.ORIGINAL.lowercase().contains(needle)
        mutableState.update {
            it.copy(
                language = lang,
                languageLabel = chosen?.native ?: EpisodeText.ORIGINAL,
                languageMode = chosen?.let { language -> if (language.dub) EpisodeText.DUBBED else EpisodeText.SUBTITLED },
                languageOptions = (if (showOriginal) listOf(original) else emptyList()) + languages,
            )
        }
    }

    /**
     * Call on every display frame with a monotonic frame time in ms (Compose `withFrameNanos` / 1e6, `CADisplayLink`
     * timestamp × 1000) and the stage size in points: advances the clock while playing (at the chosen speed) and
     * returns what to draw and play now, or null before the episode is loaded. [reducedMotion]: the whole panel, held.
     */
    fun frame(
        frameTimeMs: Double,
        stageWidth: Double,
        stageHeight: Double,
        reducedMotion: Boolean = false,
    ): PlayerFrame? {
        val p = plan ?: return null
        val current = state.value
        if (current.playing && !lastTick.isNaN()) {
            // A stalled frame (app in the background) never jumps ahead more than a quarter of a second.
            position += min(MAX_STEP_MS, max(0.0, frameTimeMs - lastTick)) * current.speed
        }
        lastTick = frameTimeMs
        val ending = current.playing && position >= p.durationMs
        if (ending) {
            position = p.durationMs
            mutableState.update { it.copy(playing = false, ended = true) }
        }
        // At the end the clock shows the full length, not the last whole second published.
        publish(p, force = ending)
        val frame = p.frame(position, stageWidth, stageHeight, playing = state.value.playing, reducedMotion = reducedMotion) ?: return null
        return if (state.value.muted) frame.copy(audio = frame.audio.map { it.copy(gain = 0.0) }) else frame
    }

    /** Where the caption block goes for [beat] (see [PlaybackPlan.captionPlacement]); call once per beat and stage size. */
    fun captionPlacement(
        beat: Int,
        stageWidth: Double,
        stageHeight: Double,
        blockHeight: Double,
        blockWidth: Double,
    ): CaptionPlacement =
        plan?.captionPlacement(beat, stageWidth, stageHeight, blockHeight, blockWidth) ?: CaptionPlacement(false, 0.0, false)

    private fun play() {
        val p = plan ?: return
        if (state.value.ended || position >= p.durationMs) position = 0.0
        lastTick = Double.NaN
        mutableState.update { it.copy(playing = true, ended = false) }
        publish(p, force = true)
    }

    private fun pause() {
        mutableState.update { it.copy(playing = false) }
        plan?.let { publish(it, force = true) }
    }

    private fun seek(ms: Double) {
        val p = plan ?: return
        position = ms.coerceIn(0.0, p.durationMs)
        lastTick = Double.NaN
        mutableState.update { it.copy(ended = false) }
        publish(p, force = true)
    }

    /** Moves the coarse state (beat, page, clock) when it changes: not on every frame. */
    private fun publish(
        p: PlaybackPlan,
        force: Boolean = false,
    ) {
        val beat = p.beatAt(position)
        val current = state.value
        val second = (position / 1000).toLong()
        if (!force && beat == current.beat && second == (current.positionMs / 1000).toLong() && current.durationMs == p.durationMs) return
        mutableState.update { it.copy(positionMs = position, beat = beat, pageNumber = p.pageNumberOf(beat)) }
    }

    private fun load(force: Boolean) {
        if (loadJob?.isActive == true) return
        if (plan == null) mutableState.update { it.copy(loading = true, error = null) }
        loadJob =
            viewModelScope.launch {
                when (val result = repository.watch(chapterId, forceRefresh = force, lang = lang)) {
                    is AppResult.Ok -> show(result.value)
                    is AppResult.Err ->
                        if (plan == null) {
                            mutableState.update { it.copy(loading = false, error = EpisodeText.watchError(result.error)) }
                        } else {
                            mutableState.update { it.copy(offline = true) }
                        }
                }
            }
    }

    private fun show(
        watch: Watch,
        atPage: Int? = startPage,
    ) {
        val before = plan
        val next = PlaybackPlan(watch)
        plan = next
        // Same render with fresh URLs: playback goes on where it was; another render (rerun): from its start.
        if (before != null && before.watch.renderKey != watch.renderKey) position = 0.0
        // First load from "Watch from here", or another language: the first beat of that chapter page.
        if (before == null && atPage != null) next.firstBeatFrom(atPage)?.let { position = next.beatStartMs(it) }
        lastTick = Double.NaN
        position = position.coerceIn(0.0, next.durationMs)
        mutableState.update {
            it.copy(
                loading = false,
                error = null,
                ready = next.beatCount > 0,
                durationMs = next.durationMs,
                beatCount = next.beatCount,
                pageCount = next.pageCount,
                silent = next.silent,
                offline = watch.expiresAtMillis <= clock.nowMillis(),
                pageUrls = watch.pageUrls,
            )
        }
        publish(next, force = true)
        scheduleRefresh(watch)
    }

    /** Fresh URLs a few minutes before these expire. */
    private fun scheduleRefresh(watch: Watch) {
        expiryJob?.cancel()
        val wait = watch.expiresAtMillis - REFRESH_BEFORE_EXPIRY_MS - clock.nowMillis()
        // Already due (refetched on the next media failure) or far away (the player is not open for days).
        if (wait <= 0 || wait > MAX_SCHEDULE_MS) return
        expiryJob =
            viewModelScope.launch {
                delay(wait)
                load(force = true)
            }
    }

    companion object {
        /** A run's share done (0..1) from its stage and the stage's own count; null while queued or unknown. */
        fun overall(episode: Episode): Float? {
            val stages = PipelineStage.entries.filter { it != PipelineStage.Translate || episode.language.isNotEmpty() }
            val stage = episode.stage ?: return null
            if (episode.status == EpisodeStatus.Queued) return null
            val index = stages.indexOf(stage).takeIf { it >= 0 } ?: return null
            val p = episode.progress
            val within =
                if (p?.done != null && p.total != null && p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f
            return ((index + within) / stages.size).coerceIn(0f, 1f)
        }

        val SPEEDS = listOf(0.75, 1.0, 1.25, 1.5)
        private const val MAX_STEP_MS = 250.0
        private const val MEDIA_RETRY_MS = 30_000L
        private const val REFRESH_BEFORE_EXPIRY_MS = 5 * 60_000L
        private const val MAX_SCHEDULE_MS = 2 * 86_400_000L
    }
}
