import Shared
import SwiftUI

struct LibraryView: View {
    let onSignOut: () -> Void
    @StateObject private var model = LibraryModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Kicker(text: "Library")
                Text("Keep reading.").font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                if let notice = model.state.pendingNotice { OfflineNotice(text: notice) }
                if let error = model.state.error { ErrorText(text: error) }
                if model.state.empty {
                    Text(model.state.emptyMessage).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                }
                ForEach(model.state.rows, id: \.self) { row in
                    NavigationLink(value: TitleRoute(sourceKey: row.sourceKey, titleId: row.titleId)) {
                        HStack(spacing: 14) {
                            AsyncImage(url: row.coverUrl.flatMap(URL.init(string:))) { image in
                                image.resizable().scaledToFill()
                            } placeholder: { Color.brandLine }
                            .frame(width: 64, height: 64)
                            .clipped()
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.name).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                                Text(row.progress).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                            }
                            Spacer()
                            // Cyan : action en attente d'envoi (la marque réserve le cyan au travail en cours).
                            if row.pendingSync { Circle().fill(Color.brandCyan).frame(width: 10, height: 10) }
                        }
                        .padding(.trailing, 14)
                        .background(Color.brandInk2)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
                Button("Sync now") { model.send(LibraryIntentSync.shared) }.buttonStyle(GhostButtonStyle())
                Button("Sign out", action: onSignOut).buttonStyle(GhostButtonStyle())
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .refreshable { model.send(LibraryIntentSync.shared) }
        .navigationDestination(for: TitleRoute.self) { TitleView(route: $0) }
        .navigationDestination(for: ReaderRoute.self) { ReaderContainer(route: $0) }
        .task { await model.observe() }
    }
}
