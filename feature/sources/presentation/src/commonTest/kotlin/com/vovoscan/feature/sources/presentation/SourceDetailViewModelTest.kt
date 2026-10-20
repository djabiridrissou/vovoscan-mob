package com.vovoscan.feature.sources.presentation

import com.vovoscan.feature.sources.domain.SettingField
import com.vovoscan.feature.sources.domain.SettingType
import com.vovoscan.feature.sources.domain.SourceUpdate
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SourceDetailViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun officialSourceIsReadOnly() =
        runTest {
            val fake = FakeSources()
            val vm = SourceDetailViewModel("gutenberg", fake, fake)
            val state = vm.state.value
            assertEquals("Official", state.info?.originLabel)
            assertNull(state.info?.rightsDisclaimer)
            assertTrue(!state.canRemove && !state.settingsEditable)
            assertEquals("Show", state.visibilityLabel)
            vm.onIntent(SourceDetailIntent.Remove)
            assertTrue(!vm.state.value.confirmingRemoval)
            vm.onIntent(SourceDetailIntent.ToggleVisibility)
            assertEquals("Hide", vm.state.value.visibilityLabel)
        }

    @Test
    fun thirdPartySettingsAreValidatedAndSaved() =
        runTest {
            val fake = FakeSources()
            fake.items.value += FakeSources.thirdParty()
            val vm = SourceDetailViewModel("demo", fake, fake)
            val state = vm.state.value
            assertEquals("Demo plugins", state.info?.originLabel)
            assertEquals("Claimed by the author, not checked by vovoscan", state.info?.rightsDisclaimer)
            assertEquals("Licensed", state.info?.rightsLabel)
            assertTrue(state.settingsEditable && state.canRemove && !state.canSaveSettings)
            assertEquals("https://one.example", state.settings.single().value)

            vm.onIntent(SourceDetailIntent.SettingChanged("baseUrl", "ftp://nope"))
            assertTrue(vm.state.value.canSaveSettings)
            vm.onIntent(SourceDetailIntent.SaveSettings)
            assertEquals(
                "Use an https:// address.",
                vm.state.value.settings
                    .single()
                    .error,
            )
            vm.onIntent(SourceDetailIntent.SettingChanged("baseUrl", "https://two.example"))
            vm.onIntent(SourceDetailIntent.SaveSettings)
            assertEquals("Settings saved.", vm.state.value.notice)
            assertEquals(
                "https://two.example",
                fake.items.value
                    .first { it.key == "demo" }
                    .settings["baseUrl"],
            )
            assertTrue(!vm.state.value.canSaveSettings)
        }

    @Test
    fun updateConfirmsNewHostsThenAsksForNewSettings() =
        runTest {
            val fake = FakeSources()
            fake.items.value += FakeSources.thirdParty(update = SourceUpdate("2.0.0", "Faster.", listOf("cdn.example")))
            val token = SettingField("token", "Access code", SettingType.Text, required = true, defaultValue = null, help = null)
            fake.candidates["demo"] =
                FakeSources.candidate(
                    version = "2.0.0",
                    settings = listOf(FakeSources.baseUrl, token),
                    installedVersion = "1.0.0",
                    newHosts = listOf("cdn.example"),
                )
            val vm = SourceDetailViewModel("demo", fake, fake)
            val update = assertNotNull(vm.state.value.update)
            assertEquals("Version 2.0.0", update.versionLabel)
            assertEquals("This version also connects to: cdn.example", update.newHostsLabel)

            vm.onIntent(SourceDetailIntent.Update)
            assertEquals("This version also connects to: cdn.example", vm.state.value.hostsConfirmation)
            vm.onIntent(SourceDetailIntent.ConfirmHosts)
            assertNull(vm.state.value.hostsConfirmation)
            assertTrue(fake.installs.isEmpty())
            assertEquals("Fill in the new settings to finish the update.", vm.state.value.settingsNotice)
            assertEquals(
                listOf("baseUrl", "token"),
                vm.state.value.settings
                    .map { it.name },
            )

            vm.onIntent(SourceDetailIntent.SaveSettings)
            assertEquals(
                "Required.",
                vm.state.value.settings
                    .last()
                    .error,
            )
            vm.onIntent(SourceDetailIntent.SettingChanged("token", "abc"))
            vm.onIntent(SourceDetailIntent.SaveSettings)
            assertEquals(listOf("demo" to mapOf("token" to "abc")), fake.installs)
            assertEquals("Demo demo updated.", vm.state.value.notice)
        }

    @Test
    fun removalNeedsConfirmation() =
        runTest {
            val fake = FakeSources()
            fake.items.value += FakeSources.thirdParty()
            val vm = SourceDetailViewModel("demo", fake, fake)
            vm.onIntent(SourceDetailIntent.Remove)
            assertTrue(vm.state.value.confirmingRemoval)
            assertEquals("Remove Demo demo?", vm.state.value.removalTitle)
            assertEquals("Titles you follow stay in your library.", vm.state.value.removalMessage)
            vm.onIntent(SourceDetailIntent.CancelRemove)
            assertTrue(fake.removedKeys.isEmpty())
            vm.onIntent(SourceDetailIntent.Remove)
            vm.onIntent(SourceDetailIntent.ConfirmRemove)
            assertEquals(listOf("demo"), fake.removedKeys)
            assertTrue(vm.state.value.gone)
        }
}
