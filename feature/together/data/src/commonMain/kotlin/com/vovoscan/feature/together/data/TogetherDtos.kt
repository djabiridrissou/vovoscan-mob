package com.vovoscan.feature.together.data

import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.feature.together.domain.ChapterKind
import com.vovoscan.feature.together.domain.Device
import com.vovoscan.feature.together.domain.InviteCode
import com.vovoscan.feature.together.domain.InvitePreview
import com.vovoscan.feature.together.domain.Member
import com.vovoscan.feature.together.domain.PagePoint
import com.vovoscan.feature.together.domain.Place
import com.vovoscan.feature.together.domain.Room
import com.vovoscan.feature.together.domain.RoomBrief
import com.vovoscan.feature.together.domain.RoomChapter
import com.vovoscan.feature.together.domain.Signal
import com.vovoscan.feature.together.domain.SignalKind
import com.vovoscan.feature.together.domain.Sticker
import kotlinx.serialization.Serializable

/** A place: comic `{kind, page, fraction, panel?}` or book `{kind, section, block, offset}` (nulls are not sent). */
@Serializable
data class PlaceDto(
    val kind: String,
    val page: Int? = null,
    val fraction: Double? = null,
    val panel: Int? = null,
    val section: Int? = null,
    val block: Int? = null,
    val offset: Int? = null,
) {
    fun toDomain(): Place? =
        when (kind) {
            "comic" -> page?.let { Place.Comic(it, fraction ?: 0.0, panel) }
            "book" -> section?.let { Place.Book(it, block ?: 0, offset ?: 0) }
            else -> null
        }

    companion object {
        fun of(place: Place): PlaceDto =
            when (place) {
                is Place.Comic -> PlaceDto(kind = "comic", page = place.page, fraction = place.fraction, panel = place.panel)
                is Place.Book -> PlaceDto(kind = "book", section = place.section, block = place.block, offset = place.offset)
            }
    }
}

@Serializable
data class PagePointDto(
    val page: Int,
    val x: Double,
    val y: Double,
) {
    fun toDomain() = PagePoint(page, x, y)
}

@Serializable
data class ChapterDto(
    val id: String,
    val number: Double? = null,
    val name: String? = null,
    val kind: String = "comic",
    val pageCount: Int = 0,
    val sourceKey: String = "",
    val titleRef: String = "",
    val titleName: String = "",
) {
    fun toDomain() = RoomChapter(id, number, name, kindOf(kind), pageCount, sourceKey, titleRef, titleName)
}

@Serializable
data class MemberDto(
    val userId: String,
    val name: String = "",
    val slot: Int = 0,
    val role: String = "reader",
    val here: Boolean = true,
    val following: Boolean = false,
    val position: PlaceDto? = null,
    val language: String? = null,
    val device: String? = null,
    val joinedAt: String = "",
    val lastSeenAt: String = "",
) {
    fun toDomain() =
        Member(
            userId = userId,
            name = name,
            slot = slot.coerceIn(0, 7),
            host = role == "host",
            here = here,
            following = following,
            place = position?.toDomain(),
            language = language,
            device = Device.of(device),
            joinedAtMillis = parseIsoMillis(joinedAt) ?: 0,
            lastSeenAtMillis = parseIsoMillis(lastSeenAt) ?: 0,
        )
}

@Serializable
data class SignalDto(
    val id: String,
    val kind: String,
    val userId: String? = null,
    val name: String? = null,
    val sticker: String? = null,
    val text: String? = null,
    val at: PagePointDto? = null,
    val chapterId: String? = null,
    val chapterNumber: Double? = null,
    val targetUserId: String? = null,
    val createdAt: String = "",
) {
    /** Null for a kind this build does not know (a newer server). */
    fun toDomain(): Signal? =
        SignalKind.of(kind)?.let {
            Signal(
                id = id,
                kind = it,
                userId = userId,
                name = name,
                sticker = Sticker.of(sticker),
                text = text,
                at = at?.toDomain(),
                chapterId = chapterId,
                chapterNumber = chapterNumber,
                targetUserId = targetUserId,
                createdAtMillis = parseIsoMillis(createdAt) ?: 0,
            )
        }
}

@Serializable
data class MeDto(
    val userId: String,
    val role: String = "reader",
    val following: Boolean = false,
)

@Serializable
data class RoomDto(
    val id: String,
    val code: String,
    val prettyCode: String = "",
    val invitePath: String = "",
    val open: Boolean = true,
    val locked: Boolean = false,
    val capacity: Int = 8,
    val closesAt: String = "",
    val chapter: ChapterDto,
    val hostId: String,
    val position: PlaceDto? = null,
    val positionSeq: Long = 0,
    val members: List<MemberDto> = emptyList(),
    val signals: List<SignalDto> = emptyList(),
    val me: MeDto,
    val serverTime: String = "",
) {
    fun toDomain() =
        Room(
            id = id,
            code = code,
            prettyCode = prettyCode.ifEmpty { InviteCode.pretty(code) },
            invitePath = invitePath.ifEmpty { "/together/$code" },
            open = open,
            locked = locked,
            capacity = capacity,
            closesAtMillis = parseIsoMillis(closesAt) ?: 0,
            chapter = chapter.toDomain(),
            hostId = hostId,
            place = position?.toDomain(),
            placeSeq = positionSeq,
            members = members.map { it.toDomain() },
            signals = signals.mapNotNull { it.toDomain() },
            myId = me.userId,
            following = me.following && me.userId != hostId,
            serverTimeMillis = parseIsoMillis(serverTime) ?: 0,
        )
}

@Serializable
data class RoomBriefDto(
    val id: String,
    val open: Boolean = true,
    val chapter: ChapterDto,
    val hostId: String,
) {
    fun toDomain() = RoomBrief(id, open, chapter.id, hostId)
}

@Serializable
data class PreviewDto(
    val prettyCode: String,
    val titleName: String = "",
    val chapterNumber: Double? = null,
    val chapterName: String? = null,
    val kind: String = "comic",
    val hostName: String = "",
    val present: Int = 0,
    val capacity: Int = 8,
    val full: Boolean = false,
    val locked: Boolean = false,
) {
    fun toDomain() =
        InvitePreview(prettyCode, titleName, chapterNumber, chapterName, kindOf(kind), hostName, present, capacity, full, locked)
}

@Serializable
data class OpenBody(
    val chapterId: String,
    val position: PlaceDto? = null,
    val language: String? = null,
    val device: String? = null,
)

@Serializable
data class JoinBody(
    val code: String,
    val language: String? = null,
    val device: String? = null,
)

@Serializable
data class MeBody(
    val position: PlaceDto? = null,
    val following: Boolean? = null,
    val language: String? = null,
)

@Serializable
data class SignalBody(
    val kind: String,
    val sticker: String? = null,
    val text: String? = null,
    val at: PagePointDto? = null,
)

@Serializable
data class MoveBody(
    val chapterId: String,
    val position: PlaceDto? = null,
)

@Serializable
data class HostBody(
    val userId: String,
)

@Serializable
data class LockBody(
    val locked: Boolean,
)

private fun kindOf(wire: String) = if (wire == "book") ChapterKind.Book else ChapterKind.Comic
