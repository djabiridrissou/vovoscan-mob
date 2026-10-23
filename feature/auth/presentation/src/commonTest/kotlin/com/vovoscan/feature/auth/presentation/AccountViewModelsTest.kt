package com.vovoscan.feature.auth.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.i18n.DevicePreferences
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.UiLanguage
import com.vovoscan.feature.auth.domain.Change
import com.vovoscan.feature.auth.domain.OnboardingStep
import com.vovoscan.feature.auth.domain.OnboardingTitle
import com.vovoscan.feature.auth.domain.ProfileChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private val prefs = DevicePreferences.InMemory()
    private val language = UiLanguage(prefs) { listOf("en-US") }
    private val clock = Clock { 1_792_677_780_000L } // 2026-10-22T14:03:00Z

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        I18n.use("en", null)
    }

    // Account.

    @Test
    fun theNameIsSavedOnlyWhenItChanged() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = AccountViewModel(auth, auth)
            assertFalse(vm.state.value.canSaveName)
            vm.onIntent(AccountIntent.Name("  Idriss "))
            vm.onIntent(AccountIntent.SaveName)
            assertEquals(listOf(ProfileChange(displayName = "Idriss")), auth.changes)
            assertEquals("Idriss", vm.state.value.name)
            assertEquals("Saved.", vm.state.value.notice)
        }

    @Test
    fun devicesShowTheCurrentOneAndSignOutTheOthers() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = AccountViewModel(auth, auth)
            val rows = vm.state.value.devices
            assertEquals(listOf("iPhone", "Mac · Chrome"), rows.map { it.title })
            assertTrue(rows[0].detail.startsWith("This device ✦ Active "))
            assertTrue(rows[1].detail.startsWith("Browser ✦ Network 203.0.113.0 ✦ Active "))
            vm.onIntent(AccountIntent.SignOutOthers)
            assertEquals(
                listOf("me"),
                vm.state.value.devices
                    .map { it.id },
            )
            assertEquals("1 device signed out.", vm.state.value.notice)
            assertFalse(vm.state.value.hasOtherDevices)
        }

    @Test
    fun theCurrentDeviceIsNeverSignedOutFromTheList() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = AccountViewModel(auth, auth)
            vm.onIntent(AccountIntent.SignOutDevice("me"))
            assertEquals(2, auth.devices.size)
        }

    @Test
    fun aWrongPasswordIsShownAndTheFieldsAreEmptied() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            auth.accountError = AppError.Rejected("WRONG_PASSWORD", "Wrong password.")
            val vm = AccountViewModel(auth, auth)
            val resets = vm.state.value.secretFieldResets
            vm.onIntent(AccountIntent.SubmitPassword("old-password", "a-new-password-1"))
            assertEquals("Wrong password.", vm.state.value.error)
            assertEquals(resets + 1, vm.state.value.secretFieldResets)
            assertNull(vm.state.value.passwordDone)
        }

    @Test
    fun aShortNewPasswordNeverReachesTheServer() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = AccountViewModel(auth, auth)
            vm.onIntent(AccountIntent.SubmitPassword("old-password", "short"))
            assertTrue(auth.passwords.isEmpty())
            assertTrue(
                vm.state.value.error!!
                    .isNotBlank(),
            )
        }

    @Test
    fun anEmailChangeSaysWhereTheLinkWent() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = AccountViewModel(auth, auth)
            vm.onIntent(AccountIntent.OpenEmailForm)
            vm.onIntent(AccountIntent.NewEmail("new@v.app"))
            vm.onIntent(AccountIntent.SubmitEmail("my-password-12"))
            assertEquals("Check new@v.app: open the link we sent to confirm. Nothing changes until then.", vm.state.value.emailSent)
            assertFalse(vm.state.value.emailFormOpen)
        }

    @Test
    fun deletionIsScheduledThenKept() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = AccountViewModel(auth, auth)
            vm.onIntent(AccountIntent.Delete("my-password-12"))
            assertTrue(
                vm.state.value.deletionScheduled!!
                    .startsWith("Your account will be deleted on November 2"),
            )
            vm.onIntent(AccountIntent.KeepAccount)
            assertNull(vm.state.value.deletionScheduled)
        }

    @Test
    fun theOwnerAccountCannotBeDeleted() =
        runTest {
            val auth = FakeAuth(signedIn = testSession().copy(isSuperAdmin = true))
            val vm = AccountViewModel(auth, auth)
            vm.onIntent(AccountIntent.Delete("my-password-12"))
            assertTrue(auth.passwords.isEmpty())
            assertEquals("The owner account can’t be deleted.", vm.state.value.ownerNote)
        }

    @Test
    fun offlineAccountWritesSaySo() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            auth.accountError = AppError.Network
            val vm = AccountViewModel(auth, auth)
            assertEquals("You’re offline. This needs the network.", vm.state.value.devicesError)
        }

    // Session.

    @Test
    fun aScheduledDeletionShowsTheBannerUntilKept() =
        runTest {
            val auth = FakeAuth(signedIn = testSession().copy(deletionScheduledAtMillis = FakeAuth.DELETION_AT))
            val vm = SessionViewModel(auth, auth)
            assertTrue(
                vm.deletionBanner.value!!
                    .text
                    .startsWith("Your account will be deleted on November 2"),
            )
            assertEquals("Keep my account", vm.deletionBanner.value!!.action)
            vm.keepAccount()
            assertNull(vm.deletionBanner.value)
        }

    @Test
    fun theTutorialShowsWhenDueOrReplayed() =
        runTest {
            val auth = FakeAuth(signedIn = testSession(onboardingDue = true))
            val vm = SessionViewModel(auth, auth)
            assertTrue(vm.tutorial.value.shown)
            auth.updateProfile(ProfileChange(onboardingCompleted = true))
            assertFalse(vm.tutorial.value.shown)
            vm.replayTutorial()
            assertTrue(vm.tutorial.value.shown)
            vm.tutorialClosed()
            assertFalse(vm.tutorial.value.shown)
        }

    // Preferences.

    @Test
    fun safeModeIsOnByDefaultAndTurnsOffOnlyAfterTheAgeIsConfirmed() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = PreferencesViewModel(auth, auth, language, clock)
            assertTrue(vm.state.value.safeMode)
            // Off asks first; cancelling changes nothing.
            vm.onIntent(PreferencesIntent.SetSafeMode(false))
            assertTrue(vm.state.value.confirmingAdult)
            vm.onIntent(PreferencesIntent.CancelAdult)
            assertTrue(auth.showAdultCalls.isEmpty())
            assertTrue(vm.state.value.safeMode)
            vm.onIntent(PreferencesIntent.SetSafeMode(false))
            vm.onIntent(PreferencesIntent.ConfirmAdult)
            advanceUntilIdle()
            assertEquals(listOf(true), auth.showAdultCalls)
            assertFalse(vm.state.value.safeMode)
            // Back on: at once, no question.
            vm.onIntent(PreferencesIntent.SetSafeMode(true))
            advanceUntilIdle()
            assertEquals(listOf(true, false), auth.showAdultCalls)
            assertTrue(vm.state.value.safeMode)
        }

    @Test
    fun choosingTheLanguageAppliesAtOnceAndSavesOnTheAccount() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = PreferencesViewModel(auth, auth, language, clock)
            vm.onIntent(PreferencesIntent.ChooseLocale("fr"))
            assertEquals("fr", I18n.locale.value)
            assertEquals("fr", prefs.get("ui_locale"))
            assertEquals(listOf(ProfileChange(locale = "fr")), auth.changes)
            assertEquals("Langue et région", vm.state.value.language.title)
        }

    @Test
    fun theRegionIsSearchedAndShownInTheSample() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = PreferencesViewModel(auth, auth, language, clock)
            vm.onIntent(PreferencesIntent.RegionQuery("tog"))
            assertEquals(
                listOf("TG"),
                vm.state.value.language.regions
                    .map { it.code },
            )
            vm.onIntent(PreferencesIntent.ChooseRegion("TG"))
            assertEquals("en-TG", I18n.formattingTag)
            assertEquals(ProfileChange(region = Change.Set("TG")), auth.changes.last())
            assertEquals("Togo", vm.state.value.language.regionLabel)
            assertTrue(
                vm.state.value.language.preview
                    .startsWith("Preview: "),
            )
            assertTrue(vm.state.value.language.regions.size > 200)
            vm.onIntent(PreferencesIntent.ChooseRegion(null))
            assertEquals(ProfileChange(region = Change.Set(null)), auth.changes.last())
            assertEquals("Not set", vm.state.value.language.regionLabel)
        }

    // Tutorial.

    @Test
    fun theTutorialWalksItsSixStepsAndSavesEach() =
        runTest {
            val auth = FakeAuth(signedIn = testSession(onboardingDue = true, timeZone = null))
            val services = FakeOnboarding()
            val vm = OnboardingViewModel(auth, services, language, prefs, clock)
            // The device's time zone, the first time.
            assertTrue((auth.changes.first().timeZone as Change.Set).value!!.isNotBlank())
            assertEquals(OnboardingStep.Welcome, vm.state.value.step)
            assertNull(vm.state.value.progressLabel)
            assertFalse(vm.state.value.canGoBack)
            vm.onIntent(OnboardingIntent.Next)
            assertEquals("Step 1 of 4", vm.state.value.progressLabel)
            assertTrue(vm.state.value.canGoBack)
            vm.onIntent(OnboardingIntent.ChooseLocale("fr"))
            assertEquals("Étape 1 sur 4", vm.state.value.progressLabel)
            assertEquals(ProfileChange(locale = "fr"), auth.changes.last())
            vm.onIntent(OnboardingIntent.Next)
            // Reading: pre-filled with the UI language.
            assertEquals(OnboardingStep.Reading, vm.state.value.step)
            assertEquals("Français", vm.state.value.readingChoice)
            vm.onIntent(OnboardingIntent.ChooseReading("ee"))
            vm.onIntent(OnboardingIntent.Next)
            assertEquals("ee", services.reading.value)
            assertEquals(OnboardingStep.Sources, vm.state.value.step)
            assertEquals("Aucune source activée pour l’instant.", vm.state.value.sourcesCount)
            assertTrue(vm.state.value.canTurnOnOfficial)
            vm.onIntent(OnboardingIntent.TurnOnOfficial)
            assertEquals("Activée", vm.state.value.officialAction)
            assertEquals("1 source activée.", vm.state.value.sourcesCount)
            vm.onIntent(OnboardingIntent.Next)
            assertEquals(OnboardingStep.Credits, vm.state.value.step)
            assertEquals("Solde\u202F: 120 crédits", vm.state.value.creditsStatus)
            assertEquals(
                true,
                vm.state.value.creditsOwn
                    .first()
                    .bold,
            )
            vm.onIntent(OnboardingIntent.Next)
            assertEquals(OnboardingStep.Done, vm.state.value.step)
            assertFalse(vm.state.value.showSkip)
            vm.onIntent(OnboardingIntent.Next)
            assertTrue(vm.state.value.finished)
            assertEquals(ProfileChange(onboardingCompleted = true), auth.changes.last())
            assertFalse(auth.current.value!!.onboardingDue)
            assertNull(prefs.get("onboarding_step"))
        }

    @Test
    fun theStepReachedSurvivesARebuild() =
        runTest {
            val auth = FakeAuth(signedIn = testSession(onboardingDue = true))
            val first = OnboardingViewModel(auth, FakeOnboarding(), language, prefs, clock)
            first.onIntent(OnboardingIntent.Next)
            first.onIntent(OnboardingIntent.Next)
            val rebuilt = OnboardingViewModel(auth, FakeOnboarding(), language, prefs, clock)
            assertEquals(OnboardingStep.Reading, rebuilt.state.value.step)
            assertEquals("en", rebuilt.state.value.readingLanguage)
        }

    @Test
    fun skippingCompletesTheTutorial() =
        runTest {
            val auth = FakeAuth(signedIn = testSession(onboardingDue = true))
            val vm = OnboardingViewModel(auth, FakeOnboarding(), language, prefs, clock)
            vm.onIntent(OnboardingIntent.Skip)
            assertTrue(vm.state.value.finished)
            assertEquals(ProfileChange(onboardingCompleted = true), auth.changes.last())
        }

    @Test
    fun ownKeysSayNothingIsCharged() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val services = FakeOnboarding()
            services.creditsFlow.value = services.creditsFlow.value.copy(ownKeys = true)
            val vm = OnboardingViewModel(auth, services, language, prefs, clock)
            assertEquals("Your own keys cover everything: nothing is ever charged.", vm.state.value.creditsStatus)
        }

    @Test
    fun anOfflineReadingChoiceStillMovesOn() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val services = FakeOnboarding()
            services.readingError = AppError.Network
            val vm = OnboardingViewModel(auth, services, language, prefs, clock)
            repeat(3) { vm.onIntent(OnboardingIntent.Next) }
            assertEquals(OnboardingStep.Sources, vm.state.value.step)
        }

    @Test
    fun theOfficialSourceUnavailableSaysSo() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val services = FakeOnboarding()
            services.sourcesFlow.value = services.sourcesFlow.value.copy(officialAvailable = false)
            val vm = OnboardingViewModel(auth, services, language, prefs, clock)
            assertFalse(vm.state.value.canTurnOnOfficial)
            assertEquals("The vovoscan source isn’t available on this server yet.", vm.state.value.officialUnavailable)
        }

    @Test
    fun theDemoBalloonFollowsTheReadingLanguageAndNeverGuesses() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val vm = OnboardingViewModel(auth, FakeOnboarding(), language, prefs, clock)
            repeat(2) { vm.onIntent(OnboardingIntent.Next) }
            // Pre-filled with the app's language.
            var demo = vm.state.value.demo
            assertEquals("Let’s go, everyone!", demo.translated)
            assertEquals("en", demo.languageCode)
            assertEquals("Demo page, in English. Real chapters read the same way.", demo.caption)
            // No language at all: the original, and the note asks for one.
            demo = DemoLines.balloon(null)
            assertEquals("行くぞ、みんな！", demo.translated)
            assertEquals("ja", demo.languageCode)
            assertNull(demo.holdLabel)
            assertEquals("Demo page. Pick a language: the balloon is lettered in it.", demo.caption)
            // A sample written for it (the spec's exact strings): lettered, right to left, the original on hold.
            vm.onIntent(OnboardingIntent.ChooseReading("ar"))
            demo = vm.state.value.demo
            assertEquals("هيا بنا، جميعًا!", demo.translated)
            assertTrue(demo.rtl)
            assertTrue(demo.exact)
            assertEquals("Hold to see the original", demo.holdLabel)
            assertEquals("Demo page, in العربية. Real chapters read the same way.", demo.caption)
            vm.onIntent(OnboardingIntent.ChooseReading("fr"))
            assertEquals("On y va, tout le monde\u202F!", vm.state.value.demo.translated)
            // Any other language: the original stays, never a made-up line, and no hold button.
            vm.onIntent(OnboardingIntent.ChooseReading("ee"))
            demo = vm.state.value.demo
            assertFalse(demo.exact)
            assertFalse(demo.rtl)
            assertEquals("行くぞ、みんな！", demo.translated)
            assertNull(demo.holdLabel)
            assertEquals("Eʋegbe it is. Real chapters are translated into it when you open them.", demo.caption)
        }

    @Test
    fun theTourEndsOnARealTitleOfTheOfficialSource() =
        runTest {
            val auth = FakeAuth(signedIn = testSession())
            val services = FakeOnboarding()
            val vm = OnboardingViewModel(auth, services, language, prefs, clock)
            assertNull(vm.state.value.featuredAction)
            val refreshes = services.featuredRefreshes
            vm.onIntent(OnboardingIntent.TurnOnOfficial)
            // Turning the source on loads its catalog, whose first title closes the tour.
            assertEquals(refreshes + 1, services.featuredRefreshes)
            services.featuredFlow.value = OnboardingTitle("vovoscan", "t1", "Le Griot de Guerre", null)
            assertEquals("Read Le Griot de Guerre", vm.state.value.featuredAction)
            assertEquals("Balance: 1,200 credits", vm.state.value.balanceLabel(1200))
        }
}
