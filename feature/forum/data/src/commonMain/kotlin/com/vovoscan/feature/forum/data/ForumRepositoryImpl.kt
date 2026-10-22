package com.vovoscan.feature.forum.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.ApiJson
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.network.apiCallUnit
import com.vovoscan.feature.forum.data.db.ForumDatabase
import com.vovoscan.feature.forum.domain.Draft
import com.vovoscan.feature.forum.domain.ForumRepository
import com.vovoscan.feature.forum.domain.Margin
import com.vovoscan.feature.forum.domain.MutedReader
import com.vovoscan.feature.forum.domain.Order
import com.vovoscan.feature.forum.domain.Post
import com.vovoscan.feature.forum.domain.PostTranslation
import com.vovoscan.feature.forum.domain.Replies
import com.vovoscan.feature.forum.domain.ReportReason
import com.vovoscan.feature.forum.domain.Salon
import com.vovoscan.feature.forum.domain.Sticker
import com.vovoscan.feature.forum.domain.Theories
import io.ktor.client.HttpClient
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

/** `api/v1/forum`: every route of the margin a reader uses (moderation stays in the web console). */
interface ForumRemoteDataSource {
    suspend fun margin(
        chapterId: String,
        order: Order,
    ): AppResult<MarginDto>

    suspend fun post(
        chapterId: String,
        body: PostBody,
    ): AppResult<PostDto>

    suspend fun salon(
        chapterId: String,
        week: String?,
    ): AppResult<SalonDto>

    suspend fun theories(
        chapterId: String,
        order: Order,
    ): AppResult<TheoriesDto>

    suspend fun replies(postId: String): AppResult<RepliesDto>

    suspend fun reply(
        postId: String,
        body: ReplyBody,
    ): AppResult<PostDto>

    suspend fun edit(
        postId: String,
        body: String,
    ): AppResult<PostDto>

    suspend fun delete(postId: String): AppResult<Unit>

    suspend fun reveal(postId: String): AppResult<PostDto>

    suspend fun react(
        postId: String,
        key: String?,
    ): AppResult<PostDto>

    suspend fun vote(
        postId: String,
        verdict: String?,
    ): AppResult<PostDto>

    suspend fun report(
        postId: String,
        body: ReportBody,
    ): AppResult<Unit>

    suspend fun translate(
        postId: String,
        language: String,
    ): AppResult<TranslationDto>

    suspend fun translation(id: String): AppResult<TranslationDto>

    suspend fun mutes(): AppResult<List<MuteDto>>

    suspend fun mute(
        userId: String,
        muted: Boolean,
    ): AppResult<Unit>
}

class ApiForumRemoteDataSource(
    private val http: HttpClient,
) : ForumRemoteDataSource {
    private fun chapter(id: String) = "$BASE/chapters/${id.encodeURLPathPart()}"

    private fun post(id: String) = "$BASE/posts/${id.encodeURLPathPart()}"

    override suspend fun margin(
        chapterId: String,
        order: Order,
    ): AppResult<MarginDto> =
        http
            .apiCall<ApiData<MarginDto>> { get("${chapter(chapterId)}/margin") { parameter("order", order.wire) } }
            .map { it.data }

    override suspend fun post(
        chapterId: String,
        body: PostBody,
    ): AppResult<PostDto> =
        http
            .apiCall<ApiData<PostDto>> {
                post("${chapter(chapterId)}/posts") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }.map { it.data }

    override suspend fun salon(
        chapterId: String,
        week: String?,
    ): AppResult<SalonDto> =
        http
            .apiCall<ApiData<SalonDto>> { get("${chapter(chapterId)}/salon") { week?.let { parameter("week", it) } } }
            .map { it.data }

    override suspend fun theories(
        chapterId: String,
        order: Order,
    ): AppResult<TheoriesDto> =
        http
            .apiCall<ApiData<TheoriesDto>> { get("${chapter(chapterId)}/theories") { parameter("order", order.wire) } }
            .map { it.data }

    override suspend fun replies(postId: String): AppResult<RepliesDto> =
        http.apiCall<ApiData<RepliesDto>> { get("${post(postId)}/replies") }.map { it.data }

    override suspend fun reply(
        postId: String,
        body: ReplyBody,
    ): AppResult<PostDto> =
        http
            .apiCall<ApiData<PostDto>> {
                post("${post(postId)}/replies") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }.map { it.data }

    override suspend fun edit(
        postId: String,
        body: String,
    ): AppResult<PostDto> =
        http
            .apiCall<ApiData<PostDto>> {
                patch(post(postId)) {
                    contentType(ContentType.Application.Json)
                    setBody(EditBody(body))
                }
            }.map { it.data }

    override suspend fun delete(postId: String): AppResult<Unit> = http.apiCallUnit { delete(post(postId)) }

    override suspend fun reveal(postId: String): AppResult<PostDto> =
        http.apiCall<ApiData<PostDto>> { post("${post(postId)}/reveal") }.map { it.data }

    override suspend fun react(
        postId: String,
        key: String?,
    ): AppResult<PostDto> =
        http
            .apiCall<ApiData<PostDto>> {
                if (key == null) {
                    delete("${post(postId)}/reaction")
                } else {
                    put("${post(postId)}/reaction") {
                        contentType(ContentType.Application.Json)
                        setBody(ReactionBody(key))
                    }
                }
            }.map { it.data }

    override suspend fun vote(
        postId: String,
        verdict: String?,
    ): AppResult<PostDto> =
        http
            .apiCall<ApiData<PostDto>> {
                if (verdict == null) {
                    delete("${post(postId)}/vote")
                } else {
                    put("${post(postId)}/vote") {
                        contentType(ContentType.Application.Json)
                        setBody(VoteBody(verdict))
                    }
                }
            }.map { it.data }

    override suspend fun report(
        postId: String,
        body: ReportBody,
    ): AppResult<Unit> =
        http.apiCallUnit {
            post("${post(postId)}/reports") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    override suspend fun translate(
        postId: String,
        language: String,
    ): AppResult<TranslationDto> =
        http
            .apiCall<ApiData<TranslationDto>> {
                post("${post(postId)}/translations") {
                    contentType(ContentType.Application.Json)
                    setBody(TranslateBody(language))
                }
            }.map { it.data }

    override suspend fun translation(id: String): AppResult<TranslationDto> =
        http.apiCall<ApiData<TranslationDto>> { get("$BASE/translations/${id.encodeURLPathPart()}") }.map { it.data }

    override suspend fun mutes(): AppResult<List<MuteDto>> = http.apiCall<ApiData<List<MuteDto>>> { get("$BASE/mutes") }.map { it.data }

    override suspend fun mute(
        userId: String,
        muted: Boolean,
    ): AppResult<Unit> {
        val path = "$BASE/mutes/${userId.encodeURLPathPart()}"
        return http.apiCallUnit { if (muted) put(path) else delete(path) }
    }

    private companion object {
        const val BASE = "api/v1/forum"
    }
}

/**
 * Online only (a post needs the server's spoiler and moderation rules: nothing is queued). The last margin read per
 * chapter is kept for display ([observeMargin]), as the server answered it to this account.
 */
class ForumRepositoryImpl(
    private val remote: ForumRemoteDataSource,
    private val database: ForumDatabase,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
) : ForumRepository {
    private val queries get() = database.forumQueries

    override fun observeMargin(chapterId: String): Flow<Margin?> =
        queries
            .margin(chapterId)
            .asFlow()
            .mapToOneOrNull(io)
            .map { body -> body?.let { runCatching { ApiJson.decodeFromString(MarginDto.serializer(), it).toDomain() }.getOrNull() } }

    override suspend fun margin(
        chapterId: String,
        order: Order,
    ): AppResult<Margin> =
        when (val result = remote.margin(chapterId, order)) {
            is AppResult.Err -> result
            is AppResult.Ok -> {
                withContext(io) {
                    queries.transaction {
                        queries.putMargin(chapterId, ApiJson.encodeToString(MarginDto.serializer(), result.value), clock.nowMillis())
                        // The 50 chapters read last: an old chapter's margin is read again when it is opened.
                        queries.trimMargins(KEPT)
                    }
                }
                AppResult.Ok(result.value.toDomain())
            }
        }

    override suspend fun post(
        chapterId: String,
        draft: Draft,
    ): AppResult<Post> =
        remote
            .post(
                chapterId,
                PostBody(
                    kind = draft.kind.wire,
                    language = draft.language,
                    body = draft.body?.takeIf { it.isNotBlank() },
                    sticker = draft.sticker?.key,
                    pageIndex = draft.pageIndex,
                    anchor = draft.anchor?.let(AnchorDto::of),
                    spoiler = draft.spoiler.takeIf { it },
                ),
            ).map { it.toDomain() }

    override suspend fun salon(
        chapterId: String,
        week: String?,
    ): AppResult<Salon> = remote.salon(chapterId, week).map { it.toDomain() }

    override suspend fun theories(
        chapterId: String,
        order: Order,
    ): AppResult<Theories> = remote.theories(chapterId, order).map { it.toDomain() }

    override suspend fun replies(postId: String): AppResult<Replies> = remote.replies(postId).map { it.toDomain() }

    override suspend fun reply(
        postId: String,
        language: String,
        body: String,
        spoiler: Boolean,
    ): AppResult<Post> = remote.reply(postId, ReplyBody(language, body, spoiler.takeIf { it })).map { it.toDomain() }

    override suspend fun edit(
        postId: String,
        body: String,
    ): AppResult<Post> = remote.edit(postId, body).map { it.toDomain() }

    override suspend fun delete(postId: String): AppResult<Unit> = remote.delete(postId)

    override suspend fun reveal(postId: String): AppResult<Post> = remote.reveal(postId).map { it.toDomain() }

    override suspend fun react(
        postId: String,
        sticker: Sticker?,
    ): AppResult<Post> = remote.react(postId, sticker?.key).map { it.toDomain() }

    override suspend fun vote(
        postId: String,
        happened: Boolean?,
    ): AppResult<Post> =
        remote
            .vote(
                postId,
                when (happened) {
                    true -> "confirmed"
                    false -> "debunked"
                    null -> null
                },
            ).map { it.toDomain() }

    override suspend fun report(
        postId: String,
        reason: ReportReason,
        note: String?,
    ): AppResult<Unit> = remote.report(postId, ReportBody(reason.wire, note?.takeIf { it.isNotBlank() }))

    override suspend fun translate(
        postId: String,
        language: String,
    ): AppResult<PostTranslation> = remote.translate(postId, language).map { it.toDomain() }

    override suspend fun translation(id: String): AppResult<PostTranslation> = remote.translation(id).map { it.toDomain() }

    override suspend fun mutes(): AppResult<List<MutedReader>> = remote.mutes().map { list -> list.map { it.toDomain() } }

    override suspend fun mute(
        userId: String,
        muted: Boolean,
    ): AppResult<Unit> = remote.mute(userId, muted)

    override suspend fun clearLocal() = withContext(io) { queries.transaction { queries.clearMargins() } }

    private companion object {
        const val KEPT = 50L
    }
}
