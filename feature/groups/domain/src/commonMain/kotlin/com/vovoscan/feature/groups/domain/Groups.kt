package com.vovoscan.feature.groups.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/*
 * Groups (backend `features/groups`, contract docs/GROUPS.md): any account makes as many as it likes, people find one
 * by name or share name, or open it from a link or a QR code, and talk in its live chat. Games are played inside a
 * group's chat (docs/GAMES.md, [Game]). Behind the `forum` flag, like the margin. Online first: the server holds every
 * message; the device keeps the last list and the last messages of each group it opened, for display offline.
 */

/** Groups show only with the `forum` flag (wired by the app to the session). */
fun interface GroupsAccess {
    fun enabled(): Flow<Boolean>

    companion object {
        val Off = GroupsAccess { flowOf(false) }
    }
}

/** Who is signed in (their own messages can be edited and deleted); wired by the app to the session. */
fun interface GroupsUser {
    fun id(): String?

    companion object {
        val None = GroupsUser { null }
    }
}

enum class Entry(
    val wire: String,
) {
    /** Anyone joins at once. */
    Open("open"),

    /** An admin accepts each request. */
    Approval("approval"),
    ;

    companion object {
        fun of(wire: String?): Entry = entries.firstOrNull { it.wire == wire } ?: Open
    }
}

enum class Role(
    val wire: String,
    /** Higher acts on lower (an admin on members, the owner on everyone). */
    val rank: Int,
) {
    Owner("owner", 3),
    Admin("admin", 2),
    Member("member", 1),
    ;

    companion object {
        fun of(wire: String?): Role = entries.firstOrNull { it.wire == wire } ?: Member
    }
}

enum class MemberState(
    val wire: String,
) {
    Member("member"),
    Requested("requested"),
    Banned("banned"),
    ;

    companion object {
        fun of(wire: String?): MemberState = entries.firstOrNull { it.wire == wire } ?: Member
    }
}

enum class GroupStatus { Active, Suspended, Deleted }

/** The caller's place in a group. */
data class Me(
    val role: Role,
    val state: MemberState,
    /** Notifications from this group. */
    val notify: Boolean,
    val joinedAtMillis: Long,
    val lastReadAtMillis: Long?,
) {
    val isMember: Boolean get() = state == MemberState.Member
    val isAdmin: Boolean get() = isMember && role.rank >= Role.Admin.rank
    val isOwner: Boolean get() = isMember && role == Role.Owner
}

data class Group(
    val id: String,
    val name: String,
    /** The share name, unique: `/g/<handle>`. */
    val handle: String,
    val sharePath: String,
    val about: String,
    val entry: Entry,
    val listed: Boolean,
    /** 0..7: the group's colour in the brand palette. */
    val accent: Int,
    val memberCount: Int,
    /** The blue badge: staff checked the group and the people in it are real. */
    val verified: Boolean,
    val status: GroupStatus,
    val createdAtMillis: Long,
    val activeAtMillis: Long,
    /** Null: the caller is not in it (nor asked). */
    val me: Me?,
    /** Requests waiting (admins only, else 0). */
    val pending: Int,
    /** Unread messages, capped at 99 by the server. */
    val unread: Int,
    val last: Message?,
    /** The latest badge request's status (admins), if any: `pending`, `approved`, `denied`… */
    val badgeRequest: String?,
)

/** The margin's eight stickers (the forum contract): stable keys on the wire, Latin onomatopoeia on screen. */
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

enum class MessageKind(
    val wire: String,
) {
    Text("text"),
    Sticker("sticker"),

    /** Written by the server (`event`); clients phrase it in their own language. */
    System("system"),

    /** A game's card in the chat (docs/GAMES.md). */
    Game("game"),
    ;

    companion object {
        fun of(wire: String?): MessageKind = entries.firstOrNull { it.wire == wire } ?: Text
    }
}

enum class MessageStatus(
    val wire: String,
) {
    Visible("visible"),

    /** Three members reported it: only its author and the admins see it until an admin decides. */
    Held("held"),

    /** Taken back by its author: its place stays, with no content. */
    Deleted("deleted"),

    /** Removed by an admin. */
    Removed("removed"),
    ;

    companion object {
        fun of(wire: String?): MessageStatus = entries.firstOrNull { it.wire == wire } ?: Visible
    }
}

/** A system line's event, as the server wrote it. */
data class SystemEvent(
    /** `created`, `joined`, `left`, `removed`, `role`, `renamed`, `verified`, `game`. */
    val event: String,
    val name: String? = null,
    val userId: String? = null,
    val role: String? = null,
    val game: GameKind? = null,
    val gameId: String? = null,
    val ending: String? = null,
    val winners: List<String> = emptyList(),
)

/** A game's card: which game, and where its live state is ([Live.games]). */
data class GameCard(
    val gameId: String,
    val game: GameKind,
    val seats: Int,
    val ranked: Boolean,
)

data class Quote(
    val id: String,
    val authorName: String?,
    val excerpt: String,
)

data class Reaction(
    val sticker: Sticker,
    val count: Int,
)

data class Message(
    val id: String,
    val groupId: String,
    val authorId: String?,
    val authorName: String?,
    val kind: MessageKind,
    /** Empty for a held, deleted or removed message. */
    val body: String,
    val sticker: Sticker?,
    val system: SystemEvent? = null,
    val card: GameCard? = null,
    val replyTo: Quote? = null,
    val status: MessageStatus,
    val reactions: List<Reaction> = emptyList(),
    /** The caller's own reaction. */
    val mine: Sticker? = null,
    val createdAtMillis: Long,
    val editedAtMillis: Long? = null,
)

data class Member(
    val userId: String,
    val name: String,
    val role: Role,
    val state: MemberState,
    /** A request's note (admins). */
    val note: String,
    val joinedAtMillis: Long,
)

data class MemberPage(
    val members: List<Member>,
    val total: Int,
    val hasMore: Boolean,
)

/** What a group's stream sends: the group as the member sees it, the latest messages (oldest first), its live games. */
data class Live(
    val group: Group,
    val messages: List<Message>,
    val games: List<Game>,
)

enum class ReportReason(
    val wire: String,
) {
    Spam("spam"),
    Harassment("harassment"),
    Spoiler("spoiler"),
    Nsfw("nsfw"),
    Bot("bot"),
    Other("other"),
}

/** A reported message (admins). */
data class ReportedMessage(
    val message: Message,
    val reason: String,
    val reports: Int,
)

/**
 * One check of the blue badge's list (`groupAgeDays`, `members`, `realMemberShare`…): its value against what it needs
 * (a minimum, or a maximum for `upheldReports90d` and `suspectShare`; `ownerVerified` is 1 or 0).
 */
data class BadgeCheck(
    val key: String,
    val value: Double,
    val need: Double,
    val passed: Boolean,
)

data class BadgeRequest(
    val id: String,
    val status: String,
    val note: String,
    val createdAtMillis: Long,
)

data class BadgeStatus(
    val checks: List<BadgeCheck>,
    val latest: BadgeRequest?,
) {
    val eligible: Boolean get() = checks.isNotEmpty() && checks.all { it.passed }
}

/** A new group, or the fields changed on one (null: unchanged). */
data class GroupDraft(
    val name: String? = null,
    val handle: String? = null,
    val about: String? = null,
    val entry: Entry? = null,
    val listed: Boolean? = null,
    val accent: Int? = null,
)

/** `api/v1/groups`: what the apps use (the staff's tools live in the web console). */
interface GroupsRepository {
    /** The last list of the caller's groups this device saw (offline display). */
    fun observeMine(): Flow<List<Group>?>

    suspend fun mine(): AppResult<List<Group>>

    /** Listed groups by name or share name; [query] under 2 characters = Discover. */
    suspend fun search(
        query: String,
        page: Int,
    ): AppResult<List<Group>>

    suspend fun byHandle(handle: String): AppResult<Group>

    suspend fun group(id: String): AppResult<Group>

    suspend fun create(draft: GroupDraft): AppResult<Group>

    suspend fun update(
        id: String,
        draft: GroupDraft,
    ): AppResult<Group>

    suspend fun delete(id: String): AppResult<Unit>

    suspend fun join(
        id: String,
        note: String?,
    ): AppResult<Group>

    suspend fun leave(id: String): AppResult<Unit>

    suspend fun notify(
        id: String,
        on: Boolean,
    ): AppResult<Unit>

    suspend fun markRead(id: String): AppResult<Unit>

    suspend fun members(
        id: String,
        state: MemberState,
        search: String?,
        page: Int,
    ): AppResult<MemberPage>

    suspend fun decide(
        id: String,
        userId: String,
        approve: Boolean,
    ): AppResult<Unit>

    suspend fun setRole(
        id: String,
        userId: String,
        role: Role,
    ): AppResult<Unit>

    suspend fun remove(
        id: String,
        userId: String,
        ban: Boolean,
    ): AppResult<Unit>

    suspend fun unban(
        id: String,
        userId: String,
    ): AppResult<Unit>

    /** The last live snapshot this device saw of a group (offline display). */
    suspend fun cachedLive(id: String): Live?

    /** Older than [before] (scrolling up) or newer than [after] (catching up), oldest first, 50 at most. */
    suspend fun messages(
        id: String,
        before: String? = null,
        after: String? = null,
    ): AppResult<List<Message>>

    suspend fun send(
        id: String,
        text: String?,
        sticker: Sticker?,
        replyToId: String?,
    ): AppResult<Message>

    suspend fun edit(
        messageId: String,
        body: String,
    ): AppResult<Message>

    suspend fun deleteMessage(messageId: String): AppResult<Unit>

    suspend fun restore(messageId: String): AppResult<Unit>

    suspend fun react(
        messageId: String,
        sticker: Sticker?,
    ): AppResult<Unit>

    suspend fun report(
        messageId: String,
        reason: ReportReason,
    ): AppResult<Unit>

    suspend fun reports(id: String): AppResult<List<ReportedMessage>>

    suspend fun badge(id: String): AppResult<BadgeStatus>

    suspend fun askBadge(
        id: String,
        reason: String,
        links: List<String>,
    ): AppResult<Unit>

    suspend fun withdrawBadge(id: String): AppResult<Unit>

    /**
     * Keeps the group's event stream open: [onLive] for every snapshot until it answers false. Reconnects at once when
     * the server ends the stream, backs off on failures, polls after 3 of them; returns a final error (no longer a
     * member, gone, flag off).
     */
    suspend fun live(
        id: String,
        onLive: suspend (Live) -> Boolean,
    ): AppResult<Unit>

    // Games (docs/GAMES.md).

    suspend fun startGame(
        groupId: String,
        game: GameKind,
        seats: Int,
        ranked: Boolean,
        invite: List<String>,
        inviteOnly: Boolean,
    ): AppResult<Game>

    suspend fun game(id: String): AppResult<Game>

    suspend fun joinGame(id: String): AppResult<Game>

    suspend fun leaveGame(id: String): AppResult<Game>

    suspend fun move(
        id: String,
        move: Move,
    ): AppResult<Game>

    suspend fun games(
        groupId: String,
        live: Boolean,
        page: Int,
    ): AppResult<List<Game>>

    suspend fun leaderboard(
        groupId: String,
        game: GameKind,
        page: Int,
    ): AppResult<List<Standing>>

    /** A game's own stream, as [live]. */
    suspend fun gameLive(
        id: String,
        onGame: suspend (Game) -> Boolean,
    ): AppResult<Unit>

    suspend fun clearLocal()
}
