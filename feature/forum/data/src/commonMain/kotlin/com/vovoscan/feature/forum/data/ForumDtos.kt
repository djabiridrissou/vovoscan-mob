package com.vovoscan.feature.forum.data

import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.feature.forum.domain.Anchor
import com.vovoscan.feature.forum.domain.Author
import com.vovoscan.feature.forum.domain.Margin
import com.vovoscan.feature.forum.domain.MutedReader
import com.vovoscan.feature.forum.domain.Post
import com.vovoscan.feature.forum.domain.PostKind
import com.vovoscan.feature.forum.domain.PostStatus
import com.vovoscan.feature.forum.domain.PostTranslation
import com.vovoscan.feature.forum.domain.Replies
import com.vovoscan.feature.forum.domain.Salon
import com.vovoscan.feature.forum.domain.SalonWeek
import com.vovoscan.feature.forum.domain.Sticker
import com.vovoscan.feature.forum.domain.Theories
import com.vovoscan.feature.forum.domain.TranslationStatus
import com.vovoscan.feature.forum.domain.Verdict
import com.vovoscan.feature.forum.domain.VerdictState
import kotlinx.serialization.Serializable

/** A point `{x, y}`, a frame `{x, y, w, h}` or a stroke `{path: [[x, y], …]}`, in fractions of the page. */
@Serializable
data class AnchorDto(
    val x: Double? = null,
    val y: Double? = null,
    val w: Double? = null,
    val h: Double? = null,
    val path: List<List<Double>>? = null,
) {
    fun toDomain(): Anchor? =
        when {
            path != null -> Anchor.Stroke(path.filter { it.size == 2 }.map { it[0] to it[1] }).takeIf { it.points.size >= 2 }
            x != null && y != null && w != null && h != null -> Anchor.Frame(x, y, w, h)
            x != null && y != null -> Anchor.Point(x, y)
            else -> null
        }

    companion object {
        fun of(anchor: Anchor): AnchorDto =
            when (anchor) {
                is Anchor.Point -> AnchorDto(x = anchor.x, y = anchor.y)
                is Anchor.Frame -> AnchorDto(x = anchor.x, y = anchor.y, w = anchor.w, h = anchor.h)
                is Anchor.Stroke -> AnchorDto(path = anchor.points.map { (x, y) -> listOf(x, y) })
            }
    }
}

@Serializable
data class AuthorDto(
    val id: String,
    val name: String = "",
)

@Serializable
data class VerdictDto(
    val state: String = "open",
    val confirmed: Int? = null,
    val debunked: Int? = null,
    val mine: String? = null,
    val canVote: Boolean = false,
)

@Serializable
data class PostDto(
    val id: String,
    val kind: String = "note",
    val parentId: String? = null,
    val chapterNumber: Double? = null,
    val pageIndex: Int? = null,
    val anchor: AnchorDto? = null,
    val sticker: String? = null,
    val body: String? = null,
    val language: String = "",
    val spoilerUntil: Double? = null,
    val salonWeek: String? = null,
    val badge: String? = null,
    val status: String = "visible",
    val score: Int = 0,
    val replyCount: Int = 0,
    val createdAt: String? = null,
    val editedAt: String? = null,
    val author: AuthorDto,
    val masked: Boolean = false,
    val reactions: Map<String, Int> = emptyMap(),
    val myReaction: String? = null,
    val verdict: VerdictDto? = null,
    val mine: Boolean = false,
    val editable: Boolean = false,
) {
    fun toDomain() =
        Post(
            id = id,
            kind = PostKind.of(kind),
            parentId = parentId,
            chapterNumber = chapterNumber,
            pageIndex = pageIndex,
            anchor = anchor?.toDomain(),
            sticker = Sticker.of(sticker),
            body = body,
            language = language,
            spoilerUntil = spoilerUntil,
            salonWeek = salonWeek,
            badge = badge,
            status = PostStatus.of(status),
            score = score,
            replyCount = replyCount,
            createdAt = createdAt?.let(::parseIsoMillis),
            editedAt = editedAt?.let(::parseIsoMillis),
            author = Author(author.id, author.name),
            masked = masked,
            reactions = reactions.mapNotNull { (key, n) -> Sticker.of(key)?.let { it to n } }.toMap(),
            myReaction = Sticker.of(myReaction),
            verdict =
                verdict?.let { v ->
                    Verdict(
                        state = VerdictState.entries.firstOrNull { it.name.equals(v.state, ignoreCase = true) } ?: VerdictState.Open,
                        confirmed = v.confirmed,
                        debunked = v.debunked,
                        mine =
                            when (v.mine) {
                                "confirmed" -> true
                                "debunked" -> false
                                else -> null
                            },
                        canVote = v.canVote,
                    )
                },
            mine = mine,
            editable = editable,
        )
}

@Serializable
data class RoomDto(
    val id: String,
)

@Serializable
data class MarginDto(
    val room: RoomDto,
    val chapterNumber: Double? = null,
    val reach: Double? = null,
    val readersHere: Int? = null,
    val pins: List<PostDto> = emptyList(),
    val notes: List<PostDto> = emptyList(),
    val theories: List<PostDto> = emptyList(),
) {
    fun toDomain() =
        Margin(
            roomId = room.id,
            chapterNumber = chapterNumber,
            reach = reach,
            readersHere = readersHere,
            pins = pins.map { it.toDomain() },
            notes = notes.map { it.toDomain() },
            theories = theories.map { it.toDomain() },
        )
}

@Serializable
data class SalonWeekDto(
    val week: String,
    val count: Int = 0,
)

@Serializable
data class SalonDto(
    val room: RoomDto,
    val week: String,
    val current: Boolean = false,
    val weeks: List<SalonWeekDto> = emptyList(),
    val reach: Double? = null,
    val posts: List<PostDto> = emptyList(),
) {
    fun toDomain() = Salon(room.id, week, current, weeks.map { SalonWeek(it.week, it.count) }, reach, posts.map { it.toDomain() })
}

@Serializable
data class TheoriesDto(
    val room: RoomDto,
    val reach: Double? = null,
    val posts: List<PostDto> = emptyList(),
) {
    fun toDomain() = Theories(room.id, reach, posts.map { it.toDomain() })
}

@Serializable
data class RepliesDto(
    val post: PostDto,
    val replies: List<PostDto> = emptyList(),
) {
    fun toDomain() = Replies(post.toDomain(), replies.map { it.toDomain() })
}

@Serializable
data class TranslationDto(
    val id: String,
    val postId: String,
    val language: String,
    val status: String = "queued",
    val text: String? = null,
    val sourceLanguage: String? = null,
    val error: String? = null,
) {
    fun toDomain() =
        PostTranslation(
            id = id,
            postId = postId,
            language = language,
            status = TranslationStatus.entries.firstOrNull { it.name.equals(status, ignoreCase = true) } ?: TranslationStatus.Queued,
            text = text,
            sourceLanguage = sourceLanguage,
            error = error,
        )
}

@Serializable
data class MuteDto(
    val userId: String,
    val name: String = "",
) {
    fun toDomain() = MutedReader(userId, name)
}

@Serializable
data class PostBody(
    val kind: String,
    val language: String,
    val body: String? = null,
    val sticker: String? = null,
    val pageIndex: Int? = null,
    val anchor: AnchorDto? = null,
    val spoiler: Boolean? = null,
)

@Serializable
data class ReplyBody(
    val language: String,
    val body: String,
    val spoiler: Boolean? = null,
)

@Serializable
data class EditBody(
    val body: String,
)

@Serializable
data class ReactionBody(
    val key: String,
)

@Serializable
data class VoteBody(
    val verdict: String,
)

@Serializable
data class ReportBody(
    val reason: String,
    val note: String? = null,
)

@Serializable
data class TranslateBody(
    val language: String,
)
