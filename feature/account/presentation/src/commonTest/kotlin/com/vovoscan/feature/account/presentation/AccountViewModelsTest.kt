package com.vovoscan.feature.account.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.UtcOffset
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.ProviderKey
import com.vovoscan.feature.account.domain.ProviderKeys
import com.vovoscan.feature.account.domain.ProviderKeysRepository
import com.vovoscan.feature.account.domain.ReadingModel
import com.vovoscan.feature.account.domain.ReadingSettingsRepository
import com.vovoscan.feature.account.domain.UnitType
import com.vovoscan.feature.account.domain.Usage
import com.vovoscan.feature.account.domain.UsageEntry
import com.vovoscan.feature.account.domain.UsageRepository
import com.vovoscan.feature.account.domain.UsageTotal
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
class AccountViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val utc = UtcOffset { 0 }

    /** An obvious fake: never a real key in tests. */
    private val secret = "fake-elevenlabs-key-for-tests-only-9876"

    private val anthropic = ProviderKey("k1", Provider.Anthropic, "abcd", "Home", parseIsoMillis("2026-10-20T10:00:00Z")!!, null)

    private class FakeKeys(
        initial: ProviderKeys?,
    ) : ProviderKeysRepository {
        val keys = MutableStateFlow(initial)
        val received = mutableListOf<Triple<Provider, String, String?>>()
        var addResult: AppResult<ProviderKey>? = null
        var refreshResult: AppResult<Unit> = AppResult.Ok(Unit)
        val removed = mutableListOf<String>()

        override fun observeKeys(): Flow<ProviderKeys?> = keys

        override suspend fun refreshKeys() = refreshResult

        override suspend fun add(
            provider: Provider,
            secret: String,
            label: String?,
        ): AppResult<ProviderKey> {
            received += Triple(provider, secret, label)
            return addResult ?: AppResult.Ok(ProviderKey("k9", provider, secret.takeLast(4), label, 0, null)).also { ok ->
                keys.value = keys.value?.let { list -> list.copy(keys = list.keys.filter { it.provider != provider } + ok.value) }
            }
        }

        override suspend fun remove(id: String): AppResult<Unit> {
            removed += id
            keys.value = keys.value?.let { list -> list.copy(keys = list.keys.filter { it.id != id }) }
            return AppResult.Ok(Unit)
        }

        override suspend fun clearLocal() = Unit
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun listsTheKeysByProvider() =
        runTest(dispatcher) {
            val vm = ProviderKeysViewModel(FakeKeys(ProviderKeys(true, listOf(anthropic))), utc)
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state.loaded)
            assertEquals(Freshness.Fresh, state.freshness)
            val (first, second) = state.slots
            assertEquals("•••• abcd", first.key?.hintLabel)
            assertEquals("Added Oct 20, 2026", first.key?.addedLabel)
            assertEquals("Not used yet", first.key?.lastUsedLabel)
            assertEquals("Replace", first.actionLabel)
            assertNull(second.key)
            assertEquals("Add a key", second.actionLabel)
            assertEquals("Voices the lines, sounds and music (VOICE).", second.purpose)
        }

    @Test
    fun theSecretLeavesTheStateTheMomentItIsSent() =
        runTest(dispatcher) {
            val repo = FakeKeys(ProviderKeys(true, listOf(anthropic)))
            val vm = ProviderKeysViewModel(repo, utc)
            advanceUntilIdle()
            vm.onIntent(ProviderKeysIntent.OpenForm(Provider.ElevenLabs))
            vm.onIntent(ProviderKeysIntent.SecretChanged(secret))
            vm.onIntent(ProviderKeysIntent.LabelChanged("Studio"))
            assertTrue(vm.state.value.canSubmit)
            assertEquals("Add your ElevenLabs key", vm.state.value.formTitle)
            assertFalse(
                vm.state.value
                    .toString()
                    .contains(secret),
                "never in the state, even while typing",
            )
            val resets = vm.state.value.secretFieldResets
            vm.onIntent(ProviderKeysIntent.Submit)
            // Sent: the draft and the field are emptied before the answer.
            assertTrue(vm.state.value.submitting)
            assertFalse(vm.state.value.secretEntered)
            assertEquals(resets + 1, vm.state.value.secretFieldResets)
            advanceUntilIdle()
            assertEquals(listOf(Triple<Provider, String, String?>(Provider.ElevenLabs, secret, "Studio")), repo.received)
            val state = vm.state.value
            assertFalse(state.formOpen)
            assertEquals("Key saved.", state.notice)
            assertEquals("•••• 9876", state.slots[1].key?.hintLabel)
            assertFalse(state.toString().contains(secret))
            // Submitting again sends nothing: the secret is gone.
            vm.onIntent(ProviderKeysIntent.OpenForm(Provider.ElevenLabs))
            vm.onIntent(ProviderKeysIntent.Submit)
            advanceUntilIdle()
            assertEquals(1, repo.received.size)
            assertEquals("Paste your key.", vm.state.value.secretError)
        }

    @Test
    fun rejectionsAreCalmAndTheFieldStaysEmpty() =
        runTest(dispatcher) {
            val repo = FakeKeys(ProviderKeys(true, emptyList()))
            val vm = ProviderKeysViewModel(repo, utc)
            advanceUntilIdle()
            val cases =
                mapOf(
                    AppError.Rejected("KEY_REJECTED", "x") to "The provider refused this key. Check it and try again.",
                    AppError.Rejected("PROVIDER_BLOCKED", "x") to
                        "The provider accepts this key but refuses calls from our servers. Check your account with them.",
                    AppError.Rejected("PROVIDER_UNAVAILABLE", "x") to
                        "The provider couldn't check this key right now. Try again in a moment.",
                    AppError.Rejected("BYOK_DISABLED", "x") to "Keys are turned off on this server.",
                    AppError.RateLimited to "Too many keys added in the last hour. Try again later.",
                    AppError.Network to "You're offline. Connect to the internet to change your keys.",
                )
            for ((error, message) in cases) {
                repo.addResult = AppResult.Err(error)
                vm.onIntent(ProviderKeysIntent.OpenForm(Provider.Anthropic))
                vm.onIntent(ProviderKeysIntent.SecretChanged(secret))
                vm.onIntent(ProviderKeysIntent.Submit)
                advanceUntilIdle()
                assertEquals(message, vm.state.value.formError)
                assertFalse(vm.state.value.secretEntered)
                assertTrue(vm.state.value.formOpen)
            }
        }

    @Test
    fun aSecretThatCannotBeAKeyIsNotSent() =
        runTest(dispatcher) {
            val repo = FakeKeys(ProviderKeys(true, emptyList()))
            val vm = ProviderKeysViewModel(repo, utc)
            advanceUntilIdle()
            vm.onIntent(ProviderKeysIntent.OpenForm(Provider.Anthropic))
            vm.onIntent(ProviderKeysIntent.SecretChanged("too short"))
            vm.onIntent(ProviderKeysIntent.LabelChanged("x".repeat(61)))
            vm.onIntent(ProviderKeysIntent.Submit)
            advanceUntilIdle()
            assertEquals("This doesn't look like a key. Check that you copied all of it.", vm.state.value.secretError)
            assertEquals("Keep the label under 60 characters.", vm.state.value.labelError)
            assertTrue(repo.received.isEmpty())
        }

    @Test
    fun keysTurnedOffOnTheServer() =
        runTest(dispatcher) {
            val vm = ProviderKeysViewModel(FakeKeys(ProviderKeys(false, emptyList())), utc)
            advanceUntilIdle()
            assertFalse(vm.state.value.enabled)
            assertEquals("Keys are turned off on this server.", vm.state.value.disabledNotice)
            vm.onIntent(ProviderKeysIntent.OpenForm(Provider.Anthropic))
            assertFalse(vm.state.value.formOpen)
        }

    @Test
    fun removingAsksFirst() =
        runTest(dispatcher) {
            val repo = FakeKeys(ProviderKeys(true, listOf(anthropic)))
            val vm = ProviderKeysViewModel(repo, utc)
            advanceUntilIdle()
            vm.onIntent(ProviderKeysIntent.AskRemove("k1"))
            assertEquals(
                "Remove your Anthropic key?",
                vm.state.value.removal
                    ?.title,
            )
            vm.onIntent(ProviderKeysIntent.CancelRemove)
            assertNull(vm.state.value.removal)
            assertTrue(repo.removed.isEmpty())
            vm.onIntent(ProviderKeysIntent.AskRemove("k1"))
            vm.onIntent(ProviderKeysIntent.ConfirmRemove)
            advanceUntilIdle()
            assertEquals(listOf("k1"), repo.removed)
            assertEquals("Key removed.", vm.state.value.notice)
            assertNull(
                vm.state.value.slots[0]
                    .key,
            )
        }

    @Test
    fun offlineTheListOnTheDeviceStays() =
        runTest(dispatcher) {
            val repo = FakeKeys(ProviderKeys(true, listOf(anthropic))).apply { refreshResult = AppResult.Err(AppError.Network) }
            val vm = ProviderKeysViewModel(repo, utc)
            advanceUntilIdle()
            assertEquals(Freshness.Offline, vm.state.value.freshness)
            assertNull(vm.state.value.error)
            assertEquals(
                "•••• abcd",
                vm.state.value.slots[0]
                    .key
                    ?.hintLabel,
            )
        }

    private class FakeReading : ReadingSettingsRepository {
        val model = MutableStateFlow<ReadingModel?>(null)
        var setResult: AppResult<ReadingModel>? = null

        override fun observeReadingModel(): Flow<ReadingModel?> = model

        override suspend fun refreshReadingModel(): AppResult<Unit> {
            model.value = ReadingModel.Sonnet
            return AppResult.Ok(Unit)
        }

        override suspend fun set(model: ReadingModel): AppResult<ReadingModel> =
            setResult ?: AppResult.Ok(model).also { this.model.value = model }

        val target = MutableStateFlow<String?>(null)
        var targetResult: AppResult<String>? = null

        override fun observeTargetLanguage(): Flow<String?> = target

        override suspend fun setTargetLanguage(code: String): AppResult<String> =
            targetResult ?: AppResult.Ok(code).also { target.value = code }
    }

    @Test
    fun theReadingLanguageIsChosenFromTheSearchablePicker() =
        runTest(dispatcher) {
            val repo = FakeReading()
            val vm = ReadingSettingsViewModel(repo)
            advanceUntilIdle()
            assertEquals("Not chosen yet", vm.state.value.languageLabel)
            vm.onIntent(ReadingSettingsIntent.LanguageQuery("ewe"))
            assertEquals(
                listOf("ee"),
                vm.state.value.languageRows
                    .map { it.code },
            )
            vm.onIntent(ReadingSettingsIntent.ChooseLanguage("ee"))
            advanceUntilIdle()
            assertEquals("ee", repo.target.value)
            assertEquals("Eʋegbe", vm.state.value.languageLabel)
            assertEquals("", vm.state.value.languageQuery)
            assertEquals("Saved.", vm.state.value.notice)
        }

    @Test
    fun anOfflineLanguageChoiceKeepsThePreviousOne() =
        runTest(dispatcher) {
            val repo = FakeReading()
            repo.target.value = "fr"
            repo.targetResult = AppResult.Err(AppError.Network)
            val vm = ReadingSettingsViewModel(repo)
            advanceUntilIdle()
            vm.onIntent(ReadingSettingsIntent.ChooseLanguage("yo"))
            advanceUntilIdle()
            assertEquals("Français", vm.state.value.languageLabel)
            assertTrue(
                vm.state.value.error!!
                    .isNotBlank(),
            )
        }

    @Test
    fun readingModelDefaultsToHaikuThenFollowsTheServer() =
        runTest(dispatcher) {
            val repo = FakeReading()
            val vm = ReadingSettingsViewModel(repo)
            assertEquals(ReadingModel.Haiku, vm.state.value.selected)
            advanceUntilIdle()
            assertEquals(
                listOf("Haiku — fastest, cheapest", "Sonnet — balanced", "Opus — most careful"),
                vm.state.value.options
                    .map { it.label },
            )
            assertEquals(
                ReadingModel.Sonnet,
                vm.state.value.options
                    .single { it.selected }
                    .model,
            )
            vm.onIntent(ReadingSettingsIntent.Select(ReadingModel.Opus))
            assertEquals(
                ReadingModel.Opus,
                vm.state.value.options
                    .single { it.selected }
                    .model,
            )
            advanceUntilIdle()
            assertEquals(ReadingModel.Opus, vm.state.value.selected)
            assertEquals("Model saved.", vm.state.value.notice)
            // Offline: the previous choice stays.
            repo.setResult = AppResult.Err(AppError.Network)
            vm.onIntent(ReadingSettingsIntent.Select(ReadingModel.Haiku))
            advanceUntilIdle()
            assertEquals(ReadingModel.Opus, vm.state.value.selected)
            assertEquals("You're offline. Connect to the internet to change the model.", vm.state.value.error)
        }

    private class FakeUsage : UsageRepository {
        val asked = mutableListOf<Long?>()
        var result: AppResult<Usage> =
            AppResult.Ok(
                Usage(
                    listOf(
                        UsageTotal(Provider.Anthropic, 1.2449, mapOf(UnitType.Requests to 12.0, UnitType.Tokens to 182_400.0)),
                        UsageTotal(Provider.ElevenLabs, 0.004, mapOf(UnitType.Chars to 1.0, UnitType.Seconds to 12.5)),
                    ),
                    listOf(
                        UsageEntry(
                            "u1",
                            Provider.Anthropic,
                            "claude-haiku-5-5",
                            UnitType.Tokens,
                            12_000.0,
                            0.012,
                            "c1",
                            parseIsoMillis("2026-10-20T14:05:00Z")!!,
                        ),
                    ),
                ),
            )

        override suspend fun usage(
            fromMillis: Long?,
            toMillis: Long?,
        ): AppResult<Usage> {
            asked += fromMillis
            return result
        }
    }

    @Test
    fun usageTotalsAndPeriods() =
        runTest(dispatcher) {
            val repo = FakeUsage()
            val now = parseIsoMillis("2026-10-21T09:30:00Z")!!
            val paris = UtcOffset { 2 * 3_600_000L }
            val vm = UsageViewModel(repo, { now }, paris)
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(now - 30 * 86_400_000L, repo.asked.single())
            assertEquals("$1.24", state.totals[0].costLabel)
            assertEquals("182,400 tokens ✦ 12 requests", state.totals[0].unitsLabel)
            assertEquals("under $0.01", state.totals[1].costLabel)
            assertEquals("1 character ✦ 12.5 seconds", state.totals[1].unitsLabel)
            assertEquals("$1.25", state.totalCostLabel)
            assertEquals("12,000 tokens", state.entries.single().amountLabel)
            // The platform writes the time (12-hour in English, with the locale's own space before PM).
            val dateLabel = state.entries.single().dateLabel
            assertTrue(dateLabel.startsWith("Oct 20, 2026 ✦ 4:05") && dateLabel.endsWith("PM"), dateLabel)
            assertEquals(listOf("Last 7 days", "Last 30 days", "This month"), state.periods.map { it.label })
            vm.onIntent(UsageIntent.SelectPeriod(UsagePeriod.ThisMonth))
            advanceUntilIdle()
            // October 1st, midnight in Paris.
            assertEquals(parseIsoMillis("2026-09-30T22:00:00Z"), repo.asked.last())
            repo.result = AppResult.Err(AppError.Network)
            vm.onIntent(UsageIntent.Refresh)
            advanceUntilIdle()
            assertEquals("You're offline. Costs show once you're back online.", vm.state.value.error)
            assertFalse(vm.state.value.empty)
        }
}
