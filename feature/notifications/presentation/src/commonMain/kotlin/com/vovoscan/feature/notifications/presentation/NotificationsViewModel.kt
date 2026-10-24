package com.vovoscan.feature.notifications.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.notifications.domain.Inbox
import com.vovoscan.feature.notifications.domain.Notification
import com.vovoscan.feature.notifications.domain.NotificationKind
import com.vovoscan.feature.notifications.domain.NotificationsRepository
import com.vovoscan.feature.notifications.domain.Preferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationRow(
    val id: String,
    val title: String,
    val body: String,
    /** "3 min", "2 h", "4 d". */
    val age: String,
    val unread: Boolean,
    /** `group`, `game`, `badge`: the row's icon. */
    val icon: String,
    val target: NotificationTarget,
)

data class KindToggle(
    val kind: String,
    val label: String,
    val on: Boolean,
)

data class NotificationPrefsUi(
    val push: Boolean = true,
    val kinds: List<KindToggle> = emptyList(),
    val saving: Boolean = false,
)

/** A tap that opens something, answered with [NotificationsIntent.OpenHandled]. */
data class OpenTarget(
    val seq: Int,
    val target: NotificationTarget,
)

data class NotificationsUiState(
    val loading: Boolean = true,
    val rows: List<NotificationRow> = emptyList(),
    val unread: Int = 0,
    val hasMore: Boolean = false,
    /** The server can push; when false, everything stays in the app (said once in the settings). */
    val pushAvailable: Boolean = false,
    val prefs: NotificationPrefsUi = NotificationPrefsUi(),
    val notice: String? = null,
    val error: String? = null,
    val open: OpenTarget? = null,
) {
    /** The bell's badge: "3", "99+"; null when all is read. */
    val badge: String? get() = unread.takeIf { it > 0 }?.let { if (it > 99) "99+" else it.toString() }
    val empty: String get() = t("notifications.empty")
    val pushLine: String get() = if (pushAvailable) t("notifications.pushOn") else t("notifications.pushOff")
}

sealed interface NotificationsIntent {
    /** The app on screen: the live stream (the badge moves at once). */
    data object Start : NotificationsIntent

    data object Stop : NotificationsIntent

    data object Refresh : NotificationsIntent

    data object LoadMore : NotificationsIntent

    data class Open(
        val id: String,
    ) : NotificationsIntent

    data class OpenHandled(
        val seq: Int,
    ) : NotificationsIntent

    data object ReadAll : NotificationsIntent

    data object LoadPreferences : NotificationsIntent

    data class SetPush(
        val on: Boolean,
    ) : NotificationsIntent

    data class SetKind(
        val kind: String,
        val on: Boolean,
    ) : NotificationsIntent
}

/**
 * The inbox: live while the app is on screen (the bell's count), newest first, a tap opens its group or game and
 * marks it read, "Mark all read", and the preferences (pushes on or off, kinds turned off). One for the app: the
 * screens and the bell share it.
 */
class NotificationsViewModel(
    private val repository: NotificationsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = mutableState.asStateFlow()

    private var items: List<Notification> = emptyList()
    private var liveJob: Job? = null
    private var seq = 0
    private var prefs: Preferences? = null

    init {
        repository
            .observeInbox()
            .filterNotNull()
            .onEach { cached -> if (items.isEmpty()) show(cached, replace = true) }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: NotificationsIntent) {
        when (intent) {
            NotificationsIntent.Start -> follow()
            NotificationsIntent.Stop -> {
                liveJob?.cancel()
                liveJob = null
            }
            NotificationsIntent.Refresh -> refresh()
            NotificationsIntent.LoadMore -> loadMore()
            is NotificationsIntent.Open -> open(intent.id)
            is NotificationsIntent.OpenHandled -> mutableState.update { if (it.open?.seq == intent.seq) it.copy(open = null) else it }
            NotificationsIntent.ReadAll -> readAll()
            NotificationsIntent.LoadPreferences -> loadPreferences()
            is NotificationsIntent.SetPush -> savePreferences(push = intent.on)
            is NotificationsIntent.SetKind -> savePreferences(kind = intent.kind to intent.on)
        }
    }

    /** Signed out: nothing of the account stays in memory (one view model for the app). */
    fun reset() {
        liveJob?.cancel()
        liveJob = null
        items = emptyList()
        prefs = null
        mutableState.value = NotificationsUiState()
    }

    private fun follow() {
        if (liveJob?.isActive == true) return
        liveJob =
            viewModelScope.launch {
                val result =
                    repository.live { inbox ->
                        show(inbox, replace = false)
                        true
                    }
                if (result is AppResult.Err) mutableState.update { it.copy(loading = false) }
            }
    }

    private fun refresh() {
        viewModelScope.launch {
            when (val result = repository.inbox()) {
                is AppResult.Ok -> show(result.value, replace = true)
                is AppResult.Err ->
                    mutableState.update {
                        it.copy(
                            loading = false,
                            notice = if (result.error == AppError.Network) t("notifications.offline") else null,
                            error = if (result.error == AppError.Network) null else result.error.userMessage(),
                        )
                    }
            }
        }
    }

    private fun loadMore() {
        val last = items.lastOrNull()?.id ?: return
        if (!state.value.hasMore) return
        viewModelScope.launch {
            when (val result = repository.inbox(before = last)) {
                is AppResult.Ok -> {
                    items = (items + result.value.items).distinctBy { it.id }
                    mutableState.update { it.copy(hasMore = result.value.items.size >= PAGE) }
                    render(result.value.unread, result.value.pushAvailable)
                }
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.userMessage()) }
            }
        }
    }

    /** A snapshot (the latest 20) replaces the top of the list and keeps the older pages scrolled in. */
    private fun show(
        inbox: Inbox,
        replace: Boolean,
    ) {
        val fresh = inbox.items.map { it.id }.toSet()
        val oldest = inbox.items.lastOrNull()?.createdAtMillis ?: Long.MAX_VALUE
        items =
            if (replace) {
                inbox.items
            } else {
                inbox.items + items.filter { it.id !in fresh && it.createdAtMillis < oldest }
            }
        mutableState.update { it.copy(loading = false, notice = null, hasMore = if (replace) inbox.items.size >= PAGE else it.hasMore) }
        render(inbox.unread, inbox.pushAvailable)
    }

    private fun render(
        unread: Int,
        pushAvailable: Boolean,
    ) {
        mutableState.update { s ->
            s.copy(
                rows = items.map(::row),
                unread = unread,
                pushAvailable = pushAvailable,
            )
        }
    }

    private fun row(n: Notification): NotificationRow {
        val phrased = NotificationText.of(n.wireKind, n.data)
        return NotificationRow(
            id = n.id,
            title = phrased.title,
            body = phrased.body,
            age = age(n.createdAtMillis),
            unread = n.unread,
            icon =
                when (n.kind) {
                    NotificationKind.GameInvite, NotificationKind.GameTurn, NotificationKind.GameOver -> "game"
                    NotificationKind.GroupBadge -> "badge"
                    else -> "group"
                },
            target = NotificationText.target(n.data),
        )
    }

    private fun open(id: String) {
        val n = items.firstOrNull { it.id == id } ?: return
        seq += 1
        mutableState.update { it.copy(open = OpenTarget(seq, NotificationText.target(n.data))) }
        if (!n.unread) return
        val readAt = clock.nowMillis()
        items = items.map { if (it.id == id) it.copy(readAtMillis = readAt) else it }
        render((state.value.unread - 1).coerceAtLeast(0), state.value.pushAvailable)
        viewModelScope.launch { repository.read(listOf(id)) }
    }

    private fun readAll() {
        val readAt = clock.nowMillis()
        items = items.map { if (it.unread) it.copy(readAtMillis = readAt) else it }
        render(0, state.value.pushAvailable)
        viewModelScope.launch {
            val result = repository.read(null)
            if (result is AppResult.Err) refresh()
        }
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            when (val result = repository.preferences()) {
                is AppResult.Ok -> showPrefs(result.value)
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.userMessage()) }
            }
        }
    }

    private fun showPrefs(p: Preferences) {
        prefs = p
        mutableState.update {
            it.copy(
                prefs =
                    NotificationPrefsUi(
                        push = p.push,
                        kinds = p.kinds.map { k -> KindToggle(k, t("notifications.kind.${k.replace('.', '_')}"), k !in p.muted) },
                        saving = false,
                    ),
            )
        }
    }

    private fun savePreferences(
        push: Boolean? = null,
        kind: Pair<String, Boolean>? = null,
    ) {
        val current = prefs ?: return
        val muted =
            kind?.let { (k, on) -> if (on) current.muted - k else current.muted + k } ?: current.muted
        val next = current.copy(push = push ?: current.push, muted = muted)
        showPrefs(next)
        mutableState.update { it.copy(prefs = it.prefs.copy(saving = true)) }
        viewModelScope.launch {
            when (val result = repository.savePreferences(next.push, next.muted)) {
                is AppResult.Ok -> showPrefs(result.value)
                is AppResult.Err -> {
                    showPrefs(current)
                    mutableState.update { it.copy(error = result.error.userMessage()) }
                }
            }
        }
    }

    private fun age(at: Long): String {
        val minutes = ((clock.nowMillis() - at) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> t("forum.ageNow")
            minutes < 60 -> t("forum.ageMinutes", "n" to minutes)
            minutes < 60 * 24 -> t("forum.ageHours", "n" to minutes / 60)
            else -> t("forum.ageDays", "n" to minutes / (60 * 24))
        }
    }

    private companion object {
        const val PAGE = 20
    }
}
