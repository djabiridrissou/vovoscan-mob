package com.vovoscan.feature.reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.feature.reader.domain.ChapterDownload
import com.vovoscan.feature.reader.domain.DownloadActivity
import com.vovoscan.feature.reader.domain.DownloadBlock
import com.vovoscan.feature.reader.domain.DownloadState
import com.vovoscan.feature.reader.domain.DownloadableChapter
import com.vovoscan.feature.reader.domain.DownloadableTitle
import com.vovoscan.feature.reader.domain.DownloadsRepository
import com.vovoscan.feature.reader.domain.TitleChapters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What tapping a chapter's download control does. */
enum class DownloadAction {
    /** Not on the device (a comic chapter, a book or a part of it): download it. */
    Download,

    /** Queued, waiting or downloading: stop and forget it. */
    Cancel,

    /** On the device: erase it (the screen asks first). */
    Delete,

    Retry,

    /** Nothing to offer (the title page isn't known yet). */
    Unavailable,
}

/** A chapter's download, ready to draw: an icon for [status], a ring for [progress], a line of text. */
data class DownloadRow(
    val sourceKey: String,
    val chapterId: String,
    val label: String,
    /** NotDownloaded, Queued, Waiting, Downloading, Done, Failed. */
    val status: DownloadStatus,
    /** 0..1 while downloading. */
    val progress: Float,
    /** "Downloaded ✦ 4.2 MB", "Downloading ✦ 12 / 19", "Waiting for Wi-Fi"… */
    val text: String?,
    val action: DownloadAction,
    /** A failed chapter can also be dropped from the queue. */
    val removable: Boolean,
)

enum class DownloadStatus {
    NotDownloaded,
    Queued,
    Waiting,
    Downloading,
    Done,
    Failed,
}

/** Copy and sizes for downloads. */
object DownloadText {
    const val EMPTY = "Nothing downloaded yet. Download chapters from a title's page to read them offline."
    const val WIFI_ONLY = "Download on Wi-Fi only"
    const val WIFI_ONLY_HELP = "Off: downloads also use mobile data."

    /** "820 KB", "4.2 MB", "42 MB", "1.3 GB" (decimal units, as the systems show storage). */
    fun size(bytes: Long): String =
        when {
            bytes < 1_000_000 -> "${(bytes + 999) / 1000} KB"
            bytes < 10_000_000 -> "${oneDecimal(bytes / 100_000)} MB"
            bytes < 1_000_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
            else -> "${oneDecimal(bytes / 100_000_000)} GB"
        }

    private fun oneDecimal(tenths: Long) = "${tenths / 10}.${tenths % 10}"

    fun chapters(count: Int) = if (count == 1) "1 chapter" else "$count chapters"

    fun waiting(block: DownloadBlock?) = if (block == DownloadBlock.NoConnection) "Waiting for a connection" else "Waiting for Wi-Fi"

    /** Why the queue waits; on the Downloads screen the switch is right there (`here`). */
    fun notice(
        block: DownloadBlock?,
        here: Boolean = false,
    ): String? =
        when (block) {
            DownloadBlock.NoConnection -> "Downloads wait for a connection."
            DownloadBlock.NotOnWifi ->
                "Downloads wait for Wi-Fi. Turn off “Wi-Fi only”" + (if (here) "" else " in Downloads") + " to use mobile data."
            null -> null
        }

    /** What the background work shows (Android's notification): how much is left and what downloads now. */
    fun notification(activity: DownloadActivity): DownloadNotice {
        val what =
            listOfNotNull(
                activity.chapters.takeIf { it > 0 }?.let(::chapters),
                activity.books.takeIf { it > 0 }?.let { if (it == 1) "1 book" else "$it books" },
            ).joinToString(" and ")
        val current = activity.current
        val text =
            when {
                activity.block != null -> waiting(activity.block)
                current == null -> "Starting"
                else ->
                    listOfNotNull(
                        current.titleName,
                        current.label.takeIf { !current.isBook || it != current.titleName },
                        if (current.pageCount > 0) "${current.pagesDone} / ${current.pageCount}" else null,
                    ).joinToString(" ✦ ")
            }
        return DownloadNotice(
            title = if (what.isEmpty()) "Downloads" else "Downloading $what…",
            text = text,
            progress =
                current?.takeIf { activity.block == null && it.pageCount > 0 }?.let { it.pagesDone.toFloat() / it.pageCount },
        )
    }

    fun row(
        download: ChapterDownload,
        block: DownloadBlock?,
    ): DownloadRow {
        val status =
            when (download.state) {
                DownloadState.Queued -> DownloadStatus.Queued
                DownloadState.Waiting -> DownloadStatus.Waiting
                DownloadState.Downloading -> DownloadStatus.Downloading
                DownloadState.Done -> DownloadStatus.Done
                DownloadState.Failed -> DownloadStatus.Failed
            }
        val text =
            when (status) {
                DownloadStatus.Queued -> "Queued"
                DownloadStatus.Waiting -> waiting(block)
                DownloadStatus.Downloading ->
                    if (download.pageCount > 0) "Downloading ✦ ${download.pagesDone} / ${download.pageCount}" else "Downloading"
                DownloadStatus.Done -> (if (download.isBook) "Saved on this device" else "Downloaded") + " ✦ " + size(download.bytes)
                DownloadStatus.Failed -> download.error ?: "The download stopped. Try again."
                DownloadStatus.NotDownloaded -> null
            }
        return DownloadRow(
            sourceKey = download.sourceKey,
            chapterId = download.chapterId,
            label = download.label,
            status = status,
            progress =
                when {
                    status == DownloadStatus.Done -> 1f
                    download.pageCount > 0 -> download.pagesDone.toFloat() / download.pageCount
                    else -> 0f
                },
            text = text,
            action =
                when (status) {
                    DownloadStatus.Done -> DownloadAction.Delete
                    DownloadStatus.Failed -> DownloadAction.Retry
                    else -> DownloadAction.Cancel
                },
            removable = status == DownloadStatus.Failed,
        )
    }
}

/** The background work's notification, ready to draw. */
data class DownloadNotice(
    /** "Downloading 2 chapters…", "Downloading 1 book…". */
    val title: String,
    /** "Le Griot de Guerre ✦ Chapter 3 ✦ 12 / 19", or why the queue waits. */
    val text: String,
    /** 0..1 for the chapter downloading now; null when unknown (a book, a page list being fetched). */
    val progress: Float?,
)

data class TitleDownloadsUiState(
    /** A comic with chapters: download controls and bulk actions are shown. */
    val offersDownloads: Boolean = false,
    val isBook: Boolean = false,
    /** "3 of 12 downloaded ✦ 2 in the queue ✦ 42 MB", or "Saved on this device ✦ 1.2 MB" for a book. */
    val summary: String? = null,
    /** "Download next 5" (from the chapter being read), null when nothing is left. */
    val downloadNextLabel: String? = null,
    /** "Download all (12)", null when nothing is left beyond the next chapters; "Download the book" for a book. */
    val downloadAllLabel: String? = null,
    val canDeleteAll: Boolean = false,
    /** The "delete every download" link and its confirmation. */
    val deleteAllLabel: String = "Delete downloads",
    val deleteAllTitle: String = "Delete the downloads?",
    val deleteAllMessage: String = "",
    /** Why the queue waits (no connection, not on Wi-Fi). */
    val notice: String? = null,
    val rows: Map<String, DownloadRow> = emptyMap(),
    val sourceKey: String = "",
) {
    /** The row of one chapter (or book part) of the title page; not on the device: Download. */
    fun row(chapterId: String): DownloadRow =
        rows[chapterId] ?: DownloadRow(
            sourceKey = sourceKey,
            chapterId = chapterId,
            label = "",
            status = DownloadStatus.NotDownloaded,
            progress = 0f,
            text = null,
            action = if (offersDownloads) DownloadAction.Download else DownloadAction.Unavailable,
            removable = false,
        )
}

sealed interface TitleDownloadsIntent {
    /** The chapter row's control: does what its `action` says (the screen confirms a Delete first). */
    data class Act(
        val chapterId: String,
    ) : TitleDownloadsIntent

    /** Drops a failed chapter from the queue. */
    data class Remove(
        val chapterId: String,
    ) : TitleDownloadsIntent

    data object DownloadNext : TitleDownloadsIntent

    data object DownloadAll : TitleDownloadsIntent

    /** Erases every download of the title (the screen confirms first). */
    data object DeleteAll : TitleDownloadsIntent
}

/**
 * Downloads on a title page, next to the catalog's chapter list (two features on one screen, wired by the app):
 * a control per chapter, "Download next 5" from the chapter being read, "Download all", and deleting them. A book
 * (opened or not) gets "Download the book" and a control per part; once saved it reads "Saved on this device".
 */
class TitleDownloadsViewModel(
    private val sourceKey: String,
    private val titleId: String,
    private val repository: DownloadsRepository,
    chapters: TitleChapters,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TitleDownloadsUiState(sourceKey = sourceKey))
    val state: StateFlow<TitleDownloadsUiState> = mutableState.asStateFlow()
    private var title: DownloadableTitle? = null
    private var downloads: List<ChapterDownload> = emptyList()

    init {
        combine(chapters.observe(sourceKey, titleId), repository.observeTitle(sourceKey, titleId), repository.observeBlock()) { t, d, b ->
            Triple(t, d, b)
        }.onEach { (title, downloads, block) ->
            this.title = title
            this.downloads = downloads
            mutableState.value = stateOf(title, downloads, block)
        }.launchIn(viewModelScope)
    }

    fun onIntent(intent: TitleDownloadsIntent) {
        val title = title ?: return
        viewModelScope.launch {
            when (intent) {
                is TitleDownloadsIntent.Act -> {
                    val row = state.value.row(intent.chapterId)
                    when (row.action) {
                        DownloadAction.Download -> repository.enqueue(title, title.chapters.filter { it.id == intent.chapterId })
                        DownloadAction.Cancel, DownloadAction.Delete -> repository.remove(sourceKey, intent.chapterId)
                        DownloadAction.Retry -> repository.retry(sourceKey, intent.chapterId)
                        DownloadAction.Unavailable -> Unit
                    }
                }
                is TitleDownloadsIntent.Remove -> repository.remove(sourceKey, intent.chapterId)
                TitleDownloadsIntent.DownloadNext -> repository.enqueue(title, next(title, downloads))
                TitleDownloadsIntent.DownloadAll -> repository.enqueue(title, remaining(title, downloads))
                TitleDownloadsIntent.DeleteAll -> repository.removeTitle(sourceKey, titleId)
            }
        }
    }

    private fun stateOf(
        title: DownloadableTitle?,
        downloads: List<ChapterDownload>,
        block: DownloadBlock?,
    ): TitleDownloadsUiState {
        val rows = downloads.associate { it.chapterId to DownloadText.row(it, block) }
        if (title == null) return TitleDownloadsUiState(rows = rows, sourceKey = sourceKey)
        val total = downloads.sumOf { it.bytes }
        if (title.isBook) return bookState(title, downloads, block, rows, total)
        val ids = title.chapters.map { it.id }.toSet()
        val done = downloads.count { it.state == DownloadState.Done && it.chapterId in ids }
        val active = downloads.count { it.state != DownloadState.Done && it.state != DownloadState.Failed }
        val next = next(title, downloads)
        val left = remaining(title, downloads)
        return TitleDownloadsUiState(
            offersDownloads = title.chapters.isNotEmpty(),
            summary =
                listOfNotNull(
                    if (done > 0) "$done of ${title.chapters.size} downloaded" else null,
                    if (active > 0) "$active in the queue" else null,
                    if (total > 0) DownloadText.size(total) else null,
                ).joinToString(" ✦ ").ifEmpty { null },
            downloadNextLabel = if (next.isEmpty()) null else "Download next ${next.size}",
            downloadAllLabel = if (left.size > next.size) "Download all (${left.size})" else null,
            canDeleteAll = downloads.isNotEmpty(),
            deleteAllMessage = "Every chapter of ${title.titleName} saved on this device goes. Reading them will need the network again.",
            notice = if (active > 0) DownloadText.notice(block) else null,
            rows = rows,
            sourceKey = sourceKey,
        )
    }

    /** A book: saved once its file is read into sections (opened, or downloaded from here). */
    private fun bookState(
        title: DownloadableTitle,
        downloads: List<ChapterDownload>,
        block: DownloadBlock?,
        rows: Map<String, DownloadRow>,
        total: Long,
    ): TitleDownloadsUiState {
        val parts = title.chapters.size
        val saved = downloads.count { it.state == DownloadState.Done }
        val active = downloads.count { it.state != DownloadState.Done && it.state != DownloadState.Failed }
        val left = remaining(title, downloads)
        return TitleDownloadsUiState(
            offersDownloads = parts > 0,
            isBook = true,
            summary =
                listOfNotNull(
                    when {
                        saved == 0 -> null
                        parts > 1 && saved < parts -> "$saved of $parts parts saved"
                        else -> "Saved on this device"
                    },
                    if (active >
                        0
                    ) {
                        (if (downloads.any { it.state == DownloadState.Downloading }) "Downloading" else "In the queue")
                    } else {
                        null
                    },
                    if (total > 0) DownloadText.size(total) else null,
                ).joinToString(" ✦ ").ifEmpty { null },
            downloadAllLabel =
                when {
                    left.isEmpty() -> null
                    left.size < parts -> "Download the rest (${left.size})"
                    else -> "Download the book"
                },
            canDeleteAll = downloads.isNotEmpty(),
            deleteAllLabel = "Remove from this device",
            deleteAllTitle = "Remove the book from this device?",
            deleteAllMessage = "The copy of ${title.titleName} saved on this device goes. Reading it will need the network again.",
            notice = if (active > 0) DownloadText.notice(block) else null,
            rows = rows,
            sourceKey = sourceKey,
        )
    }

    private fun remaining(
        title: DownloadableTitle,
        downloads: List<ChapterDownload>,
    ): List<DownloadableChapter> {
        val taken = downloads.map { it.chapterId }.toSet()
        return title.chapters.filter { it.id !in taken }
    }

    /** The next chapters not on the device yet, from the one being read (else from the first). */
    private fun next(
        title: DownloadableTitle,
        downloads: List<ChapterDownload>,
    ): List<DownloadableChapter> {
        val from = title.chapters.indexOfFirst { it.id == title.resumeChapterId }.coerceAtLeast(0)
        val taken = downloads.map { it.chapterId }.toSet()
        return title.chapters
            .drop(from)
            .filter { it.id !in taken }
            .take(NEXT_COUNT)
    }

    companion object {
        const val NEXT_COUNT = 5
    }
}

/** One title on the Downloads screen. */
data class DownloadGroup(
    val sourceKey: String,
    val titleId: String,
    val titleName: String,
    /** "3 chapters ✦ 42 MB". */
    val meta: String,
    val rows: List<DownloadRow>,
)

data class DownloadsUiState(
    val groups: List<DownloadGroup> = emptyList(),
    /** "42 MB on this device". */
    val total: String = "",
    /** The Library's link: "Downloads ✦ 42 MB". */
    val entryLabel: String = "Downloads",
    val wifiOnly: Boolean = true,
    val notice: String? = null,
    val loaded: Boolean = false,
) {
    val empty: Boolean get() = loaded && groups.isEmpty()

    val emptyMessage: String get() = DownloadText.EMPTY

    val wifiOnlyLabel: String get() = DownloadText.WIFI_ONLY

    val wifiOnlyHelp: String get() = DownloadText.WIFI_ONLY_HELP
}

sealed interface DownloadsIntent {
    data class SetWifiOnly(
        val enabled: Boolean,
    ) : DownloadsIntent

    /** A row's control: does what its `action` says (the screen confirms a Delete first). */
    data class Act(
        val sourceKey: String,
        val chapterId: String,
    ) : DownloadsIntent

    data class Remove(
        val sourceKey: String,
        val chapterId: String,
    ) : DownloadsIntent

    /** Erases a title's downloads (the screen confirms first). */
    data class DeleteTitle(
        val sourceKey: String,
        val titleId: String,
    ) : DownloadsIntent
}

/** The Downloads screen (from the Library): every downloaded chapter and saved book, by title, the space they use. */
class DownloadsViewModel(
    private val repository: DownloadsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = mutableState.asStateFlow()

    init {
        combine(repository.observeAll(), repository.observeBlock(), repository.observeWifiOnly()) { all, block, wifiOnly ->
            stateOf(all, block, wifiOnly)
        }.onEach { mutableState.value = it }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: DownloadsIntent) {
        viewModelScope.launch {
            when (intent) {
                is DownloadsIntent.SetWifiOnly -> repository.setWifiOnly(intent.enabled)
                is DownloadsIntent.Act -> {
                    val row =
                        state.value.groups.flatMap { it.rows }.firstOrNull {
                            it.sourceKey == intent.sourceKey &&
                                it.chapterId == intent.chapterId
                        }
                    when (row?.action) {
                        DownloadAction.Cancel, DownloadAction.Delete -> repository.remove(intent.sourceKey, intent.chapterId)
                        DownloadAction.Retry -> repository.retry(intent.sourceKey, intent.chapterId)
                        else -> Unit
                    }
                }
                is DownloadsIntent.Remove -> repository.remove(intent.sourceKey, intent.chapterId)
                is DownloadsIntent.DeleteTitle -> repository.removeTitle(intent.sourceKey, intent.titleId)
            }
        }
    }

    private fun stateOf(
        all: List<ChapterDownload>,
        block: DownloadBlock?,
        wifiOnly: Boolean,
    ): DownloadsUiState {
        val groups =
            all
                .groupBy { it.sourceKey to it.titleId }
                .map { (key, items) ->
                    val sorted = items.sortedBy { it.number }
                    DownloadGroup(
                        sourceKey = key.first,
                        titleId = key.second,
                        titleName = sorted.first().titleName,
                        meta =
                            listOfNotNull(
                                if (sorted.all { it.isBook }) {
                                    if (sorted.size == 1) "Book" else "${sorted.size} parts"
                                } else {
                                    DownloadText.chapters(sorted.size)
                                },
                                sorted.sumOf { it.bytes }.takeIf { it > 0 }?.let(DownloadText::size),
                            ).joinToString(" ✦ "),
                        rows = sorted.map { DownloadText.row(it, block) },
                    )
                }.sortedBy { it.titleName.lowercase() }
        val bytes = all.sumOf { it.bytes }
        val active = all.any { it.state != DownloadState.Done && it.state != DownloadState.Failed }
        return DownloadsUiState(
            groups = groups,
            total = if (all.isEmpty()) "" else DownloadText.size(bytes) + " on this device",
            entryLabel = if (bytes == 0L) "Downloads" else "Downloads ✦ ${DownloadText.size(bytes)}",
            wifiOnly = wifiOnly,
            notice = if (active) DownloadText.notice(block, here = true) else null,
            loaded = true,
        )
    }
}
