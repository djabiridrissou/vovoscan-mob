package com.vovoscan.feature.notifications.presentation

import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.notifications.domain.NotificationKind

/** A notification in the UI language: what the inbox row and the phone's notification say. */
data class Phrased(
    val title: String,
    val body: String,
)

/** Where tapping a notification goes: a group (by id, with its share name) and, for a game, the game. */
data class NotificationTarget(
    val groupId: String?,
    val handle: String?,
    val gameId: String?,
) {
    /** `vovoscan://g/<handle>` (and `?game=<id>`): the link the phone's notification opens. */
    val link: String?
        get() =
            handle?.let { h -> "vovoscan://g/$h" + (gameId?.let { "?game=$it" }.orEmpty()) }
}

/**
 * The sentences, written here from the notification's references (the server sends no sentence). Shared by the inbox
 * and Android's push service; the iOS notification service extension carries the same lines in Swift.
 */
object NotificationText {
    fun of(
        wireKind: String,
        data: Map<String, String>,
    ): Phrased {
        val group = data["groupName"].orEmpty()
        val name = data["name"] ?: t("notifications.someone")
        val count = data["count"]?.toIntOrNull() ?: 1
        val game = data["game"]?.let { t("games.$it.name") } ?: t("games.aGame")
        return when (NotificationKind.of(wireKind)) {
            NotificationKind.GroupRequest ->
                Phrased(
                    group,
                    t(
                        "notifications.groupRequest",
                        "name" to name,
                        "count" to count,
                        "group" to group,
                    ),
                )
            NotificationKind.GroupApproved -> Phrased(group, t("notifications.groupApproved", "group" to group))
            NotificationKind.GroupDeclined -> Phrased(group, t("notifications.groupDeclined", "group" to group))
            NotificationKind.GroupReply ->
                Phrased(
                    group,
                    if (count > 1) {
                        t("notifications.groupReplies", "count" to count)
                    } else {
                        t("notifications.groupReply", "name" to name, "excerpt" to data["excerpt"].orEmpty())
                    },
                )
            NotificationKind.GroupBadge ->
                Phrased(
                    group,
                    if (data["approved"] == "true") {
                        t("notifications.badgeApproved", "group" to group)
                    } else {
                        t("notifications.badgeDenied", "group" to group, "note" to data["note"].orEmpty())
                    },
                )
            NotificationKind.GameInvite -> Phrased(group.ifEmpty { game }, t("notifications.gameInvite", "name" to name, "game" to game))
            NotificationKind.GameTurn -> Phrased(group.ifEmpty { game }, t("notifications.gameTurn", "game" to game))
            NotificationKind.GameOver -> {
                val winners = data["winners"].orEmpty()
                Phrased(
                    group.ifEmpty { game },
                    if (winners.isEmpty()) {
                        t("notifications.gameOverDraw", "game" to game)
                    } else {
                        t("notifications.gameOver", "game" to game, "names" to winners)
                    },
                )
            }
            null -> Phrased(t("notifications.title"), t("notifications.generic"))
        }
    }

    fun target(data: Map<String, String>) = NotificationTarget(data["groupId"], data["handle"], data["gameId"])
}
