import Shared
import SwiftUI
import UIKit

/// The tabs of the signed-in app.
enum AppTab: Hashable {
    case catalog, library, sources, settings
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

    init() { Self.styleBars() }

    var body: some View {
        ZStack {
            Color.brandInk.ignoresSafeArea()
            if let current = session.session {
                TabView(selection: $tab) {
                    NavigationStack { CatalogView() }
                        .tabItem { Label(L("nav.catalog"), systemImage: "square.grid.2x2") }
                        .tag(AppTab.catalog)
                    NavigationStack { LibraryView(onSignOut: session.signOut) }
                        .tabItem { Label(L("nav.library"), systemImage: "books.vertical") }
                        .tag(AppTab.library)
                    NavigationStack { SourcesView() }
                        .tabItem { Label(L("nav.sources"), systemImage: "square.stack.3d.up") }
                        .tag(AppTab.sources)
                    NavigationStack { SettingsView(session: current, onSignOut: session.signOut, onReplayTutorial: session.replayTutorial) }
                        .tabItem { Label(L("nav.settings"), systemImage: "gearshape") }
                        .tag(AppTab.settings)
                }
                .environment(\.openTab, OpenTabAction { tab = $0 })
                .sensoryFeedback(.selection, trigger: tab)
                .safeAreaInset(edge: .top, spacing: 0) {
                    if let banner = session.deletionBanner { DeletionBanner(banner: banner, keep: session.keepAccount) }
                }
                .fullScreenCover(isPresented: Binding(get: { session.showTutorial }, set: { if !$0 { session.tutorialClosed() } })) {
                    OnboardingView(onClose: session.tutorialClosed) { done in
                        tab = done
                    }
                }
                .transition(.opacity)
            } else {
                SignInView().transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: session.session == nil)
        .task { await session.observe() }
        .task { await session.observeTutorial() }
        .task { await session.observeDeletion() }
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
