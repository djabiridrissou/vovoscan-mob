package com.vovoscan.feature.notifications.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/*
 * Notifications (backend `features/notifications`, contract docs/NOTIFICATIONS.md): an inbox for every account, live
 * in the app, and pushes to the phone sealed end to end (X25519, HKDF-SHA256, AES-256-GCM): Firebase and Apple carry
 * only ciphertext. A notification holds references and short texts; the app writes the sentence in its language.
 */

enum class NotificationKind(
    val wire: String,
) {
    GroupRequest("group.request"),
    GroupApproved("group.approved"),
    GroupDeclined("group.declined"),
    GroupReply("group.reply"),
    GroupBadge("group.badge"),
    GameInvite("game.invite"),
    GameTurn("game.turn"),
    GameOver("game.over"),
    ;

    companion object {
        fun of(wire: String?): NotificationKind? = entries.firstOrNull { it.wire == wire }
    }
}

data class Notification(
    val id: String,
    /** Null: a kind this app does not know yet (a newer server); shown with a generic line. */
    val kind: NotificationKind?,
    val wireKind: String,
    /** Texts and references only (`groupId`, `groupName`, `handle`, `name`, `excerpt`, `count`, `gameId`, `game`…). */
    val data: Map<String, String>,
    val createdAtMillis: Long,
    val readAtMillis: Long?,
) {
    val unread: Boolean get() = readAtMillis == null
}

data class Inbox(
    /** Newest first. */
    val items: List<Notification>,
    /** Capped at 100 by the server. */
    val unread: Int,
    /** The server can push (Firebase is set up there); else everything stays in the app. */
    val pushAvailable: Boolean,
)

data class Preferences(
    val push: Boolean,
    /** Kinds turned off: neither in the app nor pushed. */
    val muted: Set<String>,
    /** Every kind the server sends. */
    val kinds: List<String>,
)

/** What a device registers so pushes can be sealed for it (never the private half, which stays on the device). */
data class DeviceKey(
    /** A UUID kept for the install. */
    val deviceId: String,
    val keyId: String,
    /** Base64url of the raw 32-byte X25519 public key. */
    val publicKey: String,
)

/**
 * The phone's side of pushes, implemented natively: the key pair in the Keychain (shared with the notification
 * service extension through the app group) or in Keystore-protected storage on Android, and the Firebase token. Every
 * function is quick and never throws; null = this build or device can't receive sealed pushes (no Firebase set up, no
 * secure storage): notifications stay in the app.
 */
interface PushDevice {
    /** `ios` or `android`. */
    val platform: String

    /** This install's key, made on first use. */
    fun key(): DeviceKey?

    /** A new pair under a new key id (now and then, and when the server lost the old one). */
    fun rotate(): DeviceKey?

    /** Signed out: the private half is erased (pushes sealed for it can't be opened any more). */
    fun forget()

    companion object {
        val None =
            object : PushDevice {
                override val platform = "none"

                override fun key(): DeviceKey? = null

                override fun rotate(): DeviceKey? = null

                override fun forget() = Unit
            }
    }
}

interface NotificationsRepository {
    /** The last inbox this device saw (offline display). */
    fun observeInbox(): Flow<Inbox?>

    /** Newest first; [before] = older than that notification (scrolling down). */
    suspend fun inbox(before: String? = null): AppResult<Inbox>

    /** Keeps the live stream open: [onInbox] for each snapshot (the latest 20 and the unread count) until false. */
    suspend fun live(onInbox: suspend (Inbox) -> Boolean): AppResult<Unit>

    /** Null = all. */
    suspend fun read(ids: List<String>?): AppResult<Unit>

    suspend fun preferences(): AppResult<Preferences>

    suspend fun savePreferences(
        push: Boolean,
        muted: Set<String>,
    ): AppResult<Preferences>

    suspend fun register(
        key: DeviceKey,
        platform: String,
        token: String,
        locale: String?,
    ): AppResult<Unit>

    suspend fun unregister(deviceId: String): AppResult<Unit>

    suspend fun clearLocal()
}
