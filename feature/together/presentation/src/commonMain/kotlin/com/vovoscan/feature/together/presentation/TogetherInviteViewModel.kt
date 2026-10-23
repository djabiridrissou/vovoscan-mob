package com.vovoscan.feature.together.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.together.domain.InviteCode
import com.vovoscan.feature.together.domain.ThisDevice
import com.vovoscan.feature.together.domain.TogetherAccess
import com.vovoscan.feature.together.domain.TogetherLanguage
import com.vovoscan.feature.together.domain.TogetherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The chapter to open after joining: a chapter of our catalog (or a server copy), read through our API. */
data class JoinedChapter(
    val chapterId: String,
    val titleRef: String,
)

data class InviteUiState(
    val loading: Boolean = true,
    val preview: PreviewUi? = null,
    /** One calm line, never a dead end ("This link no longer opens a circle. Ask for a new one."). */
    val error: String? = null,
    val busy: Boolean = false,
    /** Set once joined: the app opens the reader there, already following. */
    val joined: JoinedChapter? = null,
) {
    val title: String get() = t("together.inviteTitle")
    val joinLabel: String get() = t("together.join")
    val closeLabel: String get() = t("common.close")
    val canJoin: Boolean get() = !busy && preview != null && preview.blocked == null && joined == null
}

sealed interface InviteIntent {
    data object Join : InviteIntent

    /** The app opened the reader on [InviteUiState.joined]. */
    data object Opened : InviteIntent
}

/**
 * An invite link opened from outside (`vovoscan://together/CODE`, or the website's page handing over to the app): a
 * preview card (series, chapter, host, "3 of 8 reading"), then Join. A link without a valid code, or with circles off
 * for the account, says so in one line.
 */
class TogetherInviteViewModel(
    link: String,
    private val repository: TogetherRepository,
    private val session: TogetherSession,
    private val access: TogetherAccess,
    private val language: TogetherLanguage,
    private val device: ThisDevice,
) : ViewModel() {
    private val code = InviteCode.fromLink(link)
    private val mutableState = MutableStateFlow(InviteUiState())
    val state: StateFlow<InviteUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            when {
                code == null -> mutableState.update { it.copy(loading = false, error = t("together.badLink")) }
                !access.enabled().first() -> mutableState.update { it.copy(loading = false, error = t("together.unavailable")) }
                else ->
                    when (val result = repository.preview(code)) {
                        is AppResult.Ok ->
                            mutableState.update {
                                it.copy(
                                    loading = false,
                                    preview = TogetherText.preview(code, result.value),
                                )
                            }
                        is AppResult.Err -> mutableState.update { it.copy(loading = false, error = message(result.error)) }
                    }
            }
        }
    }

    fun onIntent(intent: InviteIntent) {
        when (intent) {
            InviteIntent.Join -> join()
            InviteIntent.Opened -> mutableState.update { it.copy(joined = null) }
        }
    }

    private fun join() {
        val code = code ?: return
        if (!state.value.canJoin) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.join(code, language.current(), device.device)) {
                is AppResult.Ok -> {
                    val room = result.value
                    session.set(room)
                    session.openedChapter = room.chapter.id
                    mutableState.update { it.copy(busy = false, joined = JoinedChapter(room.chapter.id, room.chapter.titleRef)) }
                }
                is AppResult.Err -> mutableState.update { it.copy(busy = false, error = message(result.error)) }
            }
        }
    }

    private fun message(error: AppError): String =
        when {
            error == AppError.Network -> t("together.offline")
            error is AppError.Rejected && error.code == "ROOM_NOT_FOUND" -> t("together.badLink")
            error is AppError.Rejected && error.code in setOf("FEATURE_UNAVAILABLE", "FEATURE_DISABLED") -> t("together.unavailable")
            else -> error.userMessage()
        }
}
