import Combine
import Shared

/// SwiftUI bridges to the shared Kotlin ViewModels. No logic here: published state and forwarded intents.
/// Each model holds its own `ViewModels` and releases it when it goes away (coroutines cancelled).

@MainActor
final class SessionModel: ObservableObject {
    @Published private(set) var session: Session?
    @Published private(set) var showTutorial: Bool
    @Published private(set) var deletionBanner: DeletionBannerUi?
    private let owner = ViewModels()
    private let viewModel: SessionViewModel

    init() {
        viewModel = owner.session()
        session = viewModel.session.value
        showTutorial = viewModel.tutorial.value.shown
        deletionBanner = viewModel.deletionBanner.value
    }

    func observe() async {
        for await next in viewModel.session { session = next }
    }

    func observeTutorial() async {
        for await next in viewModel.tutorial { showTutorial = next.shown }
    }

    func observeDeletion() async {
        for await next in viewModel.deletionBanner { deletionBanner = next }
    }

    func signOut() { viewModel.signOut() }

    func replayTutorial() { viewModel.replayTutorial() }

    func tutorialClosed() { viewModel.tutorialClosed() }

    func keepAccount() { viewModel.keepAccount() }

    deinit { owner.clear() }
}

/// The UI language: the root view is rebuilt in it (every ViewModel and every `L(…)` read again).
@MainActor
final class LocaleModel: ObservableObject {
    @Published private(set) var code: String = I18n.shared.locale.value

    func observe() async {
        for await next in I18n.shared.locale {
            code = next
            // The notification service extension writes its sentences in the app's language.
            PushSeal.setLocale(next)
        }
    }
}

@MainActor
final class AccountModel: ObservableObject {
    @Published private(set) var state: AccountUiState
    private let owner = ViewModels()
    private let viewModel: AccountViewModel

    init() {
        viewModel = owner.account()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: AccountIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class PreferencesModel: ObservableObject {
    @Published private(set) var state: PreferencesUiState
    private let owner = ViewModels()
    private let viewModel: PreferencesViewModel

    init() {
        viewModel = owner.preferences()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: PreferencesIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class SearchPrivacyModel: ObservableObject {
    @Published private(set) var state: SearchPrivacyUiState
    private let owner = ViewModels()
    private let viewModel: SearchPrivacyViewModel

    init() {
        viewModel = owner.searchPrivacy()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: SearchPrivacyIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class OnboardingModel: ObservableObject {
    @Published private(set) var state: OnboardingUiState
    private let owner = ViewModels()
    private let viewModel: OnboardingViewModel

    init() {
        viewModel = owner.onboarding()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: OnboardingIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class SignInModel: ObservableObject {
    @Published private(set) var state: SignInUiState
    private let owner = ViewModels()
    private let viewModel: SignInViewModel

    init() {
        viewModel = owner.signIn()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: SignInIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class CatalogModel: ObservableObject {
    @Published private(set) var state: CatalogUiState
    private let owner = ViewModels()
    private let viewModel: CatalogViewModel

    init() {
        viewModel = owner.catalog()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: CatalogIntent) { viewModel.onIntent(intent: intent) }

    /// Pull to refresh: returns once the new listing is in, so the system's spinner ends with it (a refresh that
    /// returned at once left it spinning while the grid was replaced underneath).
    func refresh() async {
        send(CatalogIntentRefresh.shared)
        for await next in viewModel.state where next.freshness != .refreshing { break }
    }

    deinit { owner.clear() }
}

/// Title page: details (catalog), following (library) and downloads (reader), three ViewModels unaware of each other.
@MainActor
final class TitleModel: ObservableObject {
    @Published private(set) var state: TitleDetailUiState
    @Published private(set) var follow: FollowUiState
    @Published private(set) var downloads: TitleDownloadsUiState
    private let owner = ViewModels()
    private let detail: TitleDetailViewModel
    private let following: FollowViewModel
    private let downloading: TitleDownloadsViewModel

    init(sourceKey: String, titleId: String) {
        detail = owner.titleDetail(sourceKey: sourceKey, titleId: titleId)
        following = owner.follow(sourceKey: sourceKey, titleId: titleId)
        downloading = owner.titleDownloads(sourceKey: sourceKey, titleId: titleId)
        state = detail.state.value
        follow = following.state.value
        downloads = downloading.state.value
    }

    func observe() async {
        for await next in detail.state { state = next }
    }

    func observeFollow() async {
        for await next in following.state { follow = next }
    }

    func observeDownloads() async {
        for await next in downloading.state { downloads = next }
    }

    func send(_ intent: TitleDownloadsIntent) {
        downloading.onIntent(intent: intent)
        // Downloads the user starts can carry on in the background (iOS 26+: a continued processing task).
        if intent is TitleDownloadsIntentAct || intent is TitleDownloadsIntentDownloadNext || intent is TitleDownloadsIntentDownloadAll {
            BackgroundDownloads.shared.userStarted()
        }
    }

    func refresh() { detail.refresh() }

    func toggleFollow() { following.toggle(name: state.name, coverUrl: state.coverUrl) }

    /// The heart (it follows the title too).
    func toggleFavorite() { following.toggleFavorite(name: state.name, coverUrl: state.coverUrl) }

    /// The stars: a tap or a drag let go at `x` across the five (0...1), a screen reader's step, or "Clear".
    func rate(at x: Double) { detail.onIntent(intent: TitleDetailIntentRateAt(x: x)) }

    func stepRating(up: Bool) { detail.onIntent(intent: TitleDetailIntentStepRating(up: up)) }

    func clearRating() { detail.onIntent(intent: TitleDetailIntentClearRating.shared) }

    func toggleOrder() { detail.onIntent(intent: TitleDetailIntentToggleOrder.shared) }

    /// "▶ Episodes": only the chapters with an episode, or every chapter again.
    func toggleEpisodes() { detail.onIntent(intent: TitleDetailIntentToggleEpisodes.shared) }

    /// A chapter row's pill: the view model answers with `state.open`, which the page opens once.
    func translate(_ chapterId: String) { detail.onIntent(intent: TitleDetailIntentTranslate(chapterId: chapterId)) }

    func episode(_ chapterId: String) { detail.onIntent(intent: TitleDetailIntentEpisode(chapterId: chapterId)) }

    func openHandled(_ seq: Int64) { detail.onIntent(intent: TitleDetailIntentOpenHandled(seq: seq)) }

    /// A long list: a chapter number typed (or the nearest one), the chapter being read; answered once scrolled.
    func jump(_ query: String) { detail.onIntent(intent: TitleDetailIntentJumpTo(query: query)) }
    func jumpToCurrent() { detail.onIntent(intent: TitleDetailIntentJumpToCurrent.shared) }
    func scrollHandled(_ seq: Int64) { detail.onIntent(intent: TitleDetailIntentScrollHandled(seq: seq)) }

    deinit { owner.clear() }
}

@MainActor
final class LibraryModel: ObservableObject {
    @Published private(set) var state: LibraryUiState
    private let owner = ViewModels()
    private let viewModel: LibraryViewModel

    init() {
        viewModel = owner.library()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: LibraryIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class DownloadsModel: ObservableObject {
    @Published private(set) var state: DownloadsUiState
    private let owner = ViewModels()
    private let viewModel: DownloadsViewModel

    init() {
        viewModel = owner.downloads()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: DownloadsIntent) {
        viewModel.onIntent(intent: intent)
        // A retry starts downloads again (iOS 26+: a continued processing task, see BackgroundDownloads).
        if intent is DownloadsIntentAct { BackgroundDownloads.shared.userStarted() }
    }

    deinit { owner.clear() }
}

@MainActor
final class ReaderModel: ObservableObject {
    @Published private(set) var state: ReaderUiState
    /// The library's heart for this title, on the end card (two features side by side, unaware of each other).
    @Published private(set) var follow: FollowUiState
    private let owner = ViewModels()
    private let viewModel: ReaderViewModel
    private let following: FollowViewModel

    init(route: ReaderRoute) {
        viewModel = owner.reader(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: route.chapterId, startPage: Int32(route.startPage))
        following = owner.follow(sourceKey: route.sourceKey, titleId: route.titleId)
        state = viewModel.state.value
        follow = following.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func observeFollow() async {
        for await next in following.state { follow = next }
    }

    func send(_ intent: ReaderIntent) { viewModel.onIntent(intent: intent) }

    /// The heart on the end card (it follows the title too).
    func toggleFavorite() { following.toggleFavorite(name: state.titleName, coverUrl: nil) }

    deinit { owner.clear() }
}

/// The reader's language: translations of the chapter and the lettering laid over each page (TranslationViewModel).
@MainActor
final class TranslationModel: ObservableObject {
    @Published private(set) var state: TranslationUiState
    private let owner = ViewModels()
    private let viewModel: TranslationViewModel

    init(route: ReaderRoute) {
        viewModel = owner.translation(
            sourceKey: route.sourceKey,
            chapterId: route.chapterId,
            titleId: route.titleId,
            language: route.language
        )
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: TranslationIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class SourcesModel: ObservableObject {
    @Published private(set) var state: SourcesUiState
    private let owner = ViewModels()
    private let viewModel: SourcesViewModel

    init() {
        viewModel = owner.sources()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: SourcesIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class AddSourceModel: ObservableObject {
    @Published private(set) var state: AddSourceUiState
    private let owner = ViewModels()
    private let viewModel: AddSourceViewModel

    init() {
        viewModel = owner.addSource()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: AddSourceIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class SourceDetailModel: ObservableObject {
    @Published private(set) var state: SourceDetailUiState
    private let owner = ViewModels()
    private let viewModel: SourceDetailViewModel

    init(key: String) {
        viewModel = owner.sourceDetail(key: key)
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: SourceDetailIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

// Settings (account slice).

@MainActor
final class ProviderKeysModel: ObservableObject {
    @Published private(set) var state: ProviderKeysUiState
    private let owner = ViewModels()
    private let viewModel: ProviderKeysViewModel

    init() {
        viewModel = owner.providerKeys()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: ProviderKeysIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class ReadingSettingsModel: ObservableObject {
    @Published private(set) var state: ReadingSettingsUiState
    private let owner = ViewModels()
    private let viewModel: ReadingSettingsViewModel

    init() {
        viewModel = owner.readingSettings()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: ReadingSettingsIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class UsageModel: ObservableObject {
    @Published private(set) var state: UsageUiState
    private let owner = ViewModels()
    private let viewModel: UsageViewModel

    init() {
        viewModel = owner.usage()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: UsageIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class WalletModel: ObservableObject {
    @Published private(set) var state: WalletUiState
    private let owner = ViewModels()
    private let viewModel: WalletViewModel

    init() {
        viewModel = owner.wallet()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: WalletIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

// Episodes.

@MainActor
final class EpisodeModel: ObservableObject {
    @Published private(set) var state: EpisodeUiState
    private let owner = ViewModels()
    private let viewModel: EpisodeViewModel

    /// A source read on the device (not "vovoscan") has its chapter's pages brought to the server first.
    init(chapterId: String, language: String = "", sourceKey: String = "vovoscan", titleId: String = "") {
        viewModel = owner.episode(chapterId: chapterId, language: language, sourceKey: sourceKey, titleId: titleId)
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: EpisodeIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

/// The player: coarse state is published (about once a second while playing); the stage asks `frame` on every
/// display frame, outside SwiftUI's state, so nothing but the Canvas redraws.
@MainActor
final class PlayerModel: ObservableObject {
    @Published private(set) var state: PlayerUiState
    private let owner = ViewModels()
    let viewModel: PlayerViewModel

    init(chapterId: String, nextChapterId: String?, language: String = "", startPage: Int? = nil) {
        if let startPage {
            viewModel = owner.playerFrom(chapterId: chapterId, language: language, startPage: Int32(startPage))
        } else {
            viewModel = owner.player(chapterId: chapterId, nextChapterId: nextChapterId, language: language)
        }
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: PlayerIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}
