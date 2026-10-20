import Shared
import SwiftUI

/// Racine : connexion, ou onglets Catalogue / Bibliothèque. La session vient du Kotlin partagé.
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
                }
            }
        }
        .task { await session.observe() }
    }
}
