package com.vovoscan.feature.together.presentation

import com.vovoscan.feature.together.domain.Device
import com.vovoscan.feature.together.domain.ThisDevice
import com.vovoscan.feature.together.domain.TogetherAccess
import com.vovoscan.feature.together.domain.TogetherLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TogetherInviteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeTogetherRepository()
    private val session = TogetherSession(repo, CoroutineScope(dispatcher))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun invite(
        link: String,
        on: Boolean = true,
    ) = TogetherInviteViewModel(link, repo, session, TogetherAccess { flowOf(on) }, TogetherLanguage { null }, ThisDevice(Device.Ios))

    @Test
    fun aLinkShowsItsCircleThenJoinsIt() =
        runTest {
            repo.joined = room(chapter = "c9")
            val vm = invite("vovoscan://together/VK3Q9TZM")
            runCurrent()
            assertEquals(
                "Ink Lanterns ✦ Chapter 3",
                vm.state.value.preview
                    ?.chapterLine,
            )
            assertTrue(vm.state.value.canJoin)
            vm.onIntent(InviteIntent.Join)
            runCurrent()
            assertEquals(JoinedChapter("c9", "t1"), vm.state.value.joined)
            assertEquals(
                "c9",
                session.room.value
                    ?.chapter
                    ?.id,
            )
            vm.onIntent(InviteIntent.Opened)
            assertNull(vm.state.value.joined)
        }

    @Test
    fun aBadLinkOrAnAccountWithoutCirclesSaysSo() =
        runTest {
            val bad = invite("vovoscan://together/nope")
            val off = invite("vovoscan://together/VK3Q9TZM", on = false)
            val gone = invite("https://example.org/together/ZZZZ-ZZZZ")
            runCurrent()
            assertEquals("This link no longer opens a circle. Ask for a new one.", bad.state.value.error)
            assertEquals("Reading circles aren't open to this account yet.", off.state.value.error)
            assertEquals("This link no longer opens a circle. Ask for a new one.", gone.state.value.error)
            assertFalse(gone.state.value.canJoin)
        }
}
