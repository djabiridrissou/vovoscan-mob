package com.vovoscan.feature.groups.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.i18n.LocalFormat
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.groups.domain.Ending
import com.vovoscan.feature.groups.domain.GameKind
import com.vovoscan.feature.groups.domain.Message
import com.vovoscan.feature.groups.domain.MessageKind
import com.vovoscan.feature.groups.domain.MessageStatus
import com.vovoscan.feature.groups.domain.Sticker
import com.vovoscan.feature.groups.domain.SystemEvent

/** A refusal in the app's words: the contract's codes have their line (`errors.GROUP_*`, `errors.GAME_*`). */
internal fun groupError(error: AppError): String =
    when {
        error is AppError.Rejected && error.code == "GROUP_LINKS_LATER" -> t("errors.GROUP_LINKS_LATER")
        error == AppError.Forbidden -> t("groups.forbidden")
        else -> error.userMessage()
    }

/** A sticker's glyph and its label in the UI language (screen readers). */
data class StickerUi(
    val key: String,
    val glyph: String,
    val label: String,
) {
    companion object {
        fun of(sticker: Sticker) = StickerUi(sticker.key, sticker.glyph, t("sticker.${sticker.key}"))

        val all: List<StickerUi> get() = Sticker.entries.map(::of)
    }
}

internal object MessageLines {
    /** A list's last line: "Aya: see you at chapter 40", "Aya: BOOM", a system line in the UI language. */
    fun preview(m: Message): String {
        val author = m.authorName.orEmpty()
        val content =
            when {
                m.status == MessageStatus.Deleted -> t("groups.message.deleted")
                m.status == MessageStatus.Removed -> t("groups.message.removed")
                m.status == MessageStatus.Held -> t("groups.message.held")
                m.kind == MessageKind.System -> return m.system?.let(::system).orEmpty()
                m.kind == MessageKind.Sticker -> m.sticker?.glyph.orEmpty()
                m.kind == MessageKind.Game -> m.card?.let { t("games.card.started", "game" to gameName(it.game)) }.orEmpty()
                else ->
                    m.body
                        .lineSequence()
                        .firstOrNull()
                        .orEmpty()
            }
        return if (author.isEmpty()) content else t("groups.message.preview", "name" to author, "text" to content)
    }

    /** The server's system events, phrased here (never the server's words). */
    fun system(e: SystemEvent): String {
        val name = e.name ?: t("groups.someone")
        return when (e.event) {
            "created" -> t("groups.system.created", "name" to name)
            "joined" -> t("groups.system.joined", "name" to name)
            "left" -> t("groups.system.left", "name" to name)
            "removed" -> t("groups.system.removed", "name" to name)
            "role" ->
                when (e.role) {
                    "owner" -> t("groups.system.owner", "name" to name)
                    "admin" -> t("groups.system.admin", "name" to name)
                    else -> t("groups.system.member", "name" to name)
                }
            "renamed" -> t("groups.system.renamed", "name" to name)
            "verified" -> t("groups.system.verified")
            "game" -> gameOver(e)
            else -> t("groups.system.other")
        }
    }

    private fun gameOver(e: SystemEvent): String {
        val game = e.game?.let(::gameName) ?: t("games.aGame")
        return when {
            Ending.of(e.ending) == Ending.Expired -> t("games.over.expired", "game" to game)
            e.winners.isEmpty() -> t("games.over.draw", "game" to game)
            else -> t("games.over.won", "game" to game, "names" to e.winners.joinToString(", "), "count" to e.winners.size)
        }
    }

    fun gameName(kind: GameKind): String = t("games.${kind.wire}.name")
}

/** "BOOM" with its label for screen readers ("Impact"). */
internal fun Sticker.a11y(): String = "$glyph, ${t("sticker.$key")}"

/** "3:05 PM" or "15:05", from epoch millis in the device's offset. */
internal fun clockTime(localMillis: Long): String = LocalFormat.time(localMillis)
