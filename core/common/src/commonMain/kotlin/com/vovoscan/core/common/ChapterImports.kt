package com.vovoscan.core.common

import com.vovoscan.core.common.i18n.t
import kotlinx.coroutines.delay

/** Our own catalog's source key: its chapters are already on the server. */
const val INTERNAL_SOURCE_KEY = "vovoscan"

/** A chapter of a title, at any source: our catalog's ids, or the refs a source read on the device gives. */
data class SourceUnit(
    val sourceKey: String,
    val titleRef: String,
    val unitRef: String,
) {
    /** A chapter of our own catalog: the server knows it by [unitRef] already, nothing to bring in. */
    val internal: Boolean get() = sourceKey == INTERNAL_SOURCE_KEY
}

enum class ImportStatus {
    /** The server is copying the pages from the source. */
    Fetching,
    Ready,
    Failed,
    ;

    companion object {
        fun fromWire(value: String?): ImportStatus =
            when (value) {
                "ready" -> Ready
                "failed" -> Failed
                else -> Fetching
            }
    }
}

/**
 * A unit of a source read on the device, copied into our catalog so it can be translated and voiced
 * (`POST api/v1/source-reader/{key}/import`). [chapterId] and [titleId] are ours; once [ImportStatus.Ready], the
 * translation and episode routes take [chapterId], and `GET catalog/chapters/{chapterId}` reads its pages.
 */
data class ImportedChapter(
    val titleId: String,
    val chapterId: String,
    val status: ImportStatus,
    val pageCount: Int,
    /** `SOURCE_UNAVAILABLE`, `HOST_NOT_ALLOWED`, `NO_PAGES`, `INVALID_IMAGE` once failed. */
    val error: String? = null,
)

/**
 * Bringing a chapter of a source read on the device to the server, only when the reader asks to translate or watch it
 * (never on its own: the server then reads the source). A plugin's copy stays private to its owner.
 */
interface ChapterImports {
    /** Already brought in (remembered on this device, or known to the server); null when not yet. Starts nothing. */
    suspend fun find(unit: SourceUnit): ImportedChapter?

    /** Asks the server to copy it (idempotent: a unit already copied answers its state; a failed one is fetched again). */
    suspend fun import(unit: SourceUnit): AppResult<ImportedChapter>

    suspend fun status(chapterId: String): AppResult<ImportedChapter>

    /** No server copies (tests, a server without the source reader). */
    object None : ChapterImports {
        override suspend fun find(unit: SourceUnit): ImportedChapter? = null

        override suspend fun import(unit: SourceUnit): AppResult<ImportedChapter> = AppResult.Err(AppError.Rejected(NOT_SUPPORTED, ""))

        override suspend fun status(chapterId: String): AppResult<ImportedChapter> = AppResult.Err(AppError.Rejected(NOT_SUPPORTED, ""))
    }

    companion object {
        const val NOT_SUPPORTED = "IMPORT_NOT_SUPPORTED"

        /** The server's copy is checked this often while it fetches. */
        const val POLL_MS = 2_000L

        /** A fetch that runs longer is given up on screen (it goes on on the server; asking again finds it). */
        const val MAX_WAIT_MS = 10 * 60_000L
    }
}

/**
 * The unit's copy on the server, ready: imports it when needed, then follows it every [ChapterImports.POLL_MS] until
 * it is ready, failed (`Rejected(<fetch error code>)`) or too long. [onUpdate] sees each state.
 */
suspend fun ChapterImports.bringIn(
    unit: SourceUnit,
    onUpdate: (ImportedChapter) -> Unit = {},
): AppResult<ImportedChapter> {
    var current =
        find(unit)?.takeIf { it.status != ImportStatus.Failed }
            ?: when (val asked = import(unit)) {
                is AppResult.Err -> return asked
                is AppResult.Ok -> asked.value
            }
    var waited = 0L
    while (true) {
        onUpdate(current)
        when (current.status) {
            ImportStatus.Ready -> return AppResult.Ok(current)
            ImportStatus.Failed -> return AppResult.Err(failed(current.error ?: IMPORT_FAILED))
            ImportStatus.Fetching -> Unit
        }
        if (waited >= ChapterImports.MAX_WAIT_MS) return AppResult.Err(AppError.Rejected(IMPORT_SLOW, ""))
        delay(ChapterImports.POLL_MS)
        waited += ChapterImports.POLL_MS
        current =
            when (val next = status(current.chapterId)) {
                is AppResult.Ok -> next.value
                // A dropped poll is not a failed import: keep waiting while the server works.
                is AppResult.Err -> if (next.error == AppError.Network) current else return next
            }
    }
}

/** The copy failed without a reason. */
const val IMPORT_FAILED = "IMPORT_FAILED"

/** The copy is still running after [ChapterImports.MAX_WAIT_MS]. */
const val IMPORT_SLOW = "IMPORT_SLOW"

/**
 * A fetch the server gave up on. `HOST_NOT_ALLOWED` names no host here (the device's own refusal does, so that code
 * has no catalogue line of its own): the copy says what happened.
 */
private fun failed(code: String): AppError =
    AppError.Rejected(code, if (code == "HOST_NOT_ALLOWED") t("errors.importHostNotAllowed") else "")
