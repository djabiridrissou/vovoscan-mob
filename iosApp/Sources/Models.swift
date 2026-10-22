import Combine
import Shared

/// SwiftUI bridges to the shared Kotlin ViewModels. No logic here: published state and forwarded intents.
/// Each model holds its own `ViewModels` and releases it when it goes away (coroutines cancelled).

@MainActor
final class SessionModel: ObservableObject {
    @Published private(set) var session: Session?
    private let owner = ViewModels()
    private let viewModel: SessionViewModel

    init() {
        viewModel = owner.session()
        session = viewModel.session.value
    }

    func observe() async {
        for await next in viewModel.session { session = next }
    }

    func signOut() { viewModel.signOut() }

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
    private let owner = ViewModels()
    private let viewModel: ReaderViewModel

    init(route: ReaderRoute) {
        viewModel = owner.reader(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: route.chapterId, startPage: Int32(route.startPage))
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: ReaderIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

/// The reader's language: translations of the chapter and the lettering laid over each page (TranslationViewModel).
@MainActor
final class TranslationModel: ObservableObject {
    @Published private(set) var state: TranslationUiState
    private let owner = ViewModels()
    private let viewModel: TranslationViewModel

    init(route: ReaderRoute) {
        viewModel = owner.translation(sourceKey: route.sourceKey, chapterId: route.chapterId)
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

    init(chapterId: String, language: String = "") {
        viewModel = owner.episode(chapterId: chapterId, language: language)
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

    init(chapterId: String, nextChapterId: String?, language: String = "") {
        viewModel = owner.player(chapterId: chapterId, nextChapterId: nextChapterId, language: language)
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: PlayerIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}
