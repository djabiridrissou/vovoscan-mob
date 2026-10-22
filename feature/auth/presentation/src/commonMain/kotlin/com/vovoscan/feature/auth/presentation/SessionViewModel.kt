package com.vovoscan.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.auth.domain.AccountRepository
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The banner while the account's deletion is scheduled: "Your account will be deleted on 21 November 2026." */
data class DeletionBannerUi(
    val text: String,
    val action: String,
    val working: Boolean,
    val error: String?,
)

/** Root of both apps: signed in or not, what the role allows to show, the tutorial due, a scheduled deletion. */
class SessionViewModel(
    private val auth: AuthRepository,
    private val account: AccountRepository,
) : ViewModel() {
    val session: StateFlow<Session?> = auth.session

    private val keeping = MutableStateFlow(false)
    private val keepError = MutableStateFlow<String?>(null)

    val deletionBanner: StateFlow<DeletionBannerUi?> =
        combine(auth.session, keeping, keepError) { session, working, error ->
            session?.deletionScheduledAtMillis?.let {
                DeletionBannerUi(t("deletion.banner", "date" to it), t("deletion.keep"), working, error)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The tutorial was opened from Settings › Preferences (replay), or is due after sign-in. */
    private val replaying = MutableStateFlow(false)
    val replay: StateFlow<Boolean> = replaying.asStateFlow()

    /** Full screen over the tabs: due once after sign-in (or a newer tutorial), or replayed. */
    val showTutorial: StateFlow<Boolean> =
        combine(auth.session, replaying) { session, replay -> session != null && (session.onboardingDue || replay) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, auth.session.value?.onboardingDue == true)

    fun replayTutorial() {
        replaying.value = true
    }

    /** The tutorial finished or was skipped (it also told the server). */
    fun tutorialClosed() {
        replaying.value = false
    }

    fun keepAccount() {
        if (keeping.value) return
        keeping.value = true
        keepError.value = null
        viewModelScope.launch {
            val result = account.cancelDeletion()
            keeping.value = false
            if (result is AppResult.Err) keepError.value = result.error.userMessage()
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}
