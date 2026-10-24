package com.vovoscan.feature.groups.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.ApiJson
import com.vovoscan.core.network.ApiPage
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.network.apiCallUnit
import com.vovoscan.core.network.followSnapshots
import com.vovoscan.feature.groups.data.db.GroupsDatabase
import com.vovoscan.feature.groups.domain.BadgeStatus
import com.vovoscan.feature.groups.domain.Game
import com.vovoscan.feature.groups.domain.GameKind
import com.vovoscan.feature.groups.domain.Group
import com.vovoscan.feature.groups.domain.GroupDraft
import com.vovoscan.feature.groups.domain.GroupsRepository
import com.vovoscan.feature.groups.domain.Live
import com.vovoscan.feature.groups.domain.MemberPage
import com.vovoscan.feature.groups.domain.MemberState
import com.vovoscan.feature.groups.domain.Message
import com.vovoscan.feature.groups.domain.Move
import com.vovoscan.feature.groups.domain.ReportReason
import com.vovoscan.feature.groups.domain.ReportedMessage
import com.vovoscan.feature.groups.domain.Role
import com.vovoscan.feature.groups.domain.Standing
import com.vovoscan.feature.groups.domain.Sticker
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `api/v1/groups`: everything a member does in the apps. Online first: the server holds every group and message; the
 * last list and the last snapshot of each opened group are kept for display offline ([observeMine], [cachedLive]).
 */
class GroupsRepositoryImpl(
    private val http: HttpClient,
    private val database: GroupsDatabase,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
) : GroupsRepository {
    private val queries get() = database.groupsQueries

    private fun groupPath(id: String) = "$BASE/${id.encodeURLPathPart()}"

    private fun messagePath(id: String) = "$BASE/messages/${id.encodeURLPathPart()}"

    private fun gamePath(id: String) = "$BASE/games/${id.encodeURLPathPart()}"

    override fun observeMine(): Flow<List<Group>?> =
        queries
            .cached(MINE)
            .asFlow()
            .mapToOneOrNull(io)
            .map { body -> body?.let { runCatching { ApiJson.decodeFromString(GROUPS, it).map { g -> g.toDomain() } }.getOrNull() } }

    override suspend fun mine(): AppResult<List<Group>> =
        when (val result = http.apiCall<ApiData<List<GroupDto>>> { get(BASE) { parameter("perPage", 100) } }) {
            is AppResult.Err -> result
            is AppResult.Ok -> {
                save(MINE, ApiJson.encodeToString(GROUPS, result.value.data))
                AppResult.Ok(result.value.data.map { it.toDomain() })
            }
        }

    override suspend fun search(
        query: String,
        page: Int,
    ): AppResult<List<Group>> =
        http
            .apiCall<ApiData<List<GroupDto>>> {
                get("$BASE/search") {
                    parameter("q", query.trim())
                    parameter("page", page)
                    parameter("perPage", SEARCH_PAGE)
                }
            }.map { list -> list.data.map { it.toDomain() } }

    override suspend fun byHandle(handle: String): AppResult<Group> =
        http.apiCall<ApiData<GroupDto>> { get("$BASE/by-handle/${handle.encodeURLPathPart()}") }.map { it.data.toDomain() }

    override suspend fun group(id: String): AppResult<Group> =
        http.apiCall<ApiData<GroupDto>> { get(groupPath(id)) }.map { it.data.toDomain() }

    override suspend fun create(draft: GroupDraft): AppResult<Group> =
        http.apiCall<ApiData<GroupDto>> { post(BASE) { json(draft.body()) } }.map { it.data.toDomain() }

    override suspend fun update(
        id: String,
        draft: GroupDraft,
    ): AppResult<Group> = http.apiCall<ApiData<GroupDto>> { patch(groupPath(id)) { json(draft.body()) } }.map { it.data.toDomain() }

    override suspend fun delete(id: String): AppResult<Unit> =
        http
            .apiCallUnit {
                delete(groupPath(id))
            }.also { if (it is AppResult.Ok) forget(id) }

    override suspend fun join(
        id: String,
        note: String?,
    ): AppResult<Group> =
        http.apiCall<ApiData<GroupDto>> { post("${groupPath(id)}/join") { json(JoinBody(note.orEmpty())) } }.map { it.data.toDomain() }

    override suspend fun leave(id: String): AppResult<Unit> =
        http.apiCallUnit { delete("${groupPath(id)}/me") }.also { if (it is AppResult.Ok) forget(id) }

    override suspend fun notify(
        id: String,
        on: Boolean,
    ): AppResult<Unit> = http.apiCallUnit { put("${groupPath(id)}/me/notify") { json(NotifyBody(on)) } }

    override suspend fun markRead(id: String): AppResult<Unit> = http.apiCallUnit { post("${groupPath(id)}/read") }

    override suspend fun members(
        id: String,
        state: MemberState,
        search: String?,
        page: Int,
    ): AppResult<MemberPage> =
        http
            .apiCall<ApiPage<MemberDto>> {
                get("${groupPath(id)}/members") {
                    parameter("state", state.wire)
                    search?.takeIf { it.isNotBlank() }?.let { parameter("search", it.trim()) }
                    parameter("page", page)
                    parameter("perPage", MEMBER_PAGE)
                }
            }.map { MemberPage(it.data.map { m -> m.toDomain() }, it.total, it.hasNext) }

    override suspend fun decide(
        id: String,
        userId: String,
        approve: Boolean,
    ): AppResult<Unit> = http.apiCallUnit { post("${groupPath(id)}/requests/${userId.encodeURLPathPart()}") { json(DecideBody(approve)) } }

    override suspend fun setRole(
        id: String,
        userId: String,
        role: Role,
    ): AppResult<Unit> =
        http.apiCallUnit { put("${groupPath(id)}/members/${userId.encodeURLPathPart()}/role") { json(RoleBody(role.wire)) } }

    override suspend fun remove(
        id: String,
        userId: String,
        ban: Boolean,
    ): AppResult<Unit> =
        http.apiCallUnit { post("${groupPath(id)}/members/${userId.encodeURLPathPart()}/remove") { json(RemoveBody(ban)) } }

    override suspend fun unban(
        id: String,
        userId: String,
    ): AppResult<Unit> = http.apiCallUnit { delete("${groupPath(id)}/bans/${userId.encodeURLPathPart()}") }

    override suspend fun cachedLive(id: String): Live? =
        withContext(io) { queries.cached(live(id)).executeAsOneOrNull() }
            ?.let { runCatching { ApiJson.decodeFromString(LiveDto.serializer(), it).toDomain() }.getOrNull() }

    override suspend fun messages(
        id: String,
        before: String?,
        after: String?,
    ): AppResult<List<Message>> =
        http
            .apiCall<ApiData<List<MessageDto>>> {
                get("${groupPath(id)}/messages") {
                    before?.let { parameter("before", it) }
                    after?.let { parameter("after", it) }
                    parameter("limit", HISTORY_PAGE)
                }
            }.map { list -> list.data.map { it.toDomain() } }

    override suspend fun send(
        id: String,
        text: String?,
        sticker: Sticker?,
        replyToId: String?,
    ): AppResult<Message> =
        http
            .apiCall<ApiData<MessageDto>> {
                post("${groupPath(id)}/messages") {
                    json(
                        if (sticker != null) {
                            MessageBody(kind = "sticker", sticker = sticker.key, replyToId = replyToId)
                        } else {
                            MessageBody(kind = "text", body = text, replyToId = replyToId)
                        },
                    )
                }
            }.map { it.data.toDomain() }

    override suspend fun edit(
        messageId: String,
        body: String,
    ): AppResult<Message> =
        http
            .apiCall<ApiData<MessageDto>> {
                patch(messagePath(messageId)) { json(EditBody(body)) }
            }.map { it.data.toDomain() }

    override suspend fun deleteMessage(messageId: String): AppResult<Unit> = http.apiCallUnit { delete(messagePath(messageId)) }

    override suspend fun restore(messageId: String): AppResult<Unit> = http.apiCallUnit { post("${messagePath(messageId)}/restore") }

    override suspend fun react(
        messageId: String,
        sticker: Sticker?,
    ): AppResult<Unit> =
        http.apiCallUnit {
            // `{key: null}` takes the reaction back: the null must be sent (the client's JSON drops nulls).
            put("${messagePath(messageId)}/reaction") { json(JsonObject(mapOf("key" to (sticker?.key?.let(::JsonPrimitive) ?: JsonNull)))) }
        }

    override suspend fun report(
        messageId: String,
        reason: ReportReason,
    ): AppResult<Unit> = http.apiCallUnit { post("${messagePath(messageId)}/report") { json(ReportBody(reason.wire)) } }

    override suspend fun reports(id: String): AppResult<List<ReportedMessage>> =
        http.apiCall<ApiData<List<ReportDto>>> { get("${groupPath(id)}/reports") }.map { list -> list.data.map { it.toDomain() } }

    override suspend fun badge(id: String): AppResult<BadgeStatus> =
        http.apiCall<ApiData<EligibilityDto>> { get("${groupPath(id)}/badge") }.map { it.data.toDomain() }

    override suspend fun askBadge(
        id: String,
        reason: String,
        links: List<String>,
    ): AppResult<Unit> = http.apiCallUnit { post("${groupPath(id)}/badge") { json(BadgeBody(reason, links)) } }

    override suspend fun withdrawBadge(id: String): AppResult<Unit> = http.apiCallUnit { delete("${groupPath(id)}/badge") }

    override suspend fun live(
        id: String,
        onLive: suspend (Live) -> Boolean,
    ): AppResult<Unit> {
        var lastSaved = 0L
        return http.followSnapshots(
            path = "${groupPath(id)}/events",
            decode = { ApiJson.decodeFromString(LiveDto.serializer(), it) },
            poll = { pollLive(id) },
            isFinal = ::isFinal,
        ) { dto ->
            // The latest snapshot is what shows offline next time (written at most every few seconds).
            val now = clock.nowMillis()
            if (now - lastSaved >= SAVE_EVERY_MS) {
                lastSaved = now
                saveLive(id, dto)
            }
            onLive(dto.toDomain())
        }
    }

    /** No stream: the group and its latest messages and games, read as three requests. */
    private suspend fun pollLive(id: String): AppResult<LiveDto> {
        val group = http.apiCall<ApiData<GroupDto>> { get(groupPath(id)) }
        if (group is AppResult.Err) return group
        val messages = http.apiCall<ApiData<List<MessageDto>>> { get("${groupPath(id)}/messages") { parameter("limit", LIVE_MESSAGES) } }
        if (messages is AppResult.Err) return messages
        val games = http.apiCall<ApiPage<GameDto>> { get("${groupPath(id)}/games") { parameter("status", "live") } }
        return AppResult.Ok(
            LiveDto(
                (group as AppResult.Ok).value.data,
                (messages as AppResult.Ok).value.data,
                (games as? AppResult.Ok)?.value?.data.orEmpty(),
            ),
        )
    }

    override suspend fun startGame(
        groupId: String,
        game: GameKind,
        seats: Int,
        ranked: Boolean,
        invite: List<String>,
        inviteOnly: Boolean,
    ): AppResult<Game> =
        http
            .apiCall<ApiData<GameDto>> {
                post(
                    "${groupPath(groupId)}/games",
                ) { json(GameBody(game.wire, seats, ranked, invite, inviteOnly)) }
            }.map { it.data.toDomain() }

    override suspend fun game(id: String): AppResult<Game> = http.apiCall<ApiData<GameDto>> { get(gamePath(id)) }.map { it.data.toDomain() }

    override suspend fun joinGame(id: String): AppResult<Game> =
        http.apiCall<ApiData<GameDto>> { post("${gamePath(id)}/join") }.map { it.data.toDomain() }

    override suspend fun leaveGame(id: String): AppResult<Game> =
        http.apiCall<ApiData<GameDto>> { post("${gamePath(id)}/leave") }.map { it.data.toDomain() }

    override suspend fun move(
        id: String,
        move: Move,
    ): AppResult<Game> =
        http
            .apiCall<ApiData<GameDto>> {
                post("${gamePath(id)}/move") { json(MoveBody(move.wire())) }
            }.map { it.data.toDomain() }

    override suspend fun games(
        groupId: String,
        live: Boolean,
        page: Int,
    ): AppResult<List<Game>> =
        http
            .apiCall<ApiPage<GameDto>> {
                get("${groupPath(groupId)}/games") {
                    parameter("status", if (live) "live" else "over")
                    parameter("page", page)
                }
            }.map { list -> list.data.map { it.toDomain() } }

    override suspend fun leaderboard(
        groupId: String,
        game: GameKind,
        page: Int,
    ): AppResult<List<Standing>> =
        http
            .apiCall<ApiPage<StandingDto>> {
                get("${groupPath(groupId)}/games/leaderboard") {
                    parameter("game", game.wire)
                    parameter("page", page)
                }
            }.map { list -> list.data.map { it.toDomain() } }

    override suspend fun gameLive(
        id: String,
        onGame: suspend (Game) -> Boolean,
    ): AppResult<Unit> =
        http.followSnapshots(
            path = "${gamePath(id)}/events",
            decode = { ApiJson.decodeFromString(GameDto.serializer(), it).toDomain() },
            poll = { game(id) },
            isFinal = ::isFinal,
            pollMs = GAME_POLL_MS,
            onItem = onGame,
        )

    override suspend fun clearLocal() = withContext(io) { queries.clear() }

    private suspend fun save(
        key: String,
        body: String,
    ) = withContext(io) { queries.put(key, body, clock.nowMillis()) }

    private suspend fun saveLive(
        id: String,
        dto: LiveDto,
    ) = withContext(io) {
        queries.transaction {
            queries.put(live(id), ApiJson.encodeToString(LiveDto.serializer(), dto), clock.nowMillis())
            // The groups opened last: an older one is read again when it is opened.
            queries.trimLive(KEPT_LIVE)
        }
    }

    private suspend fun forget(id: String) = withContext(io) { queries.forget(live(id)) }

    private companion object {
        const val BASE = "api/v1/groups"
        const val MINE = "mine"
        const val SEARCH_PAGE = 20
        const val MEMBER_PAGE = 50
        const val HISTORY_PAGE = 50
        const val LIVE_MESSAGES = 40
        const val KEPT_LIVE = 30L
        const val SAVE_EVERY_MS = 3_000L
        const val GAME_POLL_MS = 3_000L
        val GROUPS = ListSerializer(GroupDto.serializer())

        fun live(id: String) = "live:$id"
    }
}

private fun GroupDraft.body() = GroupBody(name, handle, about, entry?.wire, listed, accent)

private inline fun <reified T> HttpRequestBuilder.json(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

/** No retry helps: the group or game is gone, the caller is out of it, or groups are off for the account. */
private fun isFinal(error: AppError): Boolean =
    error == AppError.Unauthorized ||
        error == AppError.Forbidden ||
        (error is AppError.Rejected && error.code in FINAL_CODES)

private val FINAL_CODES =
    setOf(
        "GROUP_NOT_FOUND",
        "GROUP_NOT_MEMBER",
        "GROUP_BANNED",
        "GAME_NOT_FOUND",
        "FEATURE_UNAVAILABLE",
        "FEATURE_DISABLED",
        "FEATURE_COMING_SOON",
    )
