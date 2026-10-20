import Shared
import SwiftUI

/// Root: sign-in, or the Catalog / Library / Sources tabs. The session comes from shared Kotlin.
struct RootView: View {
    @StateObject private var session = SessionModel()

    var body: some View {
        ZStack {
            Color.brandInk.ignoresSafeArea()
            if session.session == nil {
                SignInView()
            } else {
                TabView {
                    NavigationStack { CatalogView() }
                        .tabItem { Label("Catalog", systemImage: "square.grid.2x2") }
                    NavigationStack { LibraryView(onSignOut: session.signOut) }
                        .tabItem { Label("Library", systemImage: "books.vertical") }
                    NavigationStack { SourcesView() }
                        .tabItem { Label("Sources", systemImage: "square.stack.3d.up") }
                }
            }
        }
        .task { await session.observe() }
    }
}
