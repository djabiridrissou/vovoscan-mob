import SwiftUI

/// Every screen a tab's stack can push. One typed path per tab (`[AppRoute]`, owned by `RootView`), one
/// `navigationDestination(for: AppRoute.self)` at the stack's root, and no destination registered inside a pushed
/// view: links and pushes only append to the path, so the stack never holds a screen the path doesn't know.
enum AppRoute: Hashable {
    case title(TitleRoute)
    case reader(ReaderRoute)
    case episode(EpisodeRoute)
    case downloads
    /// A group, by id or `@handle`.
    case group(String)
    case game(String)
    case groupAdmin(String)
    case notifications
}

/// Pushes a route on the current tab's stack (the title page's pills, a suggestion picked under the search field).
struct PushAction {
    let push: (AppRoute) -> Void
    func callAsFunction(_ route: AppRoute) { push(route) }
}

private struct PushKey: EnvironmentKey {
    static let defaultValue = PushAction { _ in }
}

/// Pops the top screen of the current tab's path (the reader's ←, Escape); nil outside a tab's stack.
struct PopAction {
    let pop: () -> Void
    func callAsFunction() { pop() }
}

private struct PopKey: EnvironmentKey {
    static let defaultValue: PopAction? = nil
}

extension EnvironmentValues {
    var push: PushAction {
        get { self[PushKey.self] }
        set { self[PushKey.self] = newValue }
    }

    var pop: PopAction? {
        get { self[PopKey.self] }
        set { self[PopKey.self] = newValue }
    }
}

/// The screen of a route. `zoom`: the catalog's namespace, so a title opened from its tile zooms out of it.
struct AppRouteView: View {
    let route: AppRoute
    var zoom: Namespace.ID?

    var body: some View {
        switch route {
        case let .title(title):
            if let zoom {
                TitleView(route: title).navigationTransition(.zoom(sourceID: title, in: zoom))
            } else {
                TitleView(route: title)
            }
        case let .reader(reader):
            ReaderContainer(route: reader)
        case let .episode(episode):
            EpisodeContainer(route: episode)
        case .downloads:
            DownloadsView()
        case let .group(ref):
            GroupView(ref: ref)
        case let .game(id):
            GameView(gameId: id)
        case let .groupAdmin(id):
            GroupAdminView(groupId: id)
        case .notifications:
            NotificationsView()
        }
    }
}
