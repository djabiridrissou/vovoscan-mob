package com.vovoscan.feature.episodes.data

import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.feature.episodes.domain.Box
import com.vovoscan.feature.episodes.domain.BubbleSound
import com.vovoscan.feature.episodes.domain.Direction
import com.vovoscan.feature.episodes.domain.Episode
import com.vovoscan.feature.episodes.domain.EpisodeStatus
import com.vovoscan.feature.episodes.domain.LoopCue
import com.vovoscan.feature.episodes.domain.Point
import com.vovoscan.feature.episodes.domain.Quad
import com.vovoscan.feature.episodes.domain.RenderSummary
import com.vovoscan.feature.episodes.domain.StageProgress
import com.vovoscan.feature.episodes.domain.Timeline
import com.vovoscan.feature.episodes.domain.TimelineBubble
import com.vovoscan.feature.episodes.domain.TimelinePage
import com.vovoscan.feature.episodes.domain.Watch
import kotlinx.serialization.Serializable

/** `StageProgressOut`. */
@Serializable
data class StageProgressDto(
    val stage: String,
    val step: String? = null,
    val done: Int? = null,
    val total: Int? = null,
)

/** `RenderSummaryOut`. */
@Serializable
data class RenderSummaryDto(
    val id: String,
    val renderKey: String,
    val analyzer: String? = null,
    val ocr: String? = null,
    val voice: String? = null,
    val degraded: Boolean = false,
    val silent: Boolean = false,
    val createdAt: String,
)

/** `EpisodeOut` (its `export` field, the MP4, is not part of M1 and is ignored). */
@Serializable
data class EpisodeDto(
    val chapterId: String,
    val status: String,
    val progress: StageProgressDto? = null,
    val error: String? = null,
    val pageCount: Int = 0,
    val degraded: Boolean? = null,
    val silent: Boolean? = null,
    val render: RenderSummaryDto? = null,
    val outdated: Boolean = false,
    val canReread: Boolean = false,
    val updatedAt: String,
) {
    /** Ready or failed: nothing more will change without a new run. */
    val isFinal: Boolean get() = status == EpisodeStatus.Ready.wire || status == EpisodeStatus.Failed.wire
}

/** The 409 `EPISODE_BUSY` envelope: `{code, message, data: {error, episode}}`. */
@Serializable
internal data class BusyEnvelope(
    val code: Int = 409,
    val message: String = "",
    val data: BusyData? = null,
)

@Serializable
internal data class BusyData(
    val error: String,
    val episode: EpisodeDto? = null,
)

@Serializable
data class TimelinePageDto(
    val index: Int,
    val width: Int,
    val height: Int,
)

@Serializable
data class BubbleTagsDto(
    val onomatopoeia: List<String> = emptyList(),
    val ambience: String = "none",
    val mood: String = "neutral",
)

@Serializable
data class BubbleSoundDto(
    val audioKey: String,
    val durationMs: Int,
    val kind: String,
    val spanMs: Int,
    val pan: Double = 0.0,
    val panEnd: Double? = null,
    // The contract's default: a sound without the flag (older timelines) counts as an impact.
    val impact: Boolean = true,
)

@Serializable
data class TimelineBubbleDto(
    val id: String,
    val page: Int,
    val panelBbox: List<Double>,
    val bubbleBbox: List<Double>,
    val text: String = "",
    val display: String? = null,
    val shout: Boolean = false,
    val tone: String = "normal",
    val audioKey: String? = null,
    val durationMs: Int,
    val leadMs: Int = 0,
    val tags: BubbleTagsDto = BubbleTagsDto(),
    val panelShape: List<List<Double>>? = null,
    val detailBbox: List<Double>? = null,
    val keepBbox: List<Double>? = null,
    val sounds: List<BubbleSoundDto> = emptyList(),
    val pan: Double = 0.0,
)

@Serializable
data class LoopCueDto(
    val audioKey: String,
    val durationMs: Int,
    val startMs: Long,
    val endMs: Long,
    val mood: String? = null,
)

/** The Timeline contract v1 (backend `timeline.schema.json`, camelCase as stored). */
@Serializable
data class TimelineDto(
    val v: Int = 1,
    val chapterHash: String = "",
    val renderKey: String = "",
    val pages: List<TimelinePageDto> = emptyList(),
    val bubbles: List<TimelineBubbleDto> = emptyList(),
    val direction: String = "rtl",
    val beds: List<LoopCueDto> = emptyList(),
    val music: List<LoopCueDto> = emptyList(),
)

/** `WatchOut`. */
@Serializable
data class WatchDto(
    val chapterId: String,
    val renderKey: String,
    val timeline: TimelineDto,
    val pageUrls: List<String> = emptyList(),
    val audioUrls: Map<String, String> = emptyMap(),
    val expiresAt: String,
)

internal fun EpisodeDto.toDomain(): Episode? {
    val status = EpisodeStatus.from(status) ?: return null
    return Episode(
        chapterId = chapterId,
        status = status,
        progress = progress?.let { StageProgress(it.stage, it.step, it.done, it.total) },
        error = error,
        pageCount = pageCount,
        degraded = degraded,
        silent = silent,
        render =
            render?.let {
                RenderSummary(
                    it.id,
                    it.renderKey,
                    it.analyzer,
                    it.ocr,
                    it.voice,
                    it.degraded,
                    it.silent,
                    parseIsoMillis(it.createdAt) ?: 0L,
                )
            },
        outdated = outdated,
        canReread = canReread,
        updatedAtMillis = parseIsoMillis(updatedAt) ?: 0L,
    )
}

private fun List<Double>.box(): Box = Box(this[0], this[1], this[2], this[3])

internal fun TimelineDto.toDomain(): Timeline =
    Timeline(
        chapterHash = chapterHash,
        renderKey = renderKey,
        pages = pages.map { TimelinePage(it.index, it.width, it.height) },
        bubbles =
            bubbles.map { b ->
                TimelineBubble(
                    id = b.id,
                    page = b.page,
                    panelBbox = b.panelBbox.box(),
                    bubbleBbox = b.bubbleBbox.box(),
                    text = b.text,
                    display = b.display,
                    shout = b.shout,
                    tone = b.tone,
                    audioKey = b.audioKey,
                    durationMs = b.durationMs,
                    leadMs = b.leadMs,
                    onomatopoeia = b.tags.onomatopoeia,
                    panelShape = b.panelShape?.let { q -> q.map { Point(it[0], it[1]) }.let { Quad(it[0], it[1], it[2], it[3]) } },
                    detailBbox = b.detailBbox?.box(),
                    keepBbox = b.keepBbox?.box(),
                    sounds = b.sounds.map { BubbleSound(it.audioKey, it.durationMs, it.kind, it.spanMs, it.pan, it.panEnd, it.impact) },
                    pan = b.pan,
                )
            },
        direction = if (direction == "ltr") Direction.Ltr else Direction.Rtl,
        beds = beds.map { LoopCue(it.audioKey, it.durationMs, it.startMs, it.endMs, it.mood) },
        music = music.map { LoopCue(it.audioKey, it.durationMs, it.startMs, it.endMs, it.mood) },
    )

internal fun WatchDto.toDomain(): Watch =
    Watch(chapterId, renderKey, timeline.toDomain(), pageUrls, audioUrls, parseIsoMillis(expiresAt) ?: 0L)
