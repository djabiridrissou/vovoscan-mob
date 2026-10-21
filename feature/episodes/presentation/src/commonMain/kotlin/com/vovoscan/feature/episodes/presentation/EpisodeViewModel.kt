package com.vovoscan.feature.episodes.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.episodes.domain.Episode
import com.vovoscan.feature.episodes.domain.EpisodeStatus
import com.vovoscan.feature.episodes.domain.EpisodesRepository
import com.vovoscan.feature.episodes.domain.ExportStatus
import com.vovoscan.feature.episodes.domain.KeyStatus
import com.vovoscan.feature.episodes.domain.KeyStatusProvider
import com.vovoscan.feature.episodes.domain.PipelineStage
import com.vovoscan.feature.episodes.domain.PrepareResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EpisodePhase {
    /** Nothing known yet (first look). */
    Loading,

    /** No episode yet: offer Prepare. */
    None,

    /** A run is in progress: stages and progress, followed live. */
    Running,

    Ready,

    Failed,

    /** Nothing known and nothing to show (offline on a first visit, chapter gone): [EpisodeUiState.error] says why. */
    Unavailable,

    /** A book (`422 EPISODE_NOT_SUPPORTED`): hide the whole episode feature for this chapter. */
    Unsupported,
}

enum class StageState { Done, Active, Waiting }

/** A pipeline chip: ANALYZE · OCR · VOICE · TIMELINE. */
data class StageChip(
    val stage: PipelineStage,
    val label: String,
    val state: StageState,
)

/** A video to download now: a freshly signed link and the file's name. [id] changes with every request. */
data class VideoDownload(
    val id: Int,
    val url: String,
    /** "Le Griot de Guerre - Chapter 1.mp4" (the name the server signed). */
    val fileName: String,
)

data class EpisodeUiState(
    val phase: EpisodePhase = EpisodePhase.Loading,
    val headline: String = EpisodeText.LOADING,
    /** Under the headline ("Prepare it once and everyone…", the error of a failed run). */
    val detail: String? = null,
    /** While running: the four stages, the current one active. */
    val stages: List<StageChip> = emptyList(),
    /** "3 / 12 pages", "Queued"… */
    val progressLabel: String? = null,
    /** 0..1 within the current stage; null when unknown. */
    val progressFraction: Double? = null,
    /** "Degraded", "Silent", "Outdated". */
    val badges: List<String> = emptyList(),
    /** One sentence per badge, and the reread hint. */
    val notes: List<String> = emptyList(),
    val canWatch: Boolean = false,
    val watchLabel: String = EpisodeText.WATCH,
    val canPrepare: Boolean = false,
    /** "Prepare episode", "Prepare again" (outdated), "Try again" (failed). */
    val prepareLabel: String = EpisodeText.PREPARE,
    /** The server says this user's keys may run it again ([Episode.canReread]). */
    val canReread: Boolean = false,
    val rereadLabel: String = EpisodeText.REREAD,
    /** Which of the user's keys a run would use (shown with Prepare / Reread). */
    val keyLines: List<String> = emptyList(),
    /** What happens without a key; null when both are there. */
    val keyWarning: String? = null,
    val costNote: String = EpisodeText.COST_NOTE,
    /** A prepare or reread request is in flight. */
    val working: Boolean = false,
    /** Following the run live (events, or polling). */
    val live: Boolean = false,
    val freshness: Freshness = Freshness.Refreshing,
    /** Show the video section (the MP4 export, once the episode is ready). */
    val exportShown: Boolean = false,
    /** "Video ready.", "Making the video ✦ 42%"… */
    val exportLabel: String? = null,
    /** Under the label: what the video is for, or the server's error after a failure. */
    val exportDetail: String? = null,
    /** 0..1 while the video is queued or being made; null otherwise. */
    val exportProgress: Double? = null,
    val canRequestExport: Boolean = false,
    /** "Make a video", "Try again". */
    val exportActionLabel: String = EpisodeText.MAKE_VIDEO,
    val canSaveVideo: Boolean = false,
    /** A video request, or a fresh download link, is on its way. */
    val exportWorking: Boolean = false,
    /** Start this download (Android: DownloadManager or share; iOS: temp file, then the share sheet), then send `VideoHandled`. */
    val videoDownload: VideoDownload? = null,
    /** Short confirmation ("Already in progress. Following it."). */
    val notice: String? = null,
    val error: String? = null,
) {
    /** False for a book: show nothing about episodes. */
    val visible: Boolean get() = phase != EpisodePhase.Unsupported

    /** "Save video", or "Getting the video…" while the link is fetched and the download handed over. */
    val saveVideoLabel: String get() = if (exportWorking || videoDownload != null) EpisodeText.GETTING_VIDEO else EpisodeText.SAVE_VIDEO

    /** The discreet banner while the episode kept on the device is shown offline. */
    val offlineNotice: String? get() =
        if (freshness == Freshness.Offline &&
            phase != EpisodePhase.Unavailable
        ) {
            EpisodeText.OFFLINE_COPY
        } else {
            null
        }

    /** Show the key notice next to an action that would spend the user's keys. */
    val showsKeyNotice: Boolean get() = (canPrepare || canReread) && keyLines.isNotEmpty()
}

sealed interface EpisodeIntent {
    /** The screen is visible (onStart / onAppear): refresh, and follow a run in progress. Done once at creation. */
    data object Start : EpisodeIntent

    /** The screen is hidden (onStop / onDisappear): stop following. */
    data object Stop : EpisodeIntent

    data object Refresh : EpisodeIntent

    /** Prepare, "Prepare again" or "Try again". */
    data object Prepare : EpisodeIntent

    data object Reread : EpisodeIntent

    data object DismissMessage : EpisodeIntent

    /** "Make a video" (or "Try again" after a failed one): `POST …/export`. Shared, CPU only: no key is spent. */
    data object RequestExport : EpisodeIntent

    /** "Save video": fetches a fresh download link, then hands it to the screen as [EpisodeUiState.videoDownload]. */
    data object SaveVideo : EpisodeIntent

    /** The screen took [EpisodeUiState.videoDownload] (download started, share sheet shown, or the user cancelled). */
    data object VideoHandled : EpisodeIntent

    /** The download of [EpisodeUiState.videoDownload] failed. */
    data object VideoFailed : EpisodeIntent
}

/**
 * A chapter's episode card: whether it exists, its live progress, and what the user may do (prepare with their own
 * keys, watch, prepare again when outdated, retry after a failure, rerun when the server says their keys can improve
 * it). Shared episodes: nobody pays twice; a run already in progress is followed, not restarted.
 */
class EpisodeViewModel(
    private val chapterId: String,
    private val repository: EpisodesRepository,
    private val keys: KeyStatusProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EpisodeUiState())
    val state: StateFlow<EpisodeUiState> = mutableState.asStateFlow()
    private var episode: Episode? = null
    private var keyStatus: KeyStatus? = null

    /** The server answered once: no local episode now means "no episode yet". */
    private var known = false
    private var started = false
    private var unsupported = false
    private var followJob: Job? = null
    private var refreshJob: Job? = null
    private var downloads = 0

    init {
        repository
            .observe(chapterId)
            .onEach { stored ->
                episode = stored
                render()
                if (stored?.inProgress == true) follow()
            }.launchIn(viewModelScope)
        keys
            .observe()
            .onEach { status ->
                keyStatus = status
                render()
            }.launchIn(viewModelScope)
        start()
    }

    fun onIntent(intent: EpisodeIntent) {
        when (intent) {
            EpisodeIntent.Start -> start()
            EpisodeIntent.Stop -> stop()
            EpisodeIntent.Refresh -> refresh()
            EpisodeIntent.Prepare -> run(reread = false)
            EpisodeIntent.Reread -> run(reread = true)
            EpisodeIntent.DismissMessage -> mutableState.update { it.copy(notice = null, error = null) }
            EpisodeIntent.RequestExport -> requestExport()
            EpisodeIntent.SaveVideo -> saveVideo()
            EpisodeIntent.VideoHandled -> mutableState.update { it.copy(videoDownload = null) }
            EpisodeIntent.VideoFailed -> mutableState.update { it.copy(videoDownload = null, error = EpisodeText.VIDEO_DOWNLOAD_FAILED) }
        }
        if (intent == EpisodeIntent.VideoHandled || intent == EpisodeIntent.VideoFailed) render()
    }

    private fun requestExport() {
        val current = state.value
        if (current.exportWorking || !current.canRequestExport) return
        mutableState.update { it.copy(exportWorking = true, notice = null, error = null) }
        viewModelScope.launch {
            val result = repository.requestExport(chapterId)
            mutableState.update {
                when (result) {
                    is AppResult.Ok ->
                        it.copy(
                            exportWorking = false,
                            notice = if (result.value.status.active) EpisodeText.VIDEO_QUEUED else null,
                        )
                    is AppResult.Err -> it.copy(exportWorking = false, error = EpisodeText.exportError(result.error))
                }
            }
            // The stored episode carries the export now: follow its progress live.
            if (result is AppResult.Ok && result.value.status.active) follow()
            render()
        }
    }

    private fun saveVideo() {
        val current = state.value
        if (current.exportWorking || !current.canSaveVideo || current.videoDownload != null) return
        mutableState.update { it.copy(exportWorking = true, notice = null, error = null) }
        viewModelScope.launch {
            // The link kept with the episode may have expired: a download always gets a fresh one.
            val result = repository.export(chapterId)
            mutableState.update {
                when (result) {
                    is AppResult.Err -> it.copy(exportWorking = false, error = EpisodeText.exportError(result.error))
                    is AppResult.Ok -> {
                        val url = result.value.url
                        if (result.value.status == ExportStatus.Ready && url != null) {
                            downloads += 1
                            it.copy(exportWorking = false, videoDownload = VideoDownload(downloads, url, EpisodeText.videoFileName(url)))
                        } else {
                            it.copy(exportWorking = false, error = EpisodeText.VIDEO_GONE)
                        }
                    }
                }
            }
            render()
        }
    }

    private fun start() {
        if (started) return
        started = true
        refresh()
        viewModelScope.launch { keys.refresh() }
        if (episode?.inProgress == true) follow()
    }

    private fun stop() {
        started = false
        followJob?.cancel()
        followJob = null
        mutableState.update { it.copy(live = false) }
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        mutableState.update { it.copy(freshness = Freshness.Refreshing) }
        refreshJob =
            viewModelScope.launch {
                when (val result = repository.refresh(chapterId)) {
                    is AppResult.Ok -> {
                        known = true
                        mutableState.update { it.copy(freshness = Freshness.Fresh, error = null) }
                    }
                    is AppResult.Err -> failed(result.error, refreshing = true)
                }
                render()
            }
    }

    private fun run(reread: Boolean) {
        val current = state.value
        if (current.working) return
        if (reread && !current.canReread) return
        if (!reread && !current.canPrepare) return
        mutableState.update { it.copy(working = true, notice = null, error = null) }
        viewModelScope.launch {
            val result = if (reread) repository.reread(chapterId) else repository.prepare(chapterId)
            mutableState.update { it.copy(working = false) }
            when (result) {
                is AppResult.Ok -> {
                    known = true
                    val notice =
                        when (val outcome = result.value) {
                            is PrepareResult.Queued -> EpisodeText.STARTED
                            is PrepareResult.AlreadyReady -> EpisodeText.ALREADY_READY
                            is PrepareResult.Busy -> {
                                // The episode as the server sees it may not be stored yet (no snapshot in the 409).
                                if (outcome.episode == null) refreshNow()
                                EpisodeText.BUSY
                            }
                        }
                    mutableState.update { it.copy(notice = notice) }
                    // Queued, or someone else's run: follow it live. Already ready: nothing runs.
                    if (result.value !is PrepareResult.AlreadyReady) follow()
                }
                is AppResult.Err -> failed(result.error, refreshing = false)
            }
            render()
        }
    }

    private fun refreshNow() {
        refreshJob?.cancel()
        refreshJob = null
        refresh()
    }

    private fun failed(
        error: AppError,
        refreshing: Boolean,
    ) {
        if (error is AppError.Rejected && error.code == "EPISODE_NOT_SUPPORTED") {
            unsupported = true
            stop()
            return
        }
        if (refreshing) {
            mutableState.update {
                it.copy(
                    freshness = Freshness.Offline,
                    // Offline with an episode on screen: the banner is enough.
                    error = if (error == AppError.Network) null else EpisodeText.runError(error),
                )
            }
        } else {
            mutableState.update { it.copy(error = EpisodeText.runError(error)) }
        }
    }

    /** Follows the run while the screen is visible and the episode is not final. */
    private fun follow() {
        if (!started || followJob?.isActive == true) return
        mutableState.update { it.copy(live = true) }
        followJob =
            viewModelScope.launch {
                val result = repository.follow(chapterId)
                if (result is AppResult.Err) mutableState.update { it.copy(error = result.error.userMessage()) }
                mutableState.update { it.copy(live = false) }
            }
    }

    private fun render() {
        val current = episode
        val status = keyStatus
        val keyLines =
            when {
                status == null -> emptyList()
                !status.enabled -> listOf(EpisodeText.KEYS_DISABLED)
                else -> listOf(EpisodeText.anthropicLine(status.anthropicHint), EpisodeText.elevenLabsLine(status.elevenLabsHint))
            }
        val keyWarning =
            status
                ?.takeIf {
                    it.enabled && (it.anthropicHint == null || it.elevenLabsHint == null)
                }?.let { EpisodeText.KEY_WARNING }
        mutableState.update { state ->
            val base = exportState(state.copy(keyLines = keyLines, keyWarning = keyWarning), current)
            when {
                unsupported -> EpisodeUiState(phase = EpisodePhase.Unsupported)
                current != null -> episodeState(base, current)
                known -> noneState(base)
                state.freshness == Freshness.Offline ->
                    base.copy(
                        phase = EpisodePhase.Unavailable,
                        headline = state.error ?: EpisodeText.UNAVAILABLE_OFFLINE,
                        canPrepare = false,
                        canWatch = false,
                        canReread = false,
                    )
                else -> base.copy(phase = EpisodePhase.Loading, headline = EpisodeText.LOADING)
            }
        }
    }

    /** The video section: only for a ready episode, from the export carried by the stored episode. */
    private fun exportState(
        base: EpisodeUiState,
        e: Episode?,
    ): EpisodeUiState {
        val hidden =
            base.copy(
                exportShown = false,
                exportLabel = null,
                exportDetail = null,
                exportProgress = null,
                canRequestExport = false,
                canSaveVideo = false,
            )
        if (e == null || e.status != EpisodeStatus.Ready || unsupported) return hidden
        val export = e.export
        val shown = hidden.copy(exportShown = true)
        return when (export?.status) {
            null, ExportStatus.None ->
                shown.copy(
                    exportLabel = EpisodeText.VIDEO_NONE,
                    exportDetail = EpisodeText.VIDEO_NONE_DETAIL,
                    canRequestExport = true,
                    exportActionLabel = EpisodeText.MAKE_VIDEO,
                )
            ExportStatus.Queued -> shown.copy(exportLabel = EpisodeText.VIDEO_QUEUED_LABEL, exportProgress = 0.0)
            ExportStatus.Rendering ->
                shown.copy(exportLabel = EpisodeText.videoProgress(export.progress), exportProgress = export.progress / 100.0)
            ExportStatus.Ready ->
                shown.copy(
                    exportLabel = EpisodeText.VIDEO_READY,
                    exportDetail = if (export.outdated) EpisodeText.VIDEO_OUTDATED else EpisodeText.VIDEO_READY_DETAIL,
                    canSaveVideo = export.url != null,
                )
            ExportStatus.Failed ->
                shown.copy(
                    exportLabel = EpisodeText.VIDEO_FAILED,
                    exportDetail = export.error,
                    canRequestExport = true,
                    exportActionLabel = EpisodeText.TRY_AGAIN,
                )
        }
    }

    private fun noneState(base: EpisodeUiState) =
        base.copy(
            phase = EpisodePhase.None,
            headline = EpisodeText.NONE,
            detail = EpisodeText.NONE_DETAIL,
            stages = emptyList(),
            progressLabel = null,
            progressFraction = null,
            badges = emptyList(),
            notes = emptyList(),
            canWatch = false,
            canPrepare = true,
            prepareLabel = EpisodeText.PREPARE,
            canReread = false,
        )

    private fun episodeState(
        base: EpisodeUiState,
        e: Episode,
    ): EpisodeUiState {
        val badges = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (e.status == EpisodeStatus.Ready) {
            if (e.degraded == true) {
                badges += EpisodeText.BADGE_DEGRADED
                notes += EpisodeText.NOTE_DEGRADED
            }
            if (e.silent == true) {
                badges += EpisodeText.BADGE_SILENT
                notes += EpisodeText.NOTE_SILENT
            }
            if (e.outdated) {
                badges += EpisodeText.BADGE_OUTDATED
                notes += EpisodeText.NOTE_OUTDATED
            }
            if (e.canReread && (e.degraded == true || e.silent == true)) notes += EpisodeText.NOTE_REREAD
        }
        return when {
            e.status.active -> {
                val stage = e.stage
                val progress = e.progress
                base.copy(
                    phase = EpisodePhase.Running,
                    headline = EpisodeText.RUNNING + (stage?.let { " ✦ ${it.label}" } ?: ""),
                    detail = null,
                    stages =
                        PipelineStage.entries.map { s ->
                            val state =
                                when {
                                    stage == null -> StageState.Waiting
                                    s.ordinal < stage.ordinal -> StageState.Done
                                    s == stage -> StageState.Active
                                    else -> StageState.Waiting
                                }
                            StageChip(s, s.label, state)
                        },
                    progressLabel = EpisodeText.progress(progress),
                    progressFraction =
                        progress?.let { p ->
                            val done = p.done
                            val total = p.total
                            if (done != null && total != null && total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else null
                        },
                    badges = emptyList(),
                    notes = emptyList(),
                    canWatch = false,
                    canPrepare = false,
                    canReread = false,
                )
            }
            e.status == EpisodeStatus.Ready ->
                base.copy(
                    phase = EpisodePhase.Ready,
                    headline = EpisodeText.READY,
                    detail = null,
                    stages = emptyList(),
                    progressLabel = null,
                    progressFraction = null,
                    badges = badges,
                    notes = notes,
                    canWatch = true,
                    canPrepare = e.outdated,
                    prepareLabel = EpisodeText.PREPARE_AGAIN,
                    canReread = e.canReread && !e.outdated,
                )
            else ->
                base.copy(
                    phase = EpisodePhase.Failed,
                    headline = EpisodeText.FAILED,
                    detail = e.error,
                    stages = emptyList(),
                    progressLabel = null,
                    progressFraction = null,
                    badges = emptyList(),
                    notes = emptyList(),
                    // A failed run can still have an older render to play.
                    canWatch = e.render != null && !e.outdated,
                    canPrepare = true,
                    prepareLabel = EpisodeText.TRY_AGAIN,
                    canReread = false,
                )
        }
    }
}
