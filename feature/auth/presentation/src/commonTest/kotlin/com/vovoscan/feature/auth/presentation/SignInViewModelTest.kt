package com.vovoscan.feature.auth.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.DeviceLocale
import com.vovoscan.core.common.i18n.DevicePreferences
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.UiLanguage
import com.vovoscan.feature.auth.domain.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val language = UiLanguage(DevicePreferences.InMemory()) { listOf("en-US") }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun invalidInputDoesNotCallTheServer() =
        runTest {
            val auth = FakeAuth(AppResult.Err(AppError.Network))
            val vm = SignInViewModel(auth, language)
            vm.onIntent(SignInIntent.Email("nope"))
            vm.onIntent(SignInIntent.Submit)
            assertNotNull(vm.state.value.emailError)
            assertEquals(0, auth.calls)
        }

    @Test
    fun serverRejectionShowsItsMessage() =
        runTest {
            val vm = SignInViewModel(FakeAuth(AppResult.Err(AppError.Rejected("INVITE_INVALID", "Invite already used."))), language)
            vm.onIntent(SignInIntent.SwitchMode)
            vm.onIntent(SignInIntent.Email("r@v.app"))
            vm.onIntent(SignInIntent.Password("a-long-password"))
            vm.onIntent(SignInIntent.InviteCode("vovo-7k2d-qx9p"))
            vm.onIntent(SignInIntent.AgeConfirmed(true))
            vm.onIntent(SignInIntent.Submit)
            assertEquals("Invite already used.", vm.state.value.error)
        }

    @Test
    fun successClearsThePassword() =
        runTest {
            val vm = SignInViewModel(FakeAuth(AppResult.Ok(Session("u1", "r@v.app", "R", emptyList(), emptySet()))), language)
            vm.onIntent(SignInIntent.Email("r@v.app"))
            vm.onIntent(SignInIntent.Password("whatever"))
            vm.onIntent(SignInIntent.Submit)
            assertEquals("", vm.state.value.password)
            assertNull(vm.state.value.error)
        }

    @Test
    fun aSignUpNeedsTheAgeBoxAndSendsTheDevicesLanguage() =
        runTest {
            I18n.use("fr", "FR")
            try {
                val auth = FakeAuth(AppResult.Ok(Session("u1", "r@v.app", "R", emptyList(), emptySet())))
                val vm = SignInViewModel(auth, language)
                vm.onIntent(SignInIntent.SwitchMode)
                assertEquals("J’ai 15 ans ou plus.", vm.state.value.ageLabel)
                vm.onIntent(SignInIntent.Email("r@v.app"))
                vm.onIntent(SignInIntent.Password("a-long-password"))
                vm.onIntent(SignInIntent.InviteCode("VOVO-7K2D-QX9P"))
                vm.onIntent(SignInIntent.Submit)
                assertNotNull(vm.state.value.ageError)
                assertEquals(0, auth.calls)
                vm.onIntent(SignInIntent.AgeConfirmed(true))
                vm.onIntent(SignInIntent.Submit)
                assertEquals(listOf<Any?>("VOVO-7K2D-QX9P", "fr", "FR", DeviceLocale.timeZone(), true), auth.redeemed)
            } finally {
                I18n.use("en", null)
            }
        }

    @Test
    fun theLanguageCanBeChosenBeforeSigningIn() =
        runTest {
            val vm = SignInViewModel(FakeAuth(), language)
            assertEquals(
                listOf("English", "Français"),
                vm.state.value.locales
                    .map { it.label },
            )
            vm.onIntent(SignInIntent.ChooseLocale("fr"))
            try {
                assertEquals("fr", I18n.locale.value)
                assertEquals(
                    "fr",
                    vm.state.value.locales
                        .single { it.selected }
                        .code,
                )
            } finally {
                I18n.use("en", null)
            }
        }
}
