package com.vovoscan.feature.groups.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.groups.domain.BadgeStatus
import com.vovoscan.feature.groups.domain.Entry
import com.vovoscan.feature.groups.domain.Game
import com.vovoscan.feature.groups.domain.GameKind
import com.vovoscan.feature.groups.domain.Group
import com.vovoscan.feature.groups.domain.GroupDraft
import com.vovoscan.feature.groups.domain.GroupStatus
import com.vovoscan.feature.groups.domain.GroupsAccess
import com.vovoscan.feature.groups.domain.GroupsRepository
import com.vovoscan.feature.groups.domain.GroupsUser
import com.vovoscan.feature.groups.domain.Live
import com.vovoscan.feature.groups.domain.Me
import com.vovoscan.feature.groups.domain.MemberPage
import com.vovoscan.feature.groups.domain.MemberState
import com.vovoscan.feature.groups.domain.Message
import com.vovoscan.feature.groups.domain.MessageKind
import com.vovoscan.feature.groups.domain.MessageStatus
import com.vovoscan.feature.groups.domain.Move
import com.vovoscan.feature.groups.domain.Reaction
import com.vovoscan.feature.groups.domain.ReportReason
import com.vovoscan.feature.groups.domain.ReportedMessage
import com.vovoscan.feature.groups.domain.Role
import com.vovoscan.feature.groups.domain.Standing
import com.vovoscan.feature.groups.domain.Sticker
import com.vovoscan.feature.groups.domain.SystemEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400_000L
private const val NOW = 20_000 * DAY + 15 * 3_600_000L

fun group(
    me: Me? = Me(Role.Member, MemberState.Member, notify = true, joinedAtMillis = 0, lastReadAtMillis = null),
    entry: Entry = Entry.Open,
) = Group(
    id = "g1",
    name = "Ink Club",
    handle = "ink-club",
    sharePath = "/g/ink-club",
    about = "Lanterns and ink.",
    entry = entry,
    listed = true,
    accent = 2,
    memberCount = 12,
    verified = false,
    status = GroupStatus.Active,
    createdAtMillis = 0,
    activeAtMillis = 0,
    me = me,
    pending = 0,
    unread = 0,
    last = null,
    badgeRequest = null,
)

fun message(
    id: String,
    at: Long,
    author: String? = "u2",
    body: String = "hello",
    kind: MessageKind = MessageKind.Text,
    system: SystemEvent? = null,
    reactions: List<Reaction> = emptyList(),
    mine: Sticker? = null,
) = Message(
    id = id,
    groupId = "g1",
    authorId = author,
    authorName = author?.let { if (it == "u1") "Me" else "Aya" },
    kind = kind,
    body = body,
    sticker = null,
    system = system,
    status = MessageStatus.Visible,
    reactions = reactions,
    mine = mine,
    createdAtMillis = at,
)

class FakeGroups(
    var current: Group = group(),
    var history: List<Message> = emptyList(),
) : GroupsRepository {
    var snapshot: Live? = null
    var sent = mutableListOf<String?>()
    var reacted = mutableListOf<Pair<String, Sticker?>>()
    var reactResult: AppResult<Unit> = AppResult.Ok(Unit)
    var joined = 0
    var read = 0

    override fun observeMine(): Flow<List<Group>?> = flowOf(null)

    override suspend fun mine() = AppResult.Ok(listOf(current))

    override suspend fun search(
        query: String,
        page: Int,
    ) = AppResult.Ok(emptyList<Group>())

    override suspend fun byHandle(handle: String): AppResult<Group> =
        if (handle ==
            current.handle
        ) {
            AppResult.Ok(current)
        } else {
            AppResult.Err(AppError.Rejected("GROUP_NOT_FOUND", ""))
        }

    override suspend fun group(id: String): AppResult<Group> = AppResult.Ok(current)

    override suspend fun create(draft: GroupDraft): AppResult<Group> = error("unused")

    override suspend fun update(
        id: String,
        draft: GroupDraft,
    ): AppResult<Group> = error("unused")

    override suspend fun delete(id: String): AppResult<Unit> = error("unused")

    override suspend fun join(
        id: String,
        note: String?,
    ): AppResult<Group> {
        joined += 1
        current = current.copy(me = Me(Role.Member, MemberState.Member, true, 0, null))
        return AppResult.Ok(current)
    }

    override suspend fun leave(id: String) = AppResult.Ok(Unit)

    override suspend fun notify(
        id: String,
        on: Boolean,
    ) = AppResult.Ok(Unit)

    override suspend fun markRead(id: String): AppResult<Unit> {
        read += 1
        return AppResult.Ok(Unit)
    }

    override suspend fun members(
        id: String,
        state: MemberState,
        search: String?,
        page: Int,
    ) = AppResult.Ok(MemberPage(emptyList(), 0, false))

    override suspend fun decide(
        id: String,
        userId: String,
        approve: Boolean,
    ): AppResult<Unit> = error("unused")

    override suspend fun setRole(
        id: String,
        userId: String,
        role: Role,
    ): AppResult<Unit> = error("unused")

    override suspend fun remove(
        id: String,
        userId: String,
        ban: Boolean,
    ): AppResult<Unit> = error("unused")

    override suspend fun unban(
        id: String,
        userId: String,
    ): AppResult<Unit> = error("unused")

    override suspend fun cachedLive(id: String): Live? = null

    override suspend fun messages(
        id: String,
        before: String?,
        after: String?,
    ) = AppResult.Ok(history)

    override suspend fun send(
        id: String,
        text: String?,
        sticker: Sticker?,
        replyToId: String?,
    ): AppResult<Message> {
        sent += text
        return AppResult.Ok(message("sent${sent.size}", NOW, author = "u1", body = text.orEmpty()))
    }

    override suspend fun edit(
        messageId: String,
        body: String,
    ): AppResult<Message> = error("unused")

    override suspend fun deleteMessage(messageId: String): AppResult<Unit> = error("unused")

    override suspend fun restore(messageId: String): AppResult<Unit> = error("unused")

    override suspend fun react(
        messageId: String,
        sticker: Sticker?,
    ): AppResult<Unit> {
        reacted += messageId to sticker
        return reactResult
    }

    override suspend fun report(
        messageId: String,
        reason: ReportReason,
    ) = AppResult.Ok(Unit)

    override suspend fun reports(id: String): AppResult<List<ReportedMessage>> = error("unused")

    override suspend fun badge(id: String): AppResult<BadgeStatus> = error("unused")

    override suspend fun askBadge(
        id: String,
        reason: String,
        links: List<String>,
    ): AppResult<Unit> = error("unused")

    override suspend fun withdrawBadge(id: String): AppResult<Unit> = error("unused")

    override suspend fun live(
        id: String,
        onLive: suspend (Live) -> Boolean,
    ): AppResult<Unit> {
        snapshot?.let { onLive(it) }
        awaitCancellation()
    }

    override suspend fun startGame(
        groupId: String,
        game: GameKind,
        seats: Int,
        ranked: Boolean,
        invite: List<String>,
        inviteOnly: Boolean,
    ): AppResult<Game> = error("unused")

    override suspend fun game(id: String): AppResult<Game> = error("unused")

    override suspend fun joinGame(id: String): AppResult<Game> = error("unused")

    override suspend fun leaveGame(id: String): AppResult<Game> = error("unused")

    override suspend fun move(
        id: String,
        move: Move,
    ): AppResult<Game> = error("unused")

    override suspend fun games(
        groupId: String,
        live: Boolean,
        page: Int,
    ) = AppResult.Ok(emptyList<Game>())

    override suspend fun leaderboard(
        groupId: String,
        game: GameKind,
        page: Int,
    ) = AppResult.Ok(emptyList<Standing>())

    override suspend fun gameLive(
        id: String,
        onGame: suspend (Game) -> Boolean,
    ): AppResult<Unit> = awaitCancellation()

    override suspend fun clearLocal() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        I18n.use("en", null)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repo: FakeGroups,
        ref: String = "g1",
    ) = GroupViewModel(ref, repo, GroupsAccess { flowOf(true) }, GroupsUser { "u1" }, { NOW }, { 0L })

    @Test
    fun theChatGroupsRunsByAuthorAndStartsEachDay() =
        runTest(dispatcher) {
            val yesterday = NOW - DAY
            val repo =
                FakeGroups(
                    history =
                        listOf(
                            message("m1", yesterday),
                            message("m2", NOW - 60_000),
                            message("m3", NOW - 30_000),
                            message("m4", NOW - 10_000, author = "u1"),
                            message("s1", NOW, author = null, kind = MessageKind.System, system = SystemEvent("joined", name = "Ren")),
                        ),
                )
            val vm = viewModel(repo)
            advanceUntilIdle()
            val rows = vm.state.value.messages
            assertEquals(listOf("m1", "m2", "m3", "m4", "s1"), rows.map { it.id })
            assertEquals("Yesterday", rows[0].day)
            assertEquals("Today", rows[1].day)
            assertNull(rows[2].day)
            assertEquals("Aya", rows[1].author)
            assertNull(rows[2].author)
            assertTrue(rows[3].own)
            assertEquals("Ren joined.", rows[4].text)
            assertTrue(vm.state.value.member)
            assertNull(vm.state.value.join)
        }

    @Test
    fun aVisitorSeesTheGroupCardAndJoins() =
        runTest(dispatcher) {
            val repo = FakeGroups(current = group(me = null))
            val vm = viewModel(repo, ref = "@ink-club")
            advanceUntilIdle()
            val join = assertNotNull(vm.state.value.join)
            assertEquals("Join", join.label)
            assertFalse(vm.state.value.member)
            vm.onIntent(GroupIntent.JoinAct)
            advanceUntilIdle()
            assertEquals(1, repo.joined)
            assertTrue(vm.state.value.member)
        }

    @Test
    fun aMessageGoesOnlyWhenTheRulesAllowIt() =
        runTest(dispatcher) {
            val repo = FakeGroups()
            val vm = viewModel(repo)
            advanceUntilIdle()
            vm.onIntent(GroupIntent.Text("   "))
            assertFalse(vm.state.value.composer.canSend)
            vm.onIntent(GroupIntent.Send)
            advanceUntilIdle()
            assertEquals("Say something first.", vm.state.value.composer.error)
            vm.onIntent(GroupIntent.Text(" see you at chapter 40 "))
            vm.onIntent(GroupIntent.Send)
            advanceUntilIdle()
            assertEquals(listOf<String?>("see you at chapter 40"), repo.sent)
            assertEquals("", vm.state.value.composer.text)
            assertEquals(
                "sent1",
                vm.state.value.messages
                    .last()
                    .id,
            )
        }

    @Test
    fun aReactionShowsAtOnceAndTheSameStickerTakesItBack() =
        runTest(dispatcher) {
            val repo = FakeGroups(history = listOf(message("m1", NOW, reactions = listOf(Reaction(Sticker.Doki, 2)))))
            val vm = viewModel(repo)
            advanceUntilIdle()
            vm.onIntent(GroupIntent.React("m1", "doki"))
            assertEquals(
                3,
                vm.state.value.messages[0]
                    .reactions
                    .first()
                    .count,
            )
            assertTrue(
                vm.state.value.messages[0]
                    .reactions
                    .first()
                    .mine,
            )
            advanceUntilIdle()
            vm.onIntent(GroupIntent.React("m1", "doki"))
            advanceUntilIdle()
            assertEquals(listOf("m1" to Sticker.Doki, "m1" to null), repo.reacted)
        }

    @Test
    fun aRefusedReactionIsPutBack() =
        runTest(dispatcher) {
            val repo = FakeGroups(history = listOf(message("m1", NOW)))
            repo.reactResult = AppResult.Err(AppError.Rejected("GROUP_NOT_MEMBER", "Join the group first."))
            val vm = viewModel(repo)
            advanceUntilIdle()
            vm.onIntent(GroupIntent.React("m1", "kira"))
            advanceUntilIdle()
            assertTrue(
                vm.state.value.messages[0]
                    .reactions
                    .isEmpty(),
            )
            assertNotNull(vm.state.value.error)
        }

    @Test
    fun aSnapshotSeenOnScreenIsMarkedRead() =
        runTest(dispatcher) {
            val repo = FakeGroups()
            repo.snapshot = Live(group(), listOf(message("m9", NOW)), emptyList())
            val vm = viewModel(repo)
            advanceUntilIdle()
            // Started, the screen's timer ticks for ever: time is moved by hand from here.
            vm.onIntent(GroupIntent.Start)
            runCurrent()
            assertEquals(
                "m9",
                vm.state.value.messages
                    .last()
                    .id,
            )
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(1, repo.read)
            vm.onIntent(GroupIntent.Stop)
        }
}
