package com.vovoscan.feature.auth.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private class FakeAuth(
        var next: AppResult<Session>,
    ) : AuthRepository {
        var calls = 0
        override val session: StateFlow<Session?> = MutableStateFlow(null)

        override suspend fun restore() = Unit

        override suspend fun signIn(
            email: String,
            password: String,
        ) = next.also { calls++ }

        override suspend fun redeemInvite(
            code: String,
            email: String,
            password: String,
            displayName: String,
        ) = next.also { calls++ }

        override suspend fun signOut() = Unit
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun saisieInvalideNAppellePasLeServeur() =
        runTest {
            val auth = FakeAuth(AppResult.Err(AppError.Network))
            val vm = SignInViewModel(auth)
            vm.onIntent(SignInIntent.Email("nope"))
            vm.onIntent(SignInIntent.Submit)
            assertNotNull(vm.state.value.emailError)
            assertEquals(0, auth.calls)
        }

    @Test
    fun refusDuServeurAfficheSonMessage() =
        runTest {
            val vm = SignInViewModel(FakeAuth(AppResult.Err(AppError.Rejected("INVITE_INVALID", "Invite already used."))))
            vm.onIntent(SignInIntent.SwitchMode)
            vm.onIntent(SignInIntent.Email("r@v.app"))
            vm.onIntent(SignInIntent.Password("a-long-password"))
            vm.onIntent(SignInIntent.InviteCode("vovo-7k2d-qx9p"))
            vm.onIntent(SignInIntent.Submit)
            assertEquals("Invite already used.", vm.state.value.error)
        }

    @Test
    fun succesEffaceLeMotDePasse() =
        runTest {
            val vm = SignInViewModel(FakeAuth(AppResult.Ok(Session("u1", "r@v.app", "R", emptyList(), emptySet()))))
            vm.onIntent(SignInIntent.Email("r@v.app"))
            vm.onIntent(SignInIntent.Password("whatever"))
            vm.onIntent(SignInIntent.Submit)
            assertEquals("", vm.state.value.password)
            assertNull(vm.state.value.error)
        }
}
