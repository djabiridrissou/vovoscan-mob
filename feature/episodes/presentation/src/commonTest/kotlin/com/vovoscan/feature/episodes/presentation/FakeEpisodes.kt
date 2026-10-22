package com.vovoscan.feature.episodes.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.episodes.domain.Box
import com.vovoscan.feature.episodes.domain.Episode
import com.vovoscan.feature.episodes.domain.EpisodeExport
import com.vovoscan.feature.episodes.domain.EpisodeStatus
import com.vovoscan.feature.episodes.domain.EpisodesRepository
import com.vovoscan.feature.episodes.domain.ExportStatus
import com.vovoscan.feature.episodes.domain.KeyStatus
import com.vovoscan.feature.episodes.domain.KeyStatusProvider
import com.vovoscan.feature.episodes.domain.LoopCue
import com.vovoscan.feature.episodes.domain.PrepareResult
import com.vovoscan.feature.episodes.domain.RenderSummary
import com.vovoscan.feature.episodes.domain.StageProgress
import com.vovoscan.feature.episodes.domain.Timeline
import com.vovoscan.feature.episodes.domain.TimelineBubble
import com.vovoscan.feature.episodes.domain.TimelinePage
import com.vovoscan.feature.episodes.domain.Watch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun episode(
    status: EpisodeStatus,
    progress: StageProgress? = null,
    error: String? = null,
    degraded: Boolean? = null,
    silent: Boolean? = null,
    outdated: Boolean = false,
    canReread: Boolean = false,
    render: Boolean = status == EpisodeStatus.Ready,
    export: EpisodeExport? = null,
) = Episode(
    chapterId = "c1",
    status = status,
    progress = progress,
    error = error,
    pageCount = 12,
    degraded = degraded,
    silent = silent,
    render = if (render) RenderSummary("r1", "rk", "magiv2", "claude-haiku-5-5", "silent", degraded == true, silent == true, 0) else null,
    outdated = outdated,
    canReread = canReread,
    updatedAtMillis = 0,
    export = export,
)

internal class FakeKeyStatus(
    initial: KeyStatus?,
) : KeyStatusProvider {
    val status = MutableStateFlow(initial)
    var refreshes = 0

    override fun observe(): Flow<KeyStatus?> = status

    override suspend fun refresh() {
        refreshes += 1
    }
}

/** An episode store in memory; `follow` waits until the test finishes it (or cancels it). */
internal class FakeEpisodes(
    initial: Episode? = null,
) : EpisodesRepository {
    val stored = MutableStateFlow(initial)
    var refreshResult: AppResult<Episode?>? = null
    var runResult: (reread: Boolean) -> AppResult<PrepareResult> = { AppResult.Ok(PrepareResult.Queued(stored.value!!)) }
    var follows = 0
    var cancelledFollows = 0
    var followEnd = CompletableDeferred<AppResult<Episode?>>()
    val runs = mutableListOf<Boolean>()
    var watchResults = ArrayDeque<AppResult<Watch>>()
    val watchCalls = mutableListOf<Boolean>()

    /** The language of every call, in order ("" = the original). */
    val languages = mutableListOf<String>()

    /** Episodes in other languages, by code; the original is [stored]. */
    val inLanguage = mutableMapOf<String, MutableStateFlow<Episode?>>()

    /** The chapter id of every observe, refresh and run, in order. */
    val chapterIds = mutableListOf<String>()

    private fun store(lang: String) = if (lang.isEmpty()) stored else inLanguage.getOrPut(lang) { MutableStateFlow(null) }

    override fun observe(
        chapterId: String,
        lang: String,
    ): Flow<Episode?> {
        chapterIds += chapterId
        return store(lang)
    }

    override suspend fun refresh(
        chapterId: String,
        lang: String,
    ): AppResult<Episode?> {
        languages += lang
        chapterIds += chapterId
        return refreshResult ?: AppResult.Ok(store(lang).value)
    }

    override suspend fun prepare(
        chapterId: String,
        lang: String,
    ): AppResult<PrepareResult> {
        languages += lang
        chapterIds += chapterId
        return run(false)
    }

    override suspend fun reread(
        chapterId: String,
        lang: String,
    ): AppResult<PrepareResult> {
        languages += lang
        return run(true)
    }

    private fun run(reread: Boolean): AppResult<PrepareResult> {
        runs += reread
        val result = runResult(reread)
        if (result is AppResult.Ok) {
            when (val outcome = result.value) {
                is PrepareResult.Queued -> stored.value = outcome.episode
                is PrepareResult.AlreadyReady -> stored.value = outcome.episode
                is PrepareResult.Busy -> outcome.episode?.let { stored.value = it }
            }
        }
        return result
    }

    override suspend fun follow(
        chapterId: String,
        lang: String,
    ): AppResult<Episode?> {
        follows += 1
        try {
            return followEnd.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelledFollows += 1
            throw e
        }
    }

    override suspend fun watch(
        chapterId: String,
        forceRefresh: Boolean,
        lang: String,
    ): AppResult<Watch> {
        watchCalls += forceRefresh
        languages += lang
        return watchResults.removeFirstOrNull() ?: AppResult.Err(com.vovoscan.core.common.AppError.Network)
    }

    var exportRequests = 0
    var exportFetches = 0
    var exportRequestResult: () -> AppResult<EpisodeExport> = {
        AppResult.Ok(EpisodeExport(ExportStatus.Queued, 0, null, null, null, false))
    }
    var exportResult: () -> AppResult<EpisodeExport> = { AppResult.Err(com.vovoscan.core.common.AppError.Network) }

    override suspend fun requestExport(
        chapterId: String,
        lang: String,
    ): AppResult<EpisodeExport> {
        exportRequests += 1
        val result = exportRequestResult()
        if (result is AppResult.Ok) stored.value = stored.value?.copy(export = result.value)
        return result
    }

    override suspend fun export(
        chapterId: String,
        lang: String,
    ): AppResult<EpisodeExport> {
        exportFetches += 1
        val result = exportResult()
        if (result is AppResult.Ok) stored.value = stored.value?.copy(export = result.value)
        return result
    }

    override suspend fun clearLocal() = Unit
}

private val panel = Box(10.0, 10.0, 780.0, 500.0)

/** Three beats of a second: two on page 0 (the second one voiced, with a bed under everything), one on page 1. */
internal fun watch(
    expiresAtMillis: Long = Long.MAX_VALUE / 2,
    renderKey: String = "rk",
): Watch {
    val timeline =
        Timeline(
            chapterHash = "h",
            renderKey = renderKey,
            pages = listOf(TimelinePage(0, 800, 1200), TimelinePage(1, 800, 1200)),
            bubbles =
                listOf(
                    TimelineBubble(
                        id = "b0001",
                        page = 0,
                        panelBbox = panel,
                        bubbleBbox = Box(40.0, 40.0, 200.0, 90.0),
                        text = "Hello!",
                        durationMs = 1000,
                    ),
                    TimelineBubble(
                        id = "b0002",
                        page = 0,
                        panelBbox = panel,
                        bubbleBbox = Box(300.0, 40.0, 200.0, 90.0),
                        text = "Who goes there?",
                        audioKey = "audio/aa/line.ogg",
                        durationMs = 1000,
                    ),
                    TimelineBubble(id = "b0003", page = 1, panelBbox = panel, bubbleBbox = panel, durationMs = 1000),
                ),
            beds = listOf(LoopCue("audio/bb/bed.ogg", 10_000, 0, 3_000)),
        )
    return Watch(
        chapterId = "c1",
        renderKey = renderKey,
        timeline = timeline,
        pageUrls = listOf("https://api.test/p0", "https://api.test/p1"),
        audioUrls = mapOf("audio/aa/line.ogg" to "https://api.test/line", "audio/bb/bed.ogg" to "https://api.test/bed"),
        expiresAtMillis = expiresAtMillis,
    )
}
