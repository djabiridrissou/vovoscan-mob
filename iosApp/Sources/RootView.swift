import Shared
import SwiftUI
import UIKit

/// The tabs of the signed-in app.
enum AppTab: Hashable {
    case catalog, library, groups, sources, settings
}

/// Lets a screen send the reader to another tab ("Add a source →" from an empty catalog).
struct OpenTabAction {
    let open: (AppTab) -> Void
    func callAsFunction(_ tab: AppTab) { open(tab) }
}

private struct OpenTabKey: EnvironmentKey {
    static let defaultValue = OpenTabAction { _ in }
}

extension EnvironmentValues {
    var openTab: OpenTabAction {
        get { self[OpenTabKey.self] }
        set { self[OpenTabKey.self] = newValue }
    }
}

/// The app in its UI language: when the language changes, everything under it is built again (ViewModels and
/// `L(…)` texts read anew); the launch stamp sits outside, so it plays once.
struct LocaleRoot: View {
    @StateObject private var locale = LocaleModel()
    @State private var stamped = false

    var body: some View {
        ZStack {
            RootView()
                .id(locale.code)
                .environment(\.locale, Locale(identifier: locale.code))
            if !stamped { LaunchStamp { stamped = true } }
        }
        .task { await locale.observe() }
    }
}

/// Root: sign-in, or the Catalog / Library / Sources / Settings tabs, the first-launch tutorial over them once, and
/// the banner of a scheduled deletion. The session comes from shared Kotlin.
struct RootView: View {
    @StateObject private var session = SessionModel()
    @State private var tab: AppTab = .catalog
    /// Each tab's navigation, one typed path (see `AppRoute`): the tutorial ends by opening a title in the catalog's.
    @State private var catalogPath: [AppRoute] = []
    @State private var libraryPath: [AppRoute] = []
    @State private var groupsPath: [AppRoute] = []
    /// The Groups tab shows only with the `forum` feature; the inbox runs while the app is on screen and signed in.
    @StateObject private var groups = GroupsModel()
    @StateObject private var inbox = NotificationsModel()
    @ObservedObject private var groupLinks = GroupLinkCenter.shared
    @Environment(\.scenePhase) private var scenePhase
    /// A reading circle's invite (`vovoscan://together/CODE`): its card over the app, then the reader, following.
    @State private var invite: InviteLink?
    @State private var joined: JoinedChapter?

    init() { Self.styleBars() }

    var body: some View {
        ZStack {
            Color.brandInk.ignoresSafeArea()
            if let current = session.session {
                // iPhone: the tab bar. iPad: the floating tab bar, which turns into a sidebar (same tabs, same state).
                TabView(selection: $tab) {
                    Tab(L("nav.catalog"), systemImage: "square.grid.2x2", value: AppTab.catalog) {
                        NavigationStack(path: $catalogPath) { CatalogView() }
                            .environment(\.push, PushAction { catalogPath.append($0) })
                            .environment(\.pop, PopAction { if !catalogPath.isEmpty { catalogPath.removeLast() } })
                    }
                    Tab(L("nav.library"), systemImage: "books.vertical", value: AppTab.library) {
                        NavigationStack(path: $libraryPath) { LibraryView(onSignOut: session.signOut) }
                            .environment(\.push, PushAction { libraryPath.append($0) })
                            .environment(\.pop, PopAction { if !libraryPath.isEmpty { libraryPath.removeLast() } })
                    }
                    if groups.state.visible {
                        Tab(L("nav.groups"), systemImage: "person.3", value: AppTab.groups) {
                            NavigationStack(path: $groupsPath) { GroupsView(model: groups) }
                                .environment(\.push, PushAction { groupsPath.append($0) })
                                .environment(\.pop, PopAction { if !groupsPath.isEmpty { groupsPath.removeLast() } })
                        }
                        .badge(inbox.state.badge.map { Text($0) })
                    }
                    Tab(L("nav.sources"), systemImage: "square.stack.3d.up", value: AppTab.sources) {
                        NavigationStack { SourcesView() }
                    }
                    Tab(L("nav.settings"), systemImage: "gearshape", value: AppTab.settings) {
                        NavigationStack { SettingsView(session: current, onSignOut: session.signOut, onReplayTutorial: session.replayTutorial) }
                    }
                }
                .tabViewStyle(.sidebarAdaptable)
                .tint(Color.brandRed)
                // A hardware keyboard: ⌘1 to ⌘5 open the tabs (listed when ⌘ is held on iPad).
                .background { TabShortcuts(tab: $tab, groups: groups.state.visible) }
                .environment(\.openTab, OpenTabAction { tab = $0 })
                .sensoryFeedback(.selection, trigger: tab)
                .safeAreaInset(edge: .top, spacing: 0) {
                    if let banner = session.deletionBanner { DeletionBanner(banner: banner, keep: session.keepAccount) }
                }
                .fullScreenCover(isPresented: Binding(get: { session.showTutorial }, set: { if !$0 { session.tutorialClosed() } })) {
                    OnboardingView(onClose: session.tutorialClosed, openTab: { tab = $0 }, openTitle: { route in
                        tab = .catalog
                        catalogPath = [.title(route)]
                    })
                }
                // The reader is pushed once the card is gone: a push under a closing sheet can be lost.
                .sheet(item: $invite, onDismiss: openJoined) { (link: InviteLink) in
                    TogetherInviteView(link: link.url, onJoined: { chapter in
                        joined = chapter
                        invite = nil
                    }, onClose: { invite = nil })
                    .presentationDetents([.medium])
                    .presentationBackground(Color.brandInk2)
                }
                .transition(.opacity)
            } else {
                SignInView().transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: session.session == nil)
        // Signed out, the link waits for nothing: the card needs an account (it opens again from the link later).
        .onOpenURL { url in
            guard session.session != nil else { return }
            if let link = InviteLink(url) { invite = link } else { openGroup(url) }
        }
        .onChange(of: groupLinks.link) { _, link in
            guard let link, let url = URL(string: link) else { return }
            groupLinks.link = nil
            if session.session != nil { openGroup(url) }
        }
        .onChange(of: scenePhase, initial: true) { _, phase in inboxFollows(phase == .active && session.session != nil) }
        .onChange(of: session.session == nil) { _, signedOut in inboxFollows(!signedOut && scenePhase == .active) }
        .task { await groups.observe() }
        .task { await inbox.observe() }
        .task { await session.observe() }
        .task { await session.observeTutorial() }
        .task { await session.observeDeletion() }
    }

    /// The circle's chapter, joined from an invite: the reader in the catalog's tab, already following the host.
    private func openJoined() {
        guard let chapter = joined else { return }
        joined = nil
        tab = .catalog
        catalogPath.append(.reader(ReaderRoute(sourceKey: "vovoscan", titleId: chapter.titleRef, chapterId: chapter.chapterId, startPage: 0)))
    }

    /// `vovoscan://g/<handle>` (a shared group, a tapped notification), `?game=<id>` for one of its games: the Groups
    /// tab, the group, then the game.
    private func openGroup(_ url: URL) {
        guard url.scheme == "vovoscan", url.host() == "g", groups.state.visible,
              let handle = url.pathComponents.dropFirst().first, !handle.isEmpty else { return }
        var path: [AppRoute] = [.group("@\(handle)")]
        if let game = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "game" })?.value, !game.isEmpty {
            path.append(.game(game))
        }
        tab = .groups
        groupsPath = path
    }

    private func inboxFollows(_ on: Bool) {
        inbox.send(on ? NotificationsIntentStart.shared : NotificationsIntentStop.shared)
    }

    /// Ink bars with a hairline, brand fonts in the navigation titles: the system chrome follows the brand.
    private static func styleBars() {
        let tabBar = UITabBarAppearance()
        tabBar.configureWithOpaqueBackground()
        tabBar.backgroundColor = UIColor(Color.brandInk)
        tabBar.shadowColor = UIColor(Color.brandLine)
        for item in [tabBar.stackedLayoutAppearance, tabBar.inlineLayoutAppearance, tabBar.compactInlineLayoutAppearance] {
            item.normal.iconColor = UIColor(Color.brandMuted)
            item.normal.titleTextAttributes = [.foregroundColor: UIColor(Color.brandMuted)]
            item.selected.iconColor = UIColor(Color.brandRed)
            item.selected.titleTextAttributes = [.foregroundColor: UIColor(Color.brandRed)]
        }
        UITabBar.appearance().standardAppearance = tabBar
        UITabBar.appearance().scrollEdgeAppearance = tabBar

        let nav = UINavigationBarAppearance()
        nav.configureWithOpaqueBackground()
        nav.backgroundColor = UIColor(Color.brandInk)
        nav.shadowColor = .clear
        nav.titleTextAttributes = [.foregroundColor: UIColor(Color.brandPaper)]
        nav.largeTitleTextAttributes = [.foregroundColor: UIColor(Color.brandPaper)]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().compactAppearance = nav
        UINavigationBar.appearance().tintColor = UIColor(Color.brandRed)
    }
}

/// ⌘1 to ⌘5: one invisible button per tab, so the keyboard shortcuts are registered with the system.
private struct TabShortcuts: View {
    @Binding var tab: AppTab
    let groups: Bool

    var body: some View {
        ZStack {
            shortcut(.catalog, "1", "nav.catalog")
            shortcut(.library, "2", "nav.library")
            if groups {
                shortcut(.groups, "3", "nav.groups")
                shortcut(.sources, "4", "nav.sources")
                shortcut(.settings, "5", "nav.settings")
            } else {
                shortcut(.sources, "3", "nav.sources")
                shortcut(.settings, "4", "nav.settings")
            }
        }
        .frame(width: 0, height: 0)
        .opacity(0)
        .accessibilityHidden(true)
    }

    private func shortcut(_ target: AppTab, _ key: KeyEquivalent, _ label: String) -> some View {
        Button(L(label)) { tab = target }.keyboardShortcut(key, modifiers: .command)
    }
}

/// "Your account will be deleted on 21 November 2026." with "Keep my account": over every tab until cancelled.
private struct DeletionBanner: View {
    let banner: DeletionBannerUi
    let keep: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Color.brandInk)
                Text(banner.text).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let error = banner.error { Text(error).font(.brandBody(13)).foregroundStyle(Color.brandInk) }
            Button {
                Haptics.press()
                keep()
            } label: {
                HStack(spacing: 8) {
                    if banner.working { ProgressView().tint(Color.brandPaper) }
                    Text(banner.action)
                }
                .font(.brandBody(14, weight: .bold))
                .foregroundStyle(Color.brandPaper)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.brandInk, in: Capsule())
            }
            .buttonStyle(.plain)
            .disabled(banner.working)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandYellow)
        .accessibilityElement(children: .contain)
    }
}

/// Opening: the mark is stamped on the page (scale down with a hard landing and a haptic), then the app shows.
/// Under Reduce Motion it simply fades.
private struct LaunchStamp: View {
    let done: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var landed = false
    @State private var gone = false

    var body: some View {
        ZStack {
            Color.brandInk.ignoresSafeArea()
            SpeedLines(center: .center, count: 72).opacity(landed ? 1 : 0)
            VStack(spacing: 18) {
                LogoMark(size: 88)
                    .scaleEffect(landed || reduceMotion ? 1 : 2.4)
                    .opacity(landed || reduceMotion ? 1 : 0)
                Text("vovoscan").font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                    .opacity(landed ? 1 : 0)
            }
        }
        .opacity(gone ? 0 : 1)
        .allowsHitTesting(!gone)
        .task {
            withAnimation(.spring(response: 0.32, dampingFraction: 0.62)) { landed = true }
            try? await Task.sleep(for: .milliseconds(reduceMotion ? 50 : 220))
            Haptics.press()
            try? await Task.sleep(for: .milliseconds(reduceMotion ? 200 : 520))
            withAnimation(.easeOut(duration: 0.3)) { gone = true }
            try? await Task.sleep(for: .milliseconds(320))
            done()
        }
        .accessibilityHidden(true)
    }
}
