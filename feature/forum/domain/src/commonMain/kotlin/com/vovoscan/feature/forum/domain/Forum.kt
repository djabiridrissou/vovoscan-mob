package com.vovoscan.feature.forum.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/*
 * The margin: what readers leave anchored to what they read. Not a forum of topics: notes on a chapter (pinned on a
 * page or not), theories, the weekly salon of a series. Nobody sees what they have not read yet (the server masks it),
 * every list is capped. Online only: a post needs the server's spoiler and moderation rules.
 */

/**
 * The eight stickers (the forum contract): stable keys on the wire, Latin onomatopoeia on screen (never Japanese), a
 * label per UI language in the catalogues (`sticker.<key>`).
 */
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

enum class PostKind(
    val wire: String,
) {
    Note("note"),
    Theory("theory"),
    Salon("salon"),
    ;

    companion object {
        fun of(wire: String): PostKind = entries.firstOrNull { it.wire == wire } ?: Note
    }
}

enum class PostStatus {
    Visible,

    /** Three readers reported it: only its author and staff see it until staff decide. */
    Held,
    Hidden,
    Removed,

    /** Taken back by its author; kept (text wiped) because it has replies. */
    Deleted,
    ;

    companion object {
        fun of(wire: String): PostStatus = entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } ?: Visible
    }
}

/** Where a note sits on its page, in fractions of the page (0..1, 4 decimals). */
sealed interface Anchor {
    data class Point(
        val x: Double,
        val y: Double,
    ) : Anchor

    data class Frame(
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
    ) : Anchor

    /** A stroke drawn on a panel: 2 to 64 points. */
    data class Stroke(
        val points: List<Pair<Double, Double>>,
    ) : Anchor
}

object Anchors {
    const val MAX_POINTS = 64

    /** Smallest side of a frame (fraction of the page): a smaller drag is a point. */
    const val MIN_FRAME = 0.02

    fun round(value: Double): Double = kotlin.math.round(value.coerceIn(0.0, 1.0) * 10_000.0) / 10_000.0

    fun point(
        x: Double,
        y: Double,
    ) = Anchor.Point(round(x), round(y))

    /** A frame from two corners, in any order; a drag too small to be a frame is a point where it started. */
    fun frame(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): Anchor {
        val left = round(minOf(x0, x1))
        val top = round(minOf(y0, y1))
        val w = round(maxOf(x0, x1)) - left
        val h = round(maxOf(y0, y1)) - top
        if (w < MIN_FRAME || h < MIN_FRAME) return point(x0, y0)
        return Anchor.Frame(left, top, round(w), round(h))
    }

    /**
     * A stroke from the finger's points: rounded, consecutive duplicates dropped, then thinned (Ramer-Douglas-Peucker,
     * the tolerance grown until it fits) to at most [MAX_POINTS], keeping both ends; null below two distinct points.
     */
    fun stroke(raw: List<Pair<Double, Double>>): Anchor.Stroke? {
        val points =
            raw
                .map { (x, y) -> round(x) to round(y) }
                .fold(mutableListOf<Pair<Double, Double>>()) { acc, p -> acc.apply { if (lastOrNull() != p) add(p) } }
        if (points.size < 2) return null
        var tolerance = 0.001
        var kept = points.toList()
        while (kept.size > MAX_POINTS) {
            kept = simplify(points, tolerance)
            tolerance *= 1.5
        }
        return Anchor.Stroke(kept)
    }

    private fun simplify(
        points: List<Pair<Double, Double>>,
        tolerance: Double,
    ): List<Pair<Double, Double>> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque(listOf(0 to points.lastIndex))
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast()
            var worst = -1
            var distance = tolerance
            for (i in from + 1 until to) {
                val d = distanceToSegment(points[i], points[from], points[to])
                if (d > distance) {
                    distance = d
                    worst = i
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(from to worst)
                stack.addLast(worst to to)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToSegment(
        p: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val length = dx * dx + dy * dy
        val t = if (length == 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / length).coerceIn(0.0, 1.0)
        val x = a.first + t * dx - p.first
        val y = a.second + t * dy - p.second
        return kotlin.math.sqrt(x * x + y * y)
    }
}

data class Author(
    val id: String,
    val name: String,
)

enum class VerdictState { Open, Confirmed, Debunked, Disputed }

/** "It happened" / "It didn't", by readers who read further. Counts are null below 3 readers. */
data class Verdict(
    val state: VerdictState,
    val confirmed: Int?,
    val debunked: Int?,
    /** The caller's own vote: true = it happened, false = it didn't, null = none. */
    val mine: Boolean?,
    val canVote: Boolean,
)

data class Post(
    val id: String,
    val kind: PostKind,
    val parentId: String?,
    val chapterNumber: Double?,
    val pageIndex: Int?,
    val anchor: Anchor?,
    val sticker: Sticker?,
    /** Null while masked (a spoiler for this reader) or once wiped. */
    val body: String?,
    val language: String,
    val spoilerUntil: Double?,
    val salonWeek: String?,
    /** "creator" or "staff", frozen at posting. */
    val badge: String?,
    val status: PostStatus,
    val score: Int,
    val replyCount: Int,
    val createdAt: Long?,
    val editedAt: Long?,
    val author: Author,
    val masked: Boolean,
    val reactions: Map<Sticker, Int>,
    val myReaction: Sticker?,
    val verdict: Verdict?,
    val mine: Boolean,
    val editable: Boolean,
)

enum class Order(
    val wire: String,
) {
    Best("best"),
    New("new"),
    Old("old"),
}

/** A chapter's margin: pins on its pages (≤ 24 a page), notes (≤ 60), theories (≤ 30). */
data class Margin(
    val roomId: String,
    val chapterNumber: Double?,
    /** The furthest chapter number whose margin this reader opened. */
    val reach: Double?,
    /** How many readers got here; null below 3. */
    val readersHere: Int?,
    val pins: List<Post>,
    val notes: List<Post>,
    val theories: List<Post>,
)

data class SalonWeek(
    val week: String,
    val count: Int,
)

/** The series' weekly salon (ISO week); earlier weeks read only. */
data class Salon(
    val roomId: String,
    val week: String,
    val current: Boolean,
    val weeks: List<SalonWeek>,
    val reach: Double?,
    val posts: List<Post>,
)

data class Theories(
    val roomId: String,
    val reach: Double?,
    val posts: List<Post>,
)

data class Replies(
    val post: Post,
    val replies: List<Post>,
)

enum class TranslationStatus { Queued, Ready, Failed }

/** A post's translation, made once and shared by everyone. */
data class PostTranslation(
    val id: String,
    val postId: String,
    val language: String,
    val status: TranslationStatus,
    val text: String?,
    val sourceLanguage: String?,
    val error: String?,
)

enum class ReportReason(
    val wire: String,
) {
    Spoiler("spoiler"),
    Harassment("harassment"),
    Spam("spam"),
    Nsfw("nsfw"),
    Other("other"),
}

/** What a reader posts at a chapter. Only notes take [pageIndex], [anchor] and [sticker]. */
data class Draft(
    val kind: PostKind,
    val language: String,
    val body: String?,
    val sticker: Sticker? = null,
    val pageIndex: Int? = null,
    val anchor: Anchor? = null,
    val spoiler: Boolean = false,
)

data class MutedReader(
    val userId: String,
    val name: String,
)

interface ForumRepository {
    /** The chapter's margin last read on this device (display only, never written to). */
    fun observeMargin(chapterId: String): Flow<Margin?>

    /** Reads the chapter's margin (raises the reader's reach to it) and keeps it for display. */
    suspend fun margin(
        chapterId: String,
        order: Order,
    ): AppResult<Margin>

    suspend fun post(
        chapterId: String,
        draft: Draft,
    ): AppResult<Post>

    /** [week] null: the current week. */
    suspend fun salon(
        chapterId: String,
        week: String?,
    ): AppResult<Salon>

    suspend fun theories(
        chapterId: String,
        order: Order,
    ): AppResult<Theories>

    suspend fun replies(postId: String): AppResult<Replies>

    suspend fun reply(
        postId: String,
        language: String,
        body: String,
        spoiler: Boolean,
    ): AppResult<Post>

    suspend fun edit(
        postId: String,
        body: String,
    ): AppResult<Post>

    suspend fun delete(postId: String): AppResult<Unit>

    suspend fun reveal(postId: String): AppResult<Post>

    /** [sticker] null takes the reaction back. */
    suspend fun react(
        postId: String,
        sticker: Sticker?,
    ): AppResult<Post>

    /** [happened] null takes the vote back. */
    suspend fun vote(
        postId: String,
        happened: Boolean?,
    ): AppResult<Post>

    suspend fun report(
        postId: String,
        reason: ReportReason,
        note: String?,
    ): AppResult<Unit>

    suspend fun translate(
        postId: String,
        language: String,
    ): AppResult<PostTranslation>

    suspend fun translation(id: String): AppResult<PostTranslation>

    suspend fun mutes(): AppResult<List<MutedReader>>

    suspend fun mute(
        userId: String,
        muted: Boolean,
    ): AppResult<Unit>

    suspend fun clearLocal()
}

/** Port: the `forum` feature flag of the account (wired in `shared` to the session). Off: nothing of the margin shows. */
fun interface ForumAccess {
    fun enabled(): Flow<Boolean>

    companion object {
        val Off = ForumAccess { flowOf(false) }
    }
}

/** Port: the language a reader writes in: the reading language when there is one, else the app's language. */
fun interface WritingLanguage {
    suspend fun current(): String
}

/** The server's text rules, checked before sending so the composer says it at once. */
object ForumRules {
    const val NOTE_MAX = 500
    const val THEORY_MAX = 1500
    const val SALON_MAX = 1000
    const val REPLY_MAX = 500
    const val MAX_LINKS = 2

    fun max(
        kind: PostKind,
        reply: Boolean = false,
    ): Int =
        when {
            reply -> REPLY_MAX
            kind == PostKind.Theory -> THEORY_MAX
            kind == PostKind.Salon -> SALON_MAX
            else -> NOTE_MAX
        }

    /** Plain text as the server keeps it: control and bidi characters dropped, at most one blank line, trimmed. */
    fun clean(text: String): String {
        val kept =
            text
                .replace("\r\n", "\n")
                .filter { c -> c == '\n' || c == '\t' || (!c.isISOControl() && c !in BIDI) }
        return kept
            .split('\n')
            .joinToString("\n") { it.trimEnd() }
            .replace(BLANK_LINES, "\n\n")
            .trim()
    }

    fun links(text: String): Int = LINK.findAll(text).count()

    /** Why [body] can't be sent as it is (`FORUM_EMPTY`, `FORUM_TOO_LONG`, `FORUM_TOO_MANY_LINKS`), or null. */
    fun problem(
        kind: PostKind,
        body: String,
        hasMark: Boolean,
        reply: Boolean = false,
    ): String? {
        val text = clean(body)
        return when {
            text.isEmpty() && !hasMark -> "FORUM_EMPTY"
            text.length > max(kind, reply) -> "FORUM_TOO_LONG"
            links(text) > MAX_LINKS -> "FORUM_TOO_MANY_LINKS"
            else -> null
        }
    }

    private val BIDI = setOf('‎', '‏', '‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩')
    private val BLANK_LINES = Regex("\n{3,}")
    private val LINK = Regex("""(?i)\b(?:https?://|www\.)\S+""")
}
