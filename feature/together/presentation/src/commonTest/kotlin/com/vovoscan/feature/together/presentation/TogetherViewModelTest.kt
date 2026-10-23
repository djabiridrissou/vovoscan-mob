package com.vovoscan.feature.together.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.feature.together.domain.ChapterKind
import com.vovoscan.feature.together.domain.Device
import com.vovoscan.feature.together.domain.InvitePreview
import com.vovoscan.feature.together.domain.Member
import com.vovoscan.feature.together.domain.MutedReaders
import com.vovoscan.feature.together.domain.PagePoint
import com.vovoscan.feature.together.domain.Place
import com.vovoscan.feature.together.domain.Room
import com.vovoscan.feature.together.domain.RoomBrief
import com.vovoscan.feature.together.domain.RoomChapter
import com.vovoscan.feature.together.domain.Signal
import com.vovoscan.feature.together.domain.SignalKind
import com.vovoscan.feature.together.domain.Sticker
import com.vovoscan.feature.together.domain.ThisDevice
import com.vovoscan.feature.together.domain.TogetherAccess
import com.vovoscan.feature.together.domain.TogetherLanguage
import com.vovoscan.feature.together.domain.TogetherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val T0 = 1_800_000_000_000L

fun member(
    id: String,
    slot: Int,
    host: Boolean = false,
    following: Boolean = !host,
    place: Place? = null,
    seenAt: Long = T0,
) = Member(id, id.replaceFirstChar { it.uppercase() }, slot, host, true, following, place, null, Device.Ios, T0, seenAt)

fun signal(
    id: String,
    kind: SignalKind,
    from: String = "ben",
    at: Long = T0,
    sticker: Sticker? = null,
    text: String? = null,
    point: PagePoint? = null,
) = Signal(id, kind, from, from.replaceFirstChar { it.uppercase() }, sticker, text, point, null, null, null, at)

fun room(
    me: String = "ben",
    host: String = "aya",
    chapter: String = "c1",
    place: Place? = Place.Comic(2, 0.5),
    seq: Long = 1,
    following: Boolean = me != host,
    members: List<Member> = listOf(member("aya", 0, host = host == "aya"), member("ben", 1, host = host == "ben")),
    signals: List<Signal> = emptyList(),
    now: Long = T0,
    open: Boolean = true,
) = Room(
    id = "r1",
    code = "VK3Q9TZM",
    prettyCode = "VK3Q-9TZM",
    invitePath = "/together/VK3Q9TZM",
    open = open,
    locked = false,
    capacity = 8,
    closesAtMillis = T0 + 3_600_000,
    chapter = RoomChapter(chapter, 3.0, "The gate", ChapterKind.Comic, 10, "vovoscan", "t1", "Ink Lanterns"),
    hostId = host,
    place = place,
    placeSeq = seq,
    members = members,
    signals = signals,
    myId = me,
    following = following,
    serverTimeMillis = now,
)

class FakeTogetherRepository : TogetherRepository {
    var opened: Room? = null
    var joined: Room? = null
    var current: Room? = null
    var briefs: List<RoomBrief> = emptyList()
    val snapshots = Channel<Room>(Channel.UNLIMITED)
    val me = mutableListOf<Triple<Place?, Boolean?, String?>>()
    val signals = mutableListOf<Pair<SignalKind, String?>>()
    val moved = mutableListOf<String>()
    var left = 0
    var signalError: AppError? = null

    override suspend fun rooms() = AppResult.Ok(briefs)

    override suspend fun open(
        chapterId: String,
        place: Place?,
        language: String?,
        device: Device,
    ): AppResult<Room> = AppResult.Ok(opened!!)

    override suspend fun preview(code: String): AppResult<InvitePreview> =
        if (code == "VK3Q9TZM") {
            AppResult.Ok(
                InvitePreview("VK3Q-9TZM", "Ink Lanterns", 3.0, null, ChapterKind.Comic, "Aya", 3, 8, full = false, locked = false),
            )
        } else {
            AppResult.Err(AppError.Rejected("ROOM_NOT_FOUND", ""))
        }

    override suspend fun join(
        code: String,
        language: String?,
        device: Device,
    ): AppResult<Room> = AppResult.Ok(joined!!)

    override suspend fun room(id: String): AppResult<Room> =
        current?.let { AppResult.Ok(it) } ?: AppResult.Err(AppError.Rejected("ROOM_NOT_FOUND", ""))

    override suspend fun live(
        id: String,
        onRoom: suspend (Room) -> Boolean,
    ): AppResult<Unit> {
        for (next in snapshots) if (!onRoom(next)) return AppResult.Ok(Unit)
        return AppResult.Ok(Unit)
    }

    override suspend fun me(
        id: String,
        place: Place?,
        following: Boolean?,
        language: String?,
    ): AppResult<Unit> {
        me += Triple(place, following, language)
        return AppResult.Ok(Unit)
    }

    override suspend fun leave(id: String): AppResult<Unit> {
        left += 1
        return AppResult.Ok(Unit)
    }

    private var signalIds = 0

    override suspend fun signal(
        id: String,
        kind: SignalKind,
        sticker: Sticker?,
        text: String?,
        at: PagePoint?,
    ): AppResult<Signal> {
        signalError?.let { return AppResult.Err(it) }
        signals += kind to (sticker?.key ?: text)
        return AppResult.Ok(signal("mine-${++signalIds}", kind, from = "ben"))
    }

    override suspend fun moveTo(
        id: String,
        chapterId: String,
        place: Place?,
    ): AppResult<Room> {
        moved += chapterId
        return AppResult.Ok(room(me = "aya", chapter = chapterId, seq = 9))
    }

    override suspend fun handOver(
        id: String,
        userId: String,
    ) = AppResult.Ok(room(me = "aya", host = userId))

    override suspend fun lock(
        id: String,
        locked: Boolean,
    ) = AppResult.Ok(room(me = "aya", host = "aya").copy(locked = locked))

    override suspend fun renewCode(id: String) =
        AppResult.Ok(room(me = "aya", host = "aya").copy(code = "AAAA1111", prettyCode = "AAAA-1111"))

    override suspend fun remove(
        id: String,
        userId: String,
    ) = AppResult.Ok(Unit)

    override suspend fun close(id: String) = AppResult.Ok(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
class TogetherViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeTogetherRepository()
    private val access = MutableStateFlow(true)

    private val made = mutableListOf<TogetherViewModel>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    /** Every view model made is stopped at the end, whatever the outcome (its heartbeat loops would never end). */
    private fun test(body: suspend TestScope.() -> Unit) =
        runTest(timeout = 20.seconds) {
            try {
                body()
            } finally {
                made.forEach { it.onIntent(TogetherIntent.Stop) }
            }
        }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.session() = TogetherSession(repo, CoroutineScope(dispatcher))

    private fun TestScope.viewModel(
        session: TogetherSession = session(),
        chapterId: String = "c1",
        muted: Set<String> = emptySet(),
    ) = TogetherViewModel(
        chapterId,
        repo,
        session,
        TogetherAccess { access },
        TogetherLanguage { "fr" },
        MutedReaders { muted },
        ThisDevice(Device.Android),
        Clock { T0 + testScheduler.currentTime },
    ).also { made += it }

    private fun TestScope.push(room: Room) {
        repo.snapshots.trySend(room.copy(serverTimeMillis = T0 + testScheduler.currentTime))
        runCurrent()
    }

    /** A follower on chapter c1, live. */
    private fun TestScope.follower(muted: Set<String> = emptySet()): Pair<TogetherViewModel, TogetherSession> {
        val session = session()
        repo.current = room()
        repo.briefs = listOf(RoomBrief("r1", true, "c1", "aya"))
        val vm = viewModel(session, muted = muted)
        vm.onIntent(TogetherIntent.Start)
        runCurrent()
        return vm to session
    }

    @Test
    fun hiddenWithoutTheFlag() =
        test {
            access.value = false
            val vm = viewModel()
            runCurrent()
            assertFalse(vm.state.value.visible)
            access.value = true
            runCurrent()
            assertTrue(vm.state.value.visible)
        }

    @Test
    fun aHostOpensACircleAndSendsItsPlaceAtMostEvery300Ms() =
        test {
            repo.opened = room(me = "aya", host = "aya", place = null, seq = 0)
            val vm = viewModel()
            vm.onIntent(TogetherIntent.Start)
            vm.onIntent(TogetherIntent.PlaceChanged(book = false, page = 0, fraction = 0.1f, panel = 0, section = 0, count = 10))
            vm.onIntent(TogetherIntent.OpenSheet)
            runCurrent()
            assertEquals(TogetherSheet.Choose, vm.state.value.sheet)
            vm.onIntent(TogetherIntent.StartCircle)
            runCurrent()
            assertEquals(TogetherSheet.Ticket, vm.state.value.sheet)
            val circle = assertNotNull(vm.state.value.circle)
            assertEquals("VK3Q-9TZM", circle.ticket.code)
            assertEquals("vovoscan://together/VK3Q9TZM", circle.ticket.appLink)
            assertEquals("together/VK3Q9TZM", circle.ticket.webPath)
            assertTrue(circle.isHost)
            assertNull(circle.followLabel)
            repo.me.clear()
            vm.onIntent(TogetherIntent.PlaceChanged(book = false, page = 1, fraction = 0.2f, panel = 1, section = 0, count = 10))
            vm.onIntent(TogetherIntent.PlaceChanged(book = false, page = 1, fraction = 0.3f, panel = 1, section = 0, count = 10))
            vm.onIntent(TogetherIntent.PlaceChanged(book = false, page = 1, fraction = 0.4f, panel = 2, section = 0, count = 10))
            advanceTimeBy(350)
            runCurrent()
            // The trailing place goes out once, the last one.
            assertEquals(listOf(Place.Comic(1, 0.4, 2)), repo.me.mapNotNull { it.first })
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun aFollowerGlidesOnlyToNewerPlacesAndSendsNone() =
        test {
            val (vm, _) = follower()
            push(room(seq = 1))
            val first = assertIs<TogetherCommand.GoToPlace>(vm.state.value.command)
            assertEquals(2, first.page)
            assertEquals(0.5f, first.fraction)
            vm.onIntent(TogetherIntent.CommandHandled(first.seq))
            // The same or an older sequence never pulls the reader back.
            push(room(seq = 1, place = Place.Comic(0, 0.0)))
            assertNull(vm.state.value.command)
            push(room(seq = 4, place = Place.Comic(5, 0.25)))
            assertEquals(5, assertIs<TogetherCommand.GoToPlace>(vm.state.value.command).page)
            assertEquals(
                "Following Aya",
                vm.state.value.circle
                    ?.followLabel,
            )
            repo.me.clear()
            vm.onIntent(TogetherIntent.PlaceChanged(book = false, page = 5, fraction = 0.25f, panel = 3, section = 0, count = 10))
            advanceTimeBy(1_500)
            runCurrent()
            assertTrue(repo.me.none { it.first != null })
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun aGestureOfTheirOwnLetsGoAndFollowGlidesBack() =
        test {
            val (vm, _) = follower()
            push(room(seq = 2))
            vm.onIntent(
                TogetherIntent.CommandHandled(
                    vm.state.value.command!!
                        .seq,
                ),
            )
            repo.me.clear()
            vm.onIntent(TogetherIntent.PlaceChanged(book = false, page = 7, fraction = 0.1f, panel = -1, section = 0, count = 10))
            vm.onIntent(TogetherIntent.LetGo)
            runCurrent()
            assertEquals(false, repo.me.first().second)
            // On their own, their place goes out (the rail shows it).
            assertEquals(Place.Comic(7, 0.1), repo.me.last().first)
            assertEquals(
                "On your own · Follow",
                vm.state.value.circle
                    ?.followLabel,
            )
            // A late snapshot still saying "following" does not undo the tap; the host's moves no longer pull.
            push(room(seq = 3, following = true))
            assertNull(vm.state.value.command)
            assertFalse(
                vm.state.value.circle!!
                    .following,
            )
            vm.onIntent(TogetherIntent.Follow)
            runCurrent()
            assertEquals(true, repo.me.last().second)
            assertEquals(2, assertIs<TogetherCommand.GoToPlace>(vm.state.value.command).page)
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun signalsShowOnceAndOnlyWhileYoung() =
        test {
            val (vm, session) = follower(muted = setOf("cleo"))
            val members = listOf(member("aya", 0, host = true), member("ben", 1), member("cleo", 2))
            push(
                room(
                    members = members,
                    signals =
                        listOf(
                            // Older than 10 s: the log only.
                            signal("s1", SignalKind.Say, from = "aya", at = T0 - 30_000, text = "old"),
                            signal("s2", SignalKind.Sticker, from = "aya", sticker = Sticker.Doki),
                            signal("s3", SignalKind.Say, from = "aya", text = "on y va"),
                            // Muted in the margin: hidden.
                            signal("s4", SignalKind.Say, from = "cleo", text = "spoiler"),
                        ),
                ),
            )
            val state = vm.state.value
            assertEquals(listOf("DOKI"), state.bursts.map { it.glyph })
            assertEquals(listOf("on y va"), state.balloons.map { it.text })
            assertEquals(listOf("Aya: old", "Aya: DOKI", "Aya: on y va"), state.circle!!.log.map { it.text })
            assertTrue(session.shown("s4"))
            // The same signals again: nothing new.
            advanceTimeBy(1_700)
            push(room(members = members, signals = listOf(signal("s2", SignalKind.Sticker, from = "aya", sticker = Sticker.Doki))))
            assertTrue(
                vm.state.value.bursts
                    .isEmpty(),
            )
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(
                vm.state.value.balloons
                    .isEmpty(),
            )
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun moreThanSixStickersCoalesce() =
        test {
            val (vm, _) = follower()
            repeat(8) { vm.onIntent(TogetherIntent.SendSticker("don")) }
            runCurrent()
            val bursts = vm.state.value.bursts
            assertEquals(6, bursts.size)
            assertEquals("×3", bursts.last().countLabel)
            assertEquals(8, repo.signals.size)
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun theHostSeesWhoAsksToWait() =
        test {
            val session = session()
            repo.current = room(me = "aya", host = "aya")
            repo.briefs = listOf(RoomBrief("r1", true, "c1", "aya"))
            val vm = viewModel(session)
            vm.onIntent(TogetherIntent.Start)
            runCurrent()
            push(room(me = "aya", host = "aya", signals = listOf(signal("m1", SignalKind.Matte, from = "ben"))))
            assertEquals(
                "Ben: Wait for me!",
                vm.state.value.toast
                    ?.text,
            )
            assertTrue(
                vm.state.value.circle!!
                    .stamps
                    .first { it.userId == "ben" }
                    .asking,
            )
            assertEquals(
                "1 following · 0 on their own",
                vm.state.value.circle!!
                    .hostSummary,
            )
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun followersFollowTheHostToTheNextChapterOthersAreTold() =
        test {
            val (vm, _) = follower()
            push(room(chapter = "c2", seq = 5))
            val open = assertIs<TogetherCommand.OpenChapter>(vm.state.value.command)
            assertEquals("c2", open.chapterId)
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun aReaderOnTheirOwnIsToldTheCircleMovedOn() =
        test {
            val (vm, _) = follower()
            vm.onIntent(TogetherIntent.LetGo)
            runCurrent()
            push(room(chapter = "c2", seq = 5, following = false))
            // Only the place it was given on joining, never the next chapter.
            assertIs<TogetherCommand.GoToPlace>(vm.state.value.command)
            val toast = assertNotNull(vm.state.value.toast)
            assertEquals("Aya moved on to Chapter 3", toast.text)
            vm.onIntent(TogetherIntent.ToastTapped(toast.id))
            assertEquals("c2", assertIs<TogetherCommand.OpenChapter>(vm.state.value.command).chapterId)
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun theHostMovesTheCircleBeforeTheReaderOpensAnotherChapter() =
        test {
            val session = session()
            repo.current = room(me = "aya", host = "aya")
            repo.briefs = listOf(RoomBrief("r1", true, "c1", "aya"))
            val vm = viewModel(session)
            runCurrent()
            vm.onIntent(TogetherIntent.MovingOn("c2"))
            runCurrent()
            assertEquals(listOf("c2"), repo.moved)
            assertEquals(
                "c2",
                session.room.value
                    ?.chapter
                    ?.id,
            )
            // The next reader screen picks the circle up at once.
            val next = viewModel(session, chapterId = "c2")
            runCurrent()
            assertNotNull(next.state.value.circle)
            assertNull(
                next.state.value.circle!!
                    .elsewhere,
            )
        }

    @Test
    fun joiningWithACodeOpensTheRoomsChapter() =
        test {
            repo.joined = room(chapter = "c9")
            val vm = viewModel()
            runCurrent()
            vm.onIntent(TogetherIntent.ShowJoin)
            vm.onIntent(TogetherIntent.CodeChanged("vk3q-9tzm"))
            runCurrent()
            assertEquals(
                "Hosted by Aya",
                vm.state.value.preview
                    ?.hostLine,
            )
            assertEquals(
                "3 of 8 reading",
                vm.state.value.preview
                    ?.presentLine,
            )
            assertTrue(vm.state.value.canJoin)
            vm.onIntent(TogetherIntent.Join)
            runCurrent()
            assertNull(vm.state.value.sheet)
            assertEquals("c9", assertIs<TogetherCommand.OpenChapter>(vm.state.value.command).chapterId)
        }

    @Test
    fun aWrongCodeSaysSoCalmly() =
        test {
            val vm = viewModel()
            runCurrent()
            vm.onIntent(TogetherIntent.CodeChanged("ZZZZ-ZZZZ"))
            runCurrent()
            assertNull(vm.state.value.preview)
            assertEquals("This code doesn't open a circle. Check it, or ask for a new one.", vm.state.value.error)
            assertFalse(vm.state.value.canJoin)
        }

    @Test
    fun linesAreCheckedBeforeTheyGoOut() =
        test {
            val (vm, _) = follower()
            vm.onIntent(TogetherIntent.LineChanged("see https://x.example"))
            vm.onIntent(TogetherIntent.SendLine)
            assertEquals("Lines can't hold links.", vm.state.value.lineError)
            vm.onIntent(TogetherIntent.LineChanged(" on y va "))
            vm.onIntent(TogetherIntent.SendLine)
            runCurrent()
            assertEquals(listOf<Pair<SignalKind, String?>>(SignalKind.Say to "on y va"), repo.signals)
            assertEquals("", vm.state.value.lineDraft)
            assertEquals(
                listOf("on y va"),
                vm.state.value.balloons
                    .map { it.text },
            )
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun awayReadersAreDashed() =
        test {
            val (vm, _) = follower()
            push(room(members = listOf(member("aya", 0, host = true, seenAt = T0), member("ben", 1, seenAt = T0 + 50_000))))
            advanceTimeBy(50_000)
            push(room(members = listOf(member("aya", 0, host = true, seenAt = T0), member("ben", 1, seenAt = T0 + 50_000))))
            val stamps =
                vm.state.value.circle!!
                    .stamps
            assertTrue(stamps.first { it.userId == "aya" }.away)
            assertFalse(stamps.first { it.userId == "ben" }.away)
            assertEquals("Aya, host, away", stamps.first().a11y)
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun aClosedCircleEndsCalmly() =
        test {
            val (vm, session) = follower()
            push(room(open = false))
            assertNull(session.room.value)
            assertNull(vm.state.value.circle)
            assertEquals(
                "The circle has closed.",
                vm.state.value.toast
                    ?.text,
            )
            vm.onIntent(TogetherIntent.Stop)
        }

    @Test
    fun theRailPlacesEveryone() =
        test {
            val (vm, _) = follower()
            push(
                room(
                    place = Place.Comic(4, 0.0),
                    members =
                        listOf(
                            member("aya", 0, host = true),
                            member("ben", 1),
                            member("cleo", 2, following = false, place = Place.Comic(9, 0.0)),
                        ),
                ),
            )
            vm.onIntent(TogetherIntent.PlaceChanged(book = false, page = 2, fraction = 0.5f, panel = -1, section = 0, count = 10))
            push(
                room(
                    place = Place.Comic(4, 0.0),
                    seq = 1,
                    members =
                        listOf(
                            member("aya", 0, host = true),
                            member("ben", 1),
                            member("cleo", 2, following = false, place = Place.Comic(9, 0.0)),
                        ),
                ),
            )
            val rail =
                vm.state.value.circle!!
                    .rail
                    .associate { it.userId to it.share }
            assertEquals(0.4f, rail["aya"])
            assertEquals(0.25f, rail["ben"])
            assertEquals(0.9f, rail["cleo"])
            vm.onIntent(TogetherIntent.Stop)
        }
}
