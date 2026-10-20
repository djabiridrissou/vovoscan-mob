package com.vovoscan.feature.sources.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.sources.domain.EntryStatus
import com.vovoscan.feature.sources.domain.PluginLookup
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
class AddSourceViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun singleSourceAsksForRequiredSettingsBeforeInstall() =
        runTest {
            val fake = FakeSources()
            fake.lookup = AppResult.Ok(PluginLookup.Single(FakeSources.candidate(settings = listOf(FakeSources.baseUrl))))
            val vm = AddSourceViewModel(fake, fake)
            assertTrue(!vm.state.value.canSubmit)
            vm.onIntent(AddSourceIntent.LinkChanged("https://demo.example/source.json"))
            vm.onIntent(AddSourceIntent.SubmitLink)
            val state = vm.state.value
            assertEquals(AddSourceStep.Preview, state.step)
            assertEquals("vovoscan doesn't review third-party sources. You're responsible for what you read with them.", state.warning)
            val preview = assertNotNull(state.preview)
            assertEquals("Install", preview.actionLabel)
            assertEquals("Claimed by the author, not checked by vovoscan", preview.info.rightsDisclaimer)
            assertEquals("This source connects your phone to: demo.example", preview.info.hostsLabel)
            assertEquals("EN · FR", preview.info.langsLabel)
            assertEquals("Demo plugins", preview.info.originLabel)

            vm.onIntent(AddSourceIntent.Install)
            assertTrue(vm.state.value.showsSettingsForm)
            assertTrue(fake.installs.isEmpty())
            vm.onIntent(AddSourceIntent.ConfirmSettings)
            assertEquals(
                "Required.",
                vm.state.value.settingsForm
                    .single()
                    .error,
            )
            vm.onIntent(AddSourceIntent.SettingChanged("baseUrl", "http://plain.example"))
            vm.onIntent(AddSourceIntent.ConfirmSettings)
            assertEquals(
                "Use an https:// address.",
                vm.state.value.settingsForm
                    .single()
                    .error,
            )
            vm.onIntent(AddSourceIntent.SettingChanged("baseUrl", "https://mine.example"))
            vm.onIntent(AddSourceIntent.ConfirmSettings)
            assertEquals(listOf("demo" to mapOf("baseUrl" to "https://mine.example")), fake.installs)
            assertEquals("Demo demo installed.", vm.state.value.notice)
            assertEquals("demo", vm.state.value.installedKey)
            assertEquals(
                "Installed",
                vm.state.value.preview
                    ?.statusLabel,
            )
            assertNull(
                vm.state.value.preview
                    ?.actionLabel,
            )
            assertTrue(!vm.state.value.showsSettingsForm)
        }

    @Test
    fun repositoryListingInstallsAndUpdatesWithHostConfirmation() =
        runTest {
            val fake = FakeSources()
            fake.items.value += FakeSources.thirdParty(key = "old")
            fake.lookup =
                AppResult.Ok(
                    PluginLookup.Repository(
                        FakeSources.listing(
                            FakeSources.entry("demo", EntryStatus.Install),
                            FakeSources.entry("old", EntryStatus.Update, installedVersion = "1.0.0", version = "2.0.0"),
                            FakeSources.entry("gutenberg", EntryStatus.Unavailable),
                        ),
                    ),
                )
            fake.candidates["demo"] = FakeSources.candidate()
            fake.candidates["old"] =
                FakeSources.candidate(key = "old", version = "2.0.0", installedVersion = "1.0.0", newHosts = listOf("cdn.example"))
            val vm = AddSourceViewModel(fake, fake)
            vm.onIntent(AddSourceIntent.LinkChanged("https://github.com/demo/plugins"))
            vm.onIntent(AddSourceIntent.SubmitLink)
            val listing = assertNotNull(vm.state.value.repository)
            assertEquals(AddSourceStep.Repository, vm.state.value.step)
            assertEquals(listOf("Install", "Update", null), listing.entries.map { it.actionLabel })
            assertEquals("3 sources", listing.countLabel)

            vm.onIntent(AddSourceIntent.InstallEntry("demo"))
            assertEquals(
                EntryStatus.Installed,
                vm.state.value.repository!!
                    .entries[0]
                    .status,
            )

            vm.onIntent(AddSourceIntent.InstallEntry("old"))
            assertEquals("This version also connects to: cdn.example", vm.state.value.hostsConfirmation)
            assertEquals(listOf("demo"), fake.installs.map { it.first })
            vm.onIntent(AddSourceIntent.ConfirmHosts)
            assertEquals(listOf("demo", "old"), fake.installs.map { it.first })
            assertEquals("Demo old updated.", vm.state.value.notice)
            assertEquals(
                EntryStatus.Installed,
                vm.state.value.repository!!
                    .entries[1]
                    .status,
            )
        }

    @Test
    fun cancelledHostsDoNotUpdateAndBlockersAreShown() =
        runTest {
            val fake = FakeSources()
            fake.items.value += FakeSources.thirdParty(key = "old")
            fake.lookup =
                AppResult.Ok(
                    PluginLookup.Single(
                        FakeSources.candidate(key = "old", version = "2.0.0", installedVersion = "1.0.0", newHosts = listOf("x.example")),
                    ),
                )
            val vm = AddSourceViewModel(fake, fake)
            vm.onIntent(AddSourceIntent.ImportFile(byteArrayOf(1), "old-2.0.0.vovo"))
            assertEquals(
                "Update",
                vm.state.value.preview
                    ?.actionLabel,
            )
            assertEquals(
                "This version also connects to: x.example",
                vm.state.value.preview
                    ?.newHostsLabel,
            )
            vm.onIntent(AddSourceIntent.Install)
            vm.onIntent(AddSourceIntent.CancelHosts)
            assertNull(vm.state.value.hostsConfirmation)
            assertTrue(fake.installs.isEmpty())

            fake.lookup =
                AppResult.Ok(
                    PluginLookup.Single(FakeSources.candidate(key = "gutenberg", blocker = "Already available as an official source.")),
                )
            vm.onIntent(AddSourceIntent.Reset)
            assertEquals(AddSourceStep.Input, vm.state.value.step)
            vm.onIntent(AddSourceIntent.LinkChanged("https://demo.example/gutenberg.json"))
            vm.onIntent(AddSourceIntent.SubmitLink)
            assertEquals(
                "Already available as an official source.",
                vm.state.value.preview
                    ?.statusLabel,
            )
            assertNull(
                vm.state.value.preview
                    ?.actionLabel,
            )
        }

    @Test
    fun networkErrorsReturnToTheInput() =
        runTest {
            val fake = FakeSources()
            val vm = AddSourceViewModel(fake, fake)
            vm.onIntent(AddSourceIntent.LinkChanged("https://demo.example/repo.json"))
            vm.onIntent(AddSourceIntent.SubmitLink)
            assertEquals(AddSourceStep.Input, vm.state.value.step)
            assertEquals("No connection. Showing what's saved on this device.", vm.state.value.error)
        }
}
