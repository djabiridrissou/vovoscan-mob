package com.vovoscan.feature.groups.data

import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.feature.groups.domain.BadgeCheck
import com.vovoscan.feature.groups.domain.BadgeRequest
import com.vovoscan.feature.groups.domain.BadgeStatus
import com.vovoscan.feature.groups.domain.ClashPick
import com.vovoscan.feature.groups.domain.ClashRound
import com.vovoscan.feature.groups.domain.Edge
import com.vovoscan.feature.groups.domain.Ending
import com.vovoscan.feature.groups.domain.Entry
import com.vovoscan.feature.groups.domain.Game
import com.vovoscan.feature.groups.domain.GameCard
import com.vovoscan.feature.groups.domain.GameKind
import com.vovoscan.feature.groups.domain.GameState
import com.vovoscan.feature.groups.domain.GameStatus
import com.vovoscan.feature.groups.domain.Group
import com.vovoscan.feature.groups.domain.GroupStatus
import com.vovoscan.feature.groups.domain.Live
import com.vovoscan.feature.groups.domain.Me
import com.vovoscan.feature.groups.domain.Member
import com.vovoscan.feature.groups.domain.MemberState
import com.vovoscan.feature.groups.domain.Message
import com.vovoscan.feature.groups.domain.MessageKind
import com.vovoscan.feature.groups.domain.MessageStatus
import com.vovoscan.feature.groups.domain.Move
import com.vovoscan.feature.groups.domain.PlayedMove
import com.vovoscan.feature.groups.domain.Player
import com.vovoscan.feature.groups.domain.Quote
import com.vovoscan.feature.groups.domain.Reaction
import com.vovoscan.feature.groups.domain.ReportedMessage
import com.vovoscan.feature.groups.domain.Role
import com.vovoscan.feature.groups.domain.SeatPick
import com.vovoscan.feature.groups.domain.Standing
import com.vovoscan.feature.groups.domain.Sticker
import com.vovoscan.feature.groups.domain.SystemEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// Wire shapes of docs/GROUPS.md and docs/GAMES.md (camelCase, `{data}`), read leniently: an unknown kind, status or
// game falls back to something the app can show, never a crash.

@Serializable
data class MeDto(
    val role: String = "member",
    val state: String = "member",
    val notify: Boolean = true,
    val joinedAt: String = "",
    val lastReadAt: String? = null,
) {
    fun toDomain() = Me(Role.of(role), MemberState.of(state), notify, parseIsoMillis(joinedAt) ?: 0, lastReadAt?.let(::parseIsoMillis))
}

@Serializable
data class AuthorDto(
    val id: String,
    val name: String = "",
)

@Serializable
data class QuoteDto(
    val id: String,
    val authorName: String? = null,
    val excerpt: String = "",
)

@Serializable
data class ReactionDto(
    val key: String,
    val count: Int,
)

@Serializable
data class MessageDto(
    val id: String,
    val groupId: String,
    val author: AuthorDto? = null,
    val kind: String = "text",
    val body: String = "",
    val sticker: String? = null,
    val payload: JsonObject? = null,
    val replyTo: QuoteDto? = null,
    val status: String = "visible",
    val reactions: List<ReactionDto> = emptyList(),
    val mine: String? = null,
    val createdAt: String,
    val editedAt: String? = null,
) {
    fun toDomain(): Message {
        val kind = MessageKind.of(kind)
        return Message(
            id = id,
            groupId = groupId,
            authorId = author?.id,
            authorName = author?.name,
            kind = kind,
            body = body,
            sticker = Sticker.of(sticker),
            system = payload?.takeIf { kind == MessageKind.System }?.let(::systemEvent),
            card = payload?.takeIf { kind == MessageKind.Game }?.let(::gameCard),
            replyTo = replyTo?.let { Quote(it.id, it.authorName, it.excerpt) },
            status = MessageStatus.of(status),
            reactions = reactions.mapNotNull { r -> Sticker.of(r.key)?.let { Reaction(it, r.count) } },
            mine = Sticker.of(mine),
            createdAtMillis = parseIsoMillis(createdAt) ?: 0,
            editedAtMillis = editedAt?.let(::parseIsoMillis),
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun systemEvent(payload: JsonObject) =
    SystemEvent(
        event = payload.text("event").orEmpty(),
        name = payload.text("name"),
        userId = payload.text("userId"),
        role = payload.text("role"),
        game = GameKind.of(payload.text("game")),
        gameId = payload.text("gameId"),
        ending = payload.text("ending"),
        winners = (payload["winners"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
    )

private fun gameCard(payload: JsonObject): GameCard? {
    val id = payload.text("gameId") ?: return null
    val game = GameKind.of(payload.text("game")) ?: return null
    return GameCard(
        id,
        game,
        (payload["seats"] as? JsonPrimitive)?.intOrNull ?: game.seats.first(),
        (payload["ranked"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val handle: String,
    val sharePath: String = "",
    val about: String = "",
    val entry: String = "open",
    val listed: Boolean = true,
    val accent: Int = 0,
    val memberCount: Int = 0,
    val verified: Boolean = false,
    val status: String = "active",
    val createdAt: String = "",
    val activeAt: String = "",
    val me: MeDto? = null,
    val pending: Int = 0,
    val unread: Int = 0,
    val last: MessageDto? = null,
    val badgeRequest: String? = null,
) {
    fun toDomain() =
        Group(
            id = id,
            name = name,
            handle = handle,
            sharePath = sharePath.ifEmpty { "/g/$handle" },
            about = about,
            entry = Entry.of(entry),
            listed = listed,
            accent = accent.mod(8),
            memberCount = memberCount,
            verified = verified,
            status =
                when (status) {
                    "suspended" -> GroupStatus.Suspended
                    "deleted" -> GroupStatus.Deleted
                    else -> GroupStatus.Active
                },
            createdAtMillis = parseIsoMillis(createdAt) ?: 0,
            activeAtMillis = parseIsoMillis(activeAt) ?: 0,
            me = me?.toDomain(),
            pending = pending,
            unread = unread,
            last = last?.toDomain(),
            badgeRequest = badgeRequest,
        )
}

@Serializable
data class PlayerDto(
    val userId: String,
    val name: String = "",
    val seat: Int,
    val rank: Int? = null,
    val ratingBefore: Double? = null,
    val ratingAfter: Double? = null,
)

@Serializable
data class GameDto(
    val id: String,
    val groupId: String,
    val game: String,
    val seats: Int = 2,
    val ranked: Boolean = false,
    val status: String = "open",
    val ending: String? = null,
    val state: JsonObject = JsonObject(emptyMap()),
    val players: List<PlayerDto> = emptyList(),
    val turn: Int? = null,
    val waiting: List<Int> = emptyList(),
    val turnDeadline: String? = null,
    val mySeat: Int? = null,
    val invited: List<String> = emptyList(),
    val inviteOnly: Boolean = false,
    val createdById: String? = null,
    val messageId: String? = null,
    val moveCount: Int = 0,
    val moves: List<JsonObject> = emptyList(),
    val createdAt: String = "",
    val endedAt: String? = null,
) {
    fun toDomain(): Game {
        val kind = GameKind.of(game)
        return Game(
            id = id,
            groupId = groupId,
            kind = kind,
            seats = seats,
            ranked = ranked,
            status = GameStatus.of(status),
            ending = Ending.of(ending),
            state = runCatching { stateOf(kind, state) }.getOrDefault(GameState.Unknown),
            players = players.map { Player(it.userId, it.name, it.seat, it.rank, it.ratingBefore, it.ratingAfter) }.sortedBy { it.seat },
            turn = turn,
            waiting = waiting,
            turnDeadlineMillis = turnDeadline?.let(::parseIsoMillis),
            mySeat = mySeat,
            invited = invited,
            inviteOnly = inviteOnly,
            createdById = createdById,
            messageId = messageId,
            moveCount = moveCount,
            moves =
                moves.map { m ->
                    PlayedMove(
                        seat = (m["seat"] as? JsonPrimitive)?.intOrNull ?: 0,
                        move = kind?.let { k -> m["move"]?.let { moveOf(k, it) } },
                        atMillis = (m["at"] as? JsonPrimitive)?.content?.let(::parseIsoMillis) ?: 0,
                    )
                },
            createdAtMillis = parseIsoMillis(createdAt) ?: 0,
            endedAtMillis = endedAt?.let(::parseIsoMillis),
        )
    }
}

private fun JsonElement.ints(): List<Int> = jsonArray.map { it.jsonPrimitive.intOrNull ?: -1 }

private fun JsonElement.grid(): List<List<Int>> = jsonArray.map { it.ints() }

private fun JsonElement?.cell(): Pair<Int, Int>? =
    (this as? JsonArray)?.takeIf { it.size == 2 }?.let { Pair(it[0].jsonPrimitive.intOrNull ?: 0, it[1].jsonPrimitive.intOrNull ?: 0) }

private fun stateOf(
    kind: GameKind?,
    s: JsonObject,
): GameState =
    when (kind) {
        null -> GameState.Unknown
        GameKind.Clash ->
            GameState.Clash(
                round = s["round"]?.jsonPrimitive?.intOrNull ?: 1,
                scores = s["scores"]?.ints() ?: listOf(0, 0),
                picks =
                    (s["picks"] as? JsonArray).orEmpty().map { p ->
                        when {
                            p is JsonNull -> SeatPick.None
                            p.jsonPrimitive.content == "picked" -> SeatPick.Hidden
                            else -> ClashPick.of(p.jsonPrimitive.content)?.let(SeatPick::Known) ?: SeatPick.Hidden
                        }
                    },
                rounds =
                    (s["rounds"] as? JsonArray).orEmpty().map { r ->
                        val round = r as JsonObject
                        ClashRound(
                            picks = (round["picks"] as? JsonArray).orEmpty().mapNotNull { ClashPick.of(it.jsonPrimitive.content) },
                            winner = (round["winner"] as? JsonPrimitive)?.intOrNull,
                        )
                    },
            )
        GameKind.Four ->
            GameState.Four(
                board = s.getValue("board").grid(),
                turn = s["turn"]?.jsonPrimitive?.intOrNull ?: 0,
                last = s["last"].cell(),
                line = (s["line"] as? JsonArray).orEmpty().mapNotNull { it.cell() },
            )
        GameKind.Panels -> {
            val last = s["last"] as? JsonArray
            GameState.Panels(
                size = s["size"]?.jsonPrimitive?.intOrNull ?: 4,
                h = s.getValue("h").grid(),
                v = s.getValue("v").grid(),
                boxes = s.getValue("boxes").grid(),
                scores = s["scores"]?.ints().orEmpty(),
                turn = s["turn"]?.jsonPrimitive?.intOrNull ?: 0,
                seats = s["seats"]?.jsonPrimitive?.intOrNull ?: 2,
                last = last?.let { edgeOf(it) },
            )
        }
    }

private fun edgeOf(a: JsonArray): Edge? {
    if (a.size != 3) return null
    val kind = a[0].jsonPrimitive.content
    val row = a[1].jsonPrimitive.intOrNull ?: return null
    val col = a[2].jsonPrimitive.intOrNull ?: return null
    return if (kind == "h" || kind == "v") Edge(kind == "h", row, col) else null
}

private fun moveOf(
    kind: GameKind,
    m: JsonElement,
): Move? =
    when (kind) {
        GameKind.Clash -> (m as? JsonPrimitive)?.content?.let(ClashPick::of)?.let(Move::Pick)
        GameKind.Four -> (m as? JsonPrimitive)?.intOrNull?.let(Move::Drop)
        GameKind.Panels -> (m as? JsonArray)?.let(::edgeOf)?.let(Move::Draw)
    }

/** A move as `POST …/move {move}` takes it. */
fun Move.wire(): JsonElement =
    when (this) {
        is Move.Pick -> JsonPrimitive(pick.wire)
        is Move.Drop -> JsonPrimitive(col)
        is Move.Draw ->
            JsonArray(
                listOf(JsonPrimitive(if (edge.horizontal) "h" else "v"), JsonPrimitive(edge.row), JsonPrimitive(edge.col)),
            )
    }

@Serializable
data class LiveDto(
    val group: GroupDto,
    val messages: List<MessageDto> = emptyList(),
    val games: List<GameDto> = emptyList(),
) {
    fun toDomain() = Live(group.toDomain(), messages.map { it.toDomain() }, games.map { it.toDomain() })
}

@Serializable
data class MemberDto(
    val userId: String,
    val name: String = "",
    val role: String = "member",
    val state: String = "member",
    val note: String = "",
    val joinedAt: String = "",
) {
    fun toDomain() = Member(userId, name, Role.of(role), MemberState.of(state), note, parseIsoMillis(joinedAt) ?: 0)
}

@Serializable
data class StandingDto(
    val userId: String,
    val name: String = "",
    val rating: Double = 1200.0,
    val played: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
) {
    fun toDomain() = Standing(userId, name, rating, played, wins, losses, draws)
}

@Serializable
data class ReportDto(
    val message: MessageDto,
    val groupId: String,
    val groupName: String = "",
    val reason: String = "other",
    val reports: Int = 1,
    val createdAt: String = "",
) {
    fun toDomain() = ReportedMessage(message.toDomain(), reason, reports)
}

@Serializable
data class VerificationDto(
    val id: String,
    val status: String,
    val note: String = "",
    val createdAt: String = "",
)

@Serializable
data class EligibilityDto(
    val checks: JsonObject = JsonObject(emptyMap()),
    val latest: VerificationDto? = null,
) {
    fun toDomain(): BadgeStatus {
        val items = (checks["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return BadgeStatus(
            checks =
                items.map { item ->
                    BadgeCheck(
                        key = item.text("key").orEmpty(),
                        value = item["value"].number(),
                        need = item["need"].number(),
                        passed = (item["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                },
            latest = latest?.let { BadgeRequest(it.id, it.status, it.note, parseIsoMillis(it.createdAt) ?: 0) },
        )
    }
}

/** A number, or 1 / 0 for a yes / no check. */
private fun JsonElement?.number(): Double {
    val p = this as? JsonPrimitive ?: return 0.0
    return p.doubleOrNull ?: p.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: 0.0
}

@Serializable
data class GroupBody(
    val name: String? = null,
    val handle: String? = null,
    val about: String? = null,
    val entry: String? = null,
    val listed: Boolean? = null,
    val accent: Int? = null,
)

@Serializable
data class JoinBody(
    val note: String = "",
)

@Serializable
data class NotifyBody(
    val notify: Boolean,
)

@Serializable
data class DecideBody(
    val approve: Boolean,
)

@Serializable
data class RoleBody(
    val role: String,
)

@Serializable
data class RemoveBody(
    val ban: Boolean,
)

@Serializable
data class MessageBody(
    val kind: String,
    val body: String? = null,
    val sticker: String? = null,
    val replyToId: String? = null,
)

@Serializable
data class EditBody(
    val body: String,
)

@Serializable
data class ReportBody(
    val reason: String,
)

@Serializable
data class BadgeBody(
    val reason: String,
    val links: List<String>,
)

@Serializable
data class GameBody(
    val game: String,
    val seats: Int,
    val ranked: Boolean,
    val invite: List<String>,
    val inviteOnly: Boolean,
)

@Serializable
data class MoveBody(
    val move: JsonElement,
)
