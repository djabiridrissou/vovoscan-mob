import Combine
import Shared

/// Ponts SwiftUI vers les ViewModels Kotlin partagés. Aucune logique ici : état publié et intentions transmises.
/// Chaque modèle garde son propre `ViewModels` et le libère en disparaissant (coroutines annulées).

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

@MainActor
final class TitleModel: ObservableObject {
    @Published private(set) var state: TitleDetailUiState
    @Published private(set) var follow: FollowUiState
    private let owner = ViewModels()
    private let detail: TitleDetailViewModel
    private let following: FollowViewModel

    init(sourceKey: String, titleId: String) {
        detail = owner.titleDetail(sourceKey: sourceKey, titleId: titleId)
        following = owner.follow(sourceKey: sourceKey, titleId: titleId)
        state = detail.state.value
        follow = following.state.value
    }

    func observe() async {
        for await next in detail.state { state = next }
    }

    func observeFollow() async {
        for await next in following.state { follow = next }
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
