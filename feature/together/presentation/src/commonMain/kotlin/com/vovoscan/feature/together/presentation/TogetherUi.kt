package com.vovoscan.feature.together.presentation

import com.vovoscan.core.common.DeviceTimeZone
import com.vovoscan.core.common.i18n.LocalFormat
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.together.domain.ChapterKind
import com.vovoscan.feature.together.domain.InvitePreview
import com.vovoscan.feature.together.domain.Room
import com.vovoscan.feature.together.domain.RoomChapter
import com.vovoscan.feature.together.domain.Signal
import com.vovoscan.feature.together.domain.SignalKind
import com.vovoscan.feature.together.domain.Sticker

/** What the 一緒 sheet shows: the two ways in, the code field, or the invite ticket. */
enum class TogetherSheet { Choose, Join, Ticket }

/** A reader's hanko stamp in the dock: the initial in their colour, the host ringed red with 主, away dashed. */
data class StampUi(
    val userId: String,
    val initial: String,
    val name: String,
    /** 0..7: the reader's colour (`BrandColors.circle(slot)`). */
    val slot: Int,
    val host: Boolean,
    val away: Boolean,
    val me: Boolean,
    /** 待って: this reader asked the host to wait (a cyan ring for a moment). */
    val asking: Boolean,
    val a11y: String,
)

/** A dot on the rail: a reader's place as a share of the chapter (0 top, 1 bottom). */
data class RailDot(
    val userId: String,
    val slot: Int,
    val share: Float,
    val host: Boolean,
    val me: Boolean,
)

data class StickerUi(
    val key: String,
    val glyph: String,
)

/**
 * A sticker bursting on the page for 1.6 s. With a point ([page] ≥ 0): at [x], [y] (fractions of that page);
 * otherwise at the reading line, offset by the sender's colour ([slot]). [count] > 1: "×n".
 */
data class BurstUi(
    val id: Long,
    val glyph: String,
    val slot: Int,
    val page: Int,
    val x: Float,
    val y: Float,
    val count: Int,
) {
    val countLabel: String? get() = if (count > 1) "×$count" else null
}

/** A line floating as a balloon from the speaker's stamp for 5 s. */
data class BalloonUi(
    val id: Long,
    val userId: String,
    val slot: Int,
    val name: String,
    val text: String,
)

enum class ToastAction { Follow, Go }

/** One calm line at a time (joined, left, new host, 待って, 集合, moved on), also read out politely. */
data class ToastUi(
    val id: Long,
    val text: String,
    val action: ToastAction?,
    val actionLabel: String?,
    /** The chapter "Go" opens. */
    val chapterId: String? = null,
)

data class LogLineUi(
    val id: String,
    val slot: Int?,
    val text: String,
)

/** The host's choices about one reader (tap on their stamp). */
data class MemberMenuUi(
    val userId: String,
    val name: String,
    val handOverLabel: String,
    val removeLabel: String,
)

/** The admission ticket (入場券): the code in large type in a dashed frame. */
data class TicketUi(
    val code: String,
    val admission: String,
    val chapterLine: String,
    val closesLine: String,
    /** `vovoscan://together/CODE`. */
    val appLink: String,
    /** `/together/CODE`, joined to the website's address by the app. */
    val webPath: String,
    val locked: Boolean,
    val isHost: Boolean,
)

/** The circle on this chapter, as the dock and rail draw it. */
data class CircleUi(
    val stamps: List<StampUi>,
    val isHost: Boolean,
    val following: Boolean,
    /** "Following Aya" / "On your own · Follow"; null for the host. */
    val followLabel: String?,
    /** Host: "3 following · 1 on their own". */
    val hostSummary: String?,
    val canGather: Boolean,
    val rail: List<RailDot>,
    val log: List<LogLineUi>,
    val ticket: TicketUi,
    /** The circle reads another chapter now ("The circle is on Chapter 4"). */
    val elsewhere: String?,
)

data class PreviewUi(
    val code: String,
    val heading: String,
    val chapterLine: String,
    val hostLine: String,
    val presentLine: String,
    /** Why joining can't work ("This circle is full. Ask Aya for a new link."), else null. */
    val blocked: String?,
)

/**
 * What the native reader must do for the circle, once; answered with `TogetherIntent.CommandHandled(seq)`. A
 * [GoToPlace] goes to the reader as `ReaderIntent.GoToPlace`; [OpenChapter] opens that chapter of our catalog (a
 * follower's reader follows the host to the next chapter, or "Go").
 */
sealed interface TogetherCommand {
    val seq: Int

    data class GoToPlace(
        override val seq: Int,
        val page: Int,
        val fraction: Float,
        val section: Int,
        val animated: Boolean,
    ) : TogetherCommand

    data class OpenChapter(
        override val seq: Int,
        val chapterId: String,
        val titleRef: String,
    ) : TogetherCommand
}

internal object TogetherText {
    fun chapterLine(chapter: RoomChapter): String = chapterLine(chapter.titleName, chapter.number, chapter.name)

    fun chapterLine(
        title: String,
        number: Double?,
        name: String?,
    ): String {
        val label = number?.let { t("together.chapter", "n" to number(it)) }
        return listOfNotNull(title.takeIf { it.isNotBlank() }, label, name?.takeIf { it.isNotBlank() }).joinToString(" ✦ ")
    }

    fun number(value: Double): String = if (value == kotlin.math.floor(value)) value.toLong().toString() else value.toString()

    fun closes(millis: Long): String {
        val local = millis + DeviceTimeZone.offsetMillis(millis)
        return t("together.closesAt", "time" to LocalFormat.time(local))
    }

    /** The name's first letter (any script, a surrogate pair kept whole), upper case. */
    fun initial(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val end = if (trimmed[0].isHighSurrogate() && trimmed.length > 1) 2 else 1
        return trimmed.substring(0, end).uppercase()
    }

    fun preview(
        code: String,
        preview: InvitePreview,
    ): PreviewUi =
        PreviewUi(
            code = preview.prettyCode,
            heading = t(if (preview.kind == ChapterKind.Book) "together.previewBook" else "together.previewComic"),
            chapterLine = chapterLine(preview.titleName, preview.chapterNumber, preview.chapterName),
            hostLine = t("together.hostedBy", "name" to preview.hostName),
            presentLine = t("together.reading", "count" to preview.present, "capacity" to preview.capacity),
            blocked =
                when {
                    preview.full -> t("together.fullAsk", "name" to preview.hostName)
                    preview.locked -> t("together.lockedAsk", "name" to preview.hostName)
                    else -> null
                }.takeIf { code.isNotEmpty() },
        )

    fun log(
        signal: Signal,
        slot: Int?,
        room: Room,
    ): LogLineUi? {
        val name = signal.name.orEmpty()
        val text =
            when (signal.kind) {
                SignalKind.Say -> signal.text?.let { t("together.logSay", "name" to name, "text" to it) }
                SignalKind.Sticker -> signal.sticker?.let { t("together.logSay", "name" to name, "text" to it.glyph) }
                SignalKind.Matte -> t("together.logSay", "name" to name, "text" to "待って")
                SignalKind.Gather -> t("together.logSay", "name" to name, "text" to "集合")
                SignalKind.Join -> t("together.joined", "name" to name)
                SignalKind.Leave -> t("together.left", "name" to name)
                SignalKind.Host -> if (signal.userId == room.myId) t("together.youHost") else t("together.newHost", "name" to name)
                SignalKind.Chapter -> t("together.movedOnLog", "name" to name, "n" to (signal.chapterNumber?.let(::number) ?: ""))
                SignalKind.Removed -> null
                SignalKind.Closed -> t("together.closed")
            } ?: return null
        return LogLineUi(signal.id, slot, text)
    }

    val stickers: List<StickerUi> = Sticker.entries.map { StickerUi(it.key, it.glyph) }
}
