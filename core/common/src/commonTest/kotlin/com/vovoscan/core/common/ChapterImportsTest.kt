package com.vovoscan.core.common

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterImportsTest {
    private val unit = SourceUnit("inkwell", "t9", "u3")

    private class Fake(
        var found: ImportedChapter? = null,
        val states: ArrayDeque<AppResult<ImportedChapter>> = ArrayDeque(),
        val imported: AppResult<ImportedChapter> = AppResult.Ok(chapter(ImportStatus.Fetching)),
    ) : ChapterImports {
        var imports = 0

        override suspend fun find(unit: SourceUnit): ImportedChapter? = found

        override suspend fun import(unit: SourceUnit): AppResult<ImportedChapter> {
            imports += 1
            return imported
        }

        override suspend fun status(chapterId: String): AppResult<ImportedChapter> = states.removeFirst()
    }

    @Test
    fun aCopyAlreadyReadyIsUsedAsItIs() =
        runTest {
            val fake = Fake(found = chapter(ImportStatus.Ready))
            assertEquals(AppResult.Ok(chapter(ImportStatus.Ready)), fake.bringIn(unit))
            assertEquals(0, fake.imports)
        }

    @Test
    fun aNewCopyIsFollowedUntilReadyAndADroppedPollIsNotAFailure() =
        runTest {
            val fake =
                Fake(
                    states =
                        ArrayDeque(
                            listOf(
                                AppResult.Ok(chapter(ImportStatus.Fetching)),
                                AppResult.Err(AppError.Network),
                                AppResult.Ok(chapter(ImportStatus.Ready, pages = 12)),
                            ),
                        ),
                )
            val seen = mutableListOf<ImportStatus>()
            val result = fake.bringIn(unit) { seen += it.status }
            assertEquals(AppResult.Ok(chapter(ImportStatus.Ready, pages = 12)), result)
            assertEquals(1, fake.imports)
            assertEquals(3 * ChapterImports.POLL_MS, currentTime)
            assertEquals(listOf(ImportStatus.Fetching, ImportStatus.Fetching, ImportStatus.Fetching, ImportStatus.Ready), seen)
        }

    @Test
    fun aFailedFetchCarriesItsCodeAndAFailedCopyIsAskedAgain() =
        runTest {
            val fake =
                Fake(
                    found = chapter(ImportStatus.Failed, error = "NO_PAGES"),
                    states = ArrayDeque(listOf(AppResult.Ok(chapter(ImportStatus.Failed, error = "HOST_NOT_ALLOWED")))),
                )
            val failed = (fake.bringIn(unit) as AppResult.Err).error
            assertEquals("HOST_NOT_ALLOWED", (failed as AppError.Rejected).code)
            assertEquals("The source sent its pages from a site it doesn’t declare.", failed.userMessage())
            assertEquals(1, fake.imports)
        }

    @Test
    fun aCopyFollowedFromBeforeThatFailedSinceIsFetchedAgainNotShownAsFailed() =
        runTest {
            // Remembered as fetching; the server's job failed meanwhile: asking again re-arms it.
            val fake =
                Fake(
                    found = chapter(ImportStatus.Fetching),
                    states =
                        ArrayDeque(
                            listOf(
                                AppResult.Ok(chapter(ImportStatus.Failed, error = "SOURCE_UNAVAILABLE")),
                                AppResult.Ok(chapter(ImportStatus.Ready, pages = 9)),
                            ),
                        ),
                )
            val seen = mutableListOf<ImportStatus>()
            val result = fake.bringIn(unit) { seen += it.status }
            assertEquals(AppResult.Ok(chapter(ImportStatus.Ready, pages = 9)), result)
            assertEquals(1, fake.imports)
            // The old failure never reaches the screen.
            assertEquals(listOf(ImportStatus.Fetching, ImportStatus.Fetching, ImportStatus.Ready), seen)
        }

    @Test
    fun aRefusedImportIsReturned() =
        runTest {
            val refused = AppResult.Err(AppError.Rejected("SOURCE_NOT_FOUND", "Source not found."))
            assertEquals(refused, Fake(imported = refused).bringIn(unit))
        }

    @Test
    fun aCopyThatNeverEndsIsGivenUpOnScreen() =
        runTest {
            val endless = ArrayDeque<AppResult<ImportedChapter>>()
            repeat((ChapterImports.MAX_WAIT_MS / ChapterImports.POLL_MS).toInt() + 1) {
                endless +=
                    AppResult.Ok(chapter(ImportStatus.Fetching))
            }
            assertEquals(AppResult.Err(AppError.Rejected(IMPORT_SLOW, "")), Fake(states = endless).bringIn(unit))
        }

    @Test
    fun onlyOurCatalogIsInternal() {
        assertEquals(true, SourceUnit(INTERNAL_SOURCE_KEY, "t", "c").internal)
        assertEquals(false, unit.internal)
    }

    private companion object {
        fun chapter(
            status: ImportStatus,
            pages: Int = 0,
            error: String? = null,
        ) = ImportedChapter("title-1", "chapter-1", status, pages, error)
    }
}
