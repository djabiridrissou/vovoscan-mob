package com.vovoscan.feature.reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.reader.domain.BookSection
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ReadingProgressSink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState(
    val titleName: String = "",
    /** « Chapter 2 · The last gate » ou « Full text ». */
    val chapterLabel: String = "",
    val mode: ReadingMode = ReadingMode.Vertical,
    val pages: List<ReaderPage> = emptyList(),
    /** Livre : sections de la table des matières. */
    val sections: List<BookSection> = emptyList(),
    /** Livre : la section courante, prête à afficher (HTML nettoyé et mis en forme). */
    val sectionDocument: String? = null,
    /** Livre en cours de téléchargement et d'ouverture. */
    val preparingBook: Boolean = false,
    /** Page (comic) ou section (livre) courante ; d'abord celle de la reprise de lecture. */
    val currentIndex: Int = 0,
    val previousChapterId: String? = null,
    val nextChapterId: String? = null,
    /** Barres du haut et du bas : un toucher au centre les masque ou les montre. */
    val chromeVisible: Boolean = true,
    val freshness: Freshness = Freshness.Refreshing,
    /** Jamais ouvert et pas de réseau : rien à montrer. */
    val unavailable: Boolean = false,
    val error: String? = null,
) {
    val isBook: Boolean get() = mode == ReadingMode.Book

    private val count: Int get() = if (isBook) sections.size else pages.size

    /** « 5 / 19 » (pages) ou « Section 2 / 12 » (livre). */
    val positionLabel: String
        get() =
            when {
                count == 0 -> ""
                isBook -> "Section ${currentIndex + 1} / $count"
                else -> "${currentIndex + 1} / $count"
            }

    val sectionTitle: String? get() = sections.getOrNull(currentIndex)?.title

    val canGoBack: Boolean get() = currentIndex > 0

    val canGoForward: Boolean get() = currentIndex < count - 1

    val atEnd: Boolean get() = count > 0 && currentIndex >= count - 1

    /** Bouton de fin d'unité. */
    val nextLabel: String? get() = nextChapterId?.let { if (isBook) "Next part →" else "Next chapter →" }

    val rightToLeft: Boolean get() = mode == ReadingMode.PagedRightToLeft
}

sealed interface ReaderIntent {
    /** Page la plus visible à l'écran (comic), ou section choisie (livre). */
    data class PageVisible(
        val index: Int,
    ) : ReaderIntent

    data object Next : ReaderIntent

    data object Previous : ReaderIntent

    data object ToggleChrome : ReaderIntent

    data object Retry : ReaderIntent
}

/**
 * Lecteur d'une unité, partagé par Compose et SwiftUI : comic (pages, sens de lecture) ou livre (sections d'un EPUB
 * ouvert sur l'appareil). Reprise à la page ou section enregistrée ; progression enregistrée après une courte pause.
 */
class ReaderViewModel(
    private val sourceKey: String,
    private val titleId: String,
    private val chapterId: String,
    startPage: Int,
    private val repository: ReaderRepository,
    private val progress: ReadingProgressSink,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReaderUiState(currentIndex = startPage.coerceAtLeast(0)))
    val state: StateFlow<ReaderUiState> = mutableState.asStateFlow()
    private var saveJob: Job? = null
    private var bookStarted = false

    init {
        repository
            .observe(sourceKey, chapterId)
            .onEach { chapter -> if (chapter != null) show(chapter) }
            .launchIn(viewModelScope)
        repository
            .observeBook(sourceKey, chapterId)
            .onEach { book ->
                if (book != null) {
                    mutableState.update {
                        it.copy(
                            sections = book.sections,
                            preparingBook = false,
                            currentIndex = it.currentIndex.coerceIn(0, book.sections.lastIndex),
                        )
                    }
                    loadSection(state.value.currentIndex)
                }
            }.launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: ReaderIntent) {
        when (intent) {
            is ReaderIntent.PageVisible -> moveTo(intent.index)
            ReaderIntent.Next -> moveTo(state.value.currentIndex + 1)
            ReaderIntent.Previous -> moveTo(state.value.currentIndex - 1)
            ReaderIntent.ToggleChrome -> mutableState.update { it.copy(chromeVisible = !it.chromeVisible) }
            ReaderIntent.Retry -> refresh()
        }
    }

    private fun show(chapter: ReaderChapter) {
        mutableState.update {
            it.copy(
                titleName = chapter.titleName,
                chapterLabel =
                    if (chapter.mode ==
                        ReadingMode.Book
                    ) {
                        chapter.label
                    } else {
                        listOfNotNull(chapter.label, chapter.name).joinToString(" · ")
                    },
                mode = chapter.mode,
                pages = chapter.pages,
                currentIndex =
                    if (chapter.mode ==
                        ReadingMode.Book
                    ) {
                        it.currentIndex
                    } else {
                        it.currentIndex.coerceIn(0, (chapter.pages.size - 1).coerceAtLeast(0))
                    },
                previousChapterId = chapter.previousChapterId,
                nextChapterId = chapter.nextChapterId,
                unavailable = false,
            )
        }
        if (chapter.mode == ReadingMode.Book && !bookStarted) prepareBook()
    }

    private fun prepareBook() {
        bookStarted = true
        mutableState.update { it.copy(preparingBook = it.sections.isEmpty()) }
        viewModelScope.launch {
            val result = repository.loadBook(sourceKey, chapterId)
            if (result is AppResult.Err) {
                bookStarted = false
                mutableState.update {
                    it.copy(
                        preparingBook = false,
                        unavailable = it.sections.isEmpty(),
                        error = result.error.userMessage(),
                    )
                }
            }
        }
    }

    private fun loadSection(index: Int) {
        viewModelScope.launch {
            val html = repository.sectionHtml(sourceKey, chapterId, index) ?: return@launch
            val title =
                state.value.sections
                    .getOrNull(index)
                    ?.title
                    .orEmpty()
            mutableState.update { if (it.currentIndex == index) it.copy(sectionDocument = BookStyle.document(title, html)) else it }
        }
    }

    private fun refresh() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.refresh(sourceKey, titleId, chapterId)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh)
                    is AppResult.Err -> {
                        val nothing = it.pages.isEmpty() && it.sections.isEmpty()
                        it.copy(
                            freshness = Freshness.Offline,
                            unavailable = nothing && it.titleName.isEmpty(),
                            error = if (result.error == AppError.Network && !nothing) null else result.error.userMessage(),
                        )
                    }
                }
            }
        }
    }

    private fun moveTo(index: Int) {
        val current = state.value
        val count = if (current.isBook) current.sections.size else current.pages.size
        if (index == current.currentIndex || index !in 0 until count) return
        mutableState.update { it.copy(currentIndex = index, sectionDocument = if (it.isBook) null else it.sectionDocument) }
        if (current.isBook) loadSection(index)
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                delay(SAVE_DELAY_MS)
                progress.save(sourceKey, titleId, chapterId, index)
            }
    }

    override fun onCleared() {
        // En quittant le lecteur, la dernière page vue est enregistrée sans attendre la fin de la pause.
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            progress.save(sourceKey, titleId, chapterId, state.value.currentIndex)
        }
        super.onCleared()
    }

    private companion object {
        const val SAVE_DELAY_MS = 800L
    }
}
