package com.vovoscan.feature.account.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.account.domain.Wallet
import com.vovoscan.feature.account.domain.WalletRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
class WalletViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun balanceWithThousandsAndNoWarning() =
        runTest(dispatcher) {
            val vm = WalletViewModel(FakeWallet(AppResult.Ok(Wallet(1240, usesOwnKeys = false, dailyCapReached = false))))
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state.shown)
            assertEquals("1,240", state.balanceLabel)
            assertEquals("credits", state.unitLabel)
            assertNull(state.warning)
        }

    @Test
    fun emptyAndCappedSayWhyUnlessOwnKeysPay() =
        runTest(dispatcher) {
            val repo = FakeWallet(AppResult.Ok(Wallet(0, usesOwnKeys = false, dailyCapReached = false)))
            val vm = WalletViewModel(repo)
            advanceUntilIdle()
            assertTrue(vm.state.value.empty)
            assertEquals(WalletText.EMPTY, vm.state.value.warning)
            repo.next = AppResult.Ok(Wallet(50, usesOwnKeys = false, dailyCapReached = true))
            vm.onIntent(WalletIntent.Refresh)
            advanceUntilIdle()
            assertEquals(WalletText.DAILY_CAP, vm.state.value.warning)
            repo.next = AppResult.Ok(Wallet(0, usesOwnKeys = true, dailyCapReached = true))
            vm.onIntent(WalletIntent.Refresh)
            advanceUntilIdle()
            assertFalse(vm.state.value.empty)
            assertNull(vm.state.value.warning)
            assertEquals(WalletText.OWN_KEYS, vm.state.value.explainer)
        }

    @Test
    fun aServerWithoutWalletShowsNothing() =
        runTest(dispatcher) {
            val vm = WalletViewModel(FakeWallet(AppResult.Ok(null)))
            advanceUntilIdle()
            assertFalse(vm.state.value.shown)
            assertNull(vm.state.value.error)
        }

    @Test
    fun offlineKeepsQuiet() =
        runTest(dispatcher) {
            val vm = WalletViewModel(FakeWallet(AppResult.Err(AppError.Network)))
            advanceUntilIdle()
            assertTrue(vm.state.value.offline)
            assertNull(vm.state.value.error)
        }
}

private class FakeWallet(
    var next: AppResult<Wallet?>,
) : WalletRepository {
    private val current = MutableStateFlow<Wallet?>(null)

    override fun observeWallet(): Flow<Wallet?> = current

    override suspend fun refreshWallet(): AppResult<Wallet?> = next.also { if (it is AppResult.Ok) current.value = it.value }

    override suspend fun quote(
        kind: com.vovoscan.feature.account.domain.QuoteKind,
        chapterId: String,
        lang: String?,
    ): AppResult<com.vovoscan.feature.account.domain.Quote?> = AppResult.Ok(null)

    override fun clear() {
        current.value = null
    }
}
