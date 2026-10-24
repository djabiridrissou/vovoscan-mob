package com.vovoscan.feature.together.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.round

/*
 * Reading circles: two to eight accounts read one chapter together, live, each on their own device, in their
 * own mode and language (shared-reading contract v1, backend `features/reading_rooms`). One reader hosts, the others
 * follow the host's place by default.
 */

/** Where a reader is in the chapter, whatever their screen: a comic's reading line, or a book's section. */
sealed interface Place {
    /**
     * [page] = the page under the reading line (a third of the screen down), [fraction] = how far down that page the
     * line is (0..1, 4 decimals), [panel] = the reader's stop index when known.
     */
    data class Comic(
        val page: Int,
        val fraction: Double,
        val panel: Int? = null,
    ) : Place

    /** The book reader's locator: section index, block index, UTF-16 offset in the block. */
    data class Book(
        val section: Int,
        val block: Int = 0,
        val offset: Int = 0,
    ) : Place

    companion object {
        /** A comic place as the server takes it: fraction in 0..1, 4 decimals. */
        fun comic(
            page: Int,
            fraction: Double,
            panel: Int? = null,
        ): Comic = Comic(page.coerceAtLeast(0), round(fraction.coerceIn(0.0, 1.0) * 10_000) / 10_000, panel?.takeIf { it >= 0 })
    }
}

enum class ChapterKind { Comic, Book }

data class RoomChapter(
    val id: String,
    val number: Double?,
    val name: String?,
    val kind: ChapterKind,
    val pageCount: Int,
    val sourceKey: String,
    val titleRef: String,
    val titleName: String,
)

enum class Device(
    val wire: String,
) {
    Web("web"),
    Ios("ios"),
    Android("android"),
    ;

    companion object {
        fun of(wire: String?): Device? = entries.firstOrNull { it.wire == wire }
    }
}

data class Member(
    val userId: String,
    val name: String,
    /** 0..7: the reader's colour. */
    val slot: Int,
    val host: Boolean,
    /** As the server saw it; compute it again with [Presence.here] between snapshots. */
    val here: Boolean,
    val following: Boolean,
    /** Null while following. */
    val place: Place?,
    /** Null = the original. */
    val language: String?,
    val device: Device?,
    val joinedAtMillis: Long,
    val lastSeenAtMillis: Long,
)

/** The margin's eight stickers (the forum contract), the same keys and Latin glyphs: a circle's stickers. */
enum class Sticker(
    val key: String,
    val glyph: String,
) {
    Doki("doki", "DOKI"),
    Don("don", "BOOM"),
    Zawa("zawa", "BZZZ"),
    Gogogo("gogogo", "GRRR"),
    Kusu("kusu", "HAHA"),
    Gaan("gaan", "GASP"),
    Kira("kira", "BLING"),
    Shiin("shiin", "SHHH"),
    ;

    companion object {
        fun of(key: String?): Sticker? = entries.firstOrNull { it.key == key }
    }
}

enum class SignalKind(
    val wire: String,
) {
    Sticker("sticker"),
    Say("say"),

    /** Wait: wait for me. */
    Matte("matte"),

    /** Gather: the host calls readers on their own. */
    Gather("gather"),
    Join("join"),
    Leave("leave"),
    Host("host"),
    Chapter("chapter"),
    Removed("removed"),
    Closed("closed"),
    ;

    companion object {
        fun of(wire: String): SignalKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A sticker's point on a page, in fractions of that page. */
data class PagePoint(
    val page: Int,
    val x: Double,
    val y: Double,
)

data class Signal(
    /** Time-ordered. */
    val id: String,
    val kind: SignalKind,
    val userId: String?,
    val name: String?,
    val sticker: Sticker?,
    val text: String?,
    val at: PagePoint?,
    val chapterId: String?,
    val chapterNumber: Double?,
    val targetUserId: String?,
    val createdAtMillis: Long,
)

data class Room(
    val id: String,
    /** "VK3Q9TZM". */
    val code: String,
    /** "VK3Q-9TZM". */
    val prettyCode: String,
    /** "/together/VK3Q9TZM". */
    val invitePath: String,
    val open: Boolean,
    val locked: Boolean,
    val capacity: Int,
    val closesAtMillis: Long,
    val chapter: RoomChapter,
    val hostId: String,
    val place: Place?,
    val placeSeq: Long,
    val members: List<Member>,
    /** The 30 latest, oldest first. */
    val signals: List<Signal>,
    val myId: String,
    val following: Boolean,
    val serverTimeMillis: Long,
) {
    val isHost: Boolean get() = myId == hostId

    val host: Member? get() = members.firstOrNull { it.userId == hostId }
}

/** An open room the caller is in (`GET /reading-rooms`). */
data class RoomBrief(
    val id: String,
    val open: Boolean,
    val chapterId: String,
    val hostId: String,
)

/** What a code shows before joining. */
data class InvitePreview(
    val prettyCode: String,
    val titleName: String,
    val chapterNumber: Double?,
    val chapterName: String?,
    val kind: ChapterKind,
    val hostName: String,
    val present: Int,
    val capacity: Int,
    val full: Boolean,
    val locked: Boolean,
)

interface TogetherRepository {
    /** The open rooms the caller is in (after a restart, or from another device). */
    suspend fun rooms(): AppResult<List<RoomBrief>>

    suspend fun open(
        chapterId: String,
        place: Place?,
        language: String?,
        device: Device,
    ): AppResult<Room>

    suspend fun preview(code: String): AppResult<InvitePreview>

    suspend fun join(
        code: String,
        language: String?,
        device: Device,
    ): AppResult<Room>

    suspend fun room(id: String): AppResult<Room>

    /**
     * Keeps the room live: every snapshot goes to [onRoom] (which returns false to stop), over the event stream with
     * reconnection (at once when the server ends it, else backoff 1, 2, 4… 30 s), polling `GET` every 5 s after 3
     * failures and trying the stream again every minute. Returns when [onRoom] stops, the room closes, or the caller
     * is no longer in it (the error).
     */
    suspend fun live(
        id: String,
        onRoom: suspend (Room) -> Boolean,
    ): AppResult<Unit>

    /** `PUT /{id}/me`: only what is given changes; nothing given is a heartbeat. */
    suspend fun me(
        id: String,
        place: Place? = null,
        following: Boolean? = null,
        language: String? = null,
    ): AppResult<Unit>

    suspend fun leave(id: String): AppResult<Unit>

    suspend fun signal(
        id: String,
        kind: SignalKind,
        sticker: Sticker? = null,
        text: String? = null,
        at: PagePoint? = null,
    ): AppResult<Signal>

    suspend fun moveTo(
        id: String,
        chapterId: String,
        place: Place?,
    ): AppResult<Room>

    suspend fun handOver(
        id: String,
        userId: String,
    ): AppResult<Room>

    suspend fun lock(
        id: String,
        locked: Boolean,
    ): AppResult<Room>

    suspend fun renewCode(id: String): AppResult<Room>

    suspend fun remove(
        id: String,
        userId: String,
    ): AppResult<Unit>

    suspend fun close(id: String): AppResult<Unit>
}

/** Circles show only with the `shared_reading` feature flag (gated by default; every feature for a super-admin). */
fun interface TogetherAccess {
    fun enabled(): Flow<Boolean>

    companion object {
        val Off = TogetherAccess { flowOf(false) }
    }
}

/** The language a reader reads in (null = the original), told to the others. */
fun interface TogetherLanguage {
    suspend fun current(): String?

    companion object {
        val Original = TogetherLanguage { null }
    }
}

/**
 * Readers the margin muted (`/forum/mutes`): hidden from one's signals (they still see you; nobody is told). The
 * margin's slice keeps them; circles only ask.
 */
fun interface MutedReaders {
    suspend fun ids(): Set<String>

    companion object {
        val None = MutedReaders { emptySet() }
    }
}

/** Which device this is, for the dock's stamps. */
data class ThisDevice(
    val device: Device,
)

/** Invite codes: 8 characters of Crockford base32, shown "VK3Q-9TZM", typed forgivingly. */
object InviteCode {
    const val LENGTH = 8
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Case, dashes and spaces ignored, O read as 0, I and L as 1; null unless 8 valid characters remain. */
    fun normalize(input: String): String? {
        val code =
            input
                .filterNot { it.isWhitespace() || it == '-' }
                .uppercase()
                .map {
                    when (it) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> it
                    }
                }.joinToString("")
        return code.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    /** "VK3Q9TZM" -> "VK3Q-9TZM". */
    fun pretty(code: String): String = if (code.length == LENGTH) code.substring(0, 4) + "-" + code.substring(4) else code

    /**
     * The code in an invite: `vovoscan://together/CODE`, `https://<host>/together/CODE` (any host: the code is the
     * key), or a code typed alone.
     */
    fun fromLink(link: String): String? {
        val trimmed = link.trim()
        val marker = "together/"
        val at = trimmed.indexOf(marker)
        if (at < 0) return normalize(trimmed)
        if (!trimmed.startsWith("vovoscan://") && !trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return null
        val rest =
            trimmed
                .substring(at + marker.length)
                .substringBefore('?')
                .substringBefore('#')
                .trimEnd('/')
        return normalize(rest)
    }

    fun appLink(code: String): String = "vovoscan://together/$code"
}

/** What a line may hold before it is sent (the server checks the same). */
object Lines {
    const val MAX = 280
    private val LINK = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)

    sealed interface Check {
        data class Ok(
            val text: String,
        ) : Check

        data object Empty : Check

        data object TooLong : Check

        data object Link : Check
    }

    /** One line: control characters and line breaks become spaces, runs of spaces one, trimmed. */
    fun check(raw: String): Check {
        val text =
            raw
                .map { if (it.isISOControl() || it == ' ' || it == ' ') ' ' else it }
                .joinToString("")
                .replace(Regex(" {2,}"), " ")
                .trim()
        return when {
            text.isEmpty() -> Check.Empty
            text.length > MAX -> Check.TooLong
            LINK.containsMatchIn(text) -> Check.Link
            else -> Check.Ok(text)
        }
    }
}

/** Who is here: a reader who wrote anything (a move, a heartbeat) in the last 45 s. */
object Presence {
    const val HERE_MS = 45_000L
    const val HEARTBEAT_MS = 20_000L

    /** [offsetMs] = server time − device time at the last snapshot. */
    fun here(
        lastSeenAtMillis: Long,
        nowMillis: Long,
        offsetMs: Long,
    ): Boolean = nowMillis + offsetMs - lastSeenAtMillis < HERE_MS
}

/** A reader's place on the rail: a share of the chapter (0..1). */
object Rail {
    fun share(
        place: Place?,
        pageCount: Int,
    ): Double? =
        when (place) {
            null -> null
            is Place.Comic -> if (pageCount <= 0) null else ((place.page + place.fraction) / pageCount).coerceIn(0.0, 1.0)
            is Place.Book -> if (pageCount <= 0) null else ((place.section + 0.5) / pageCount).coerceIn(0.0, 1.0)
        }
}
