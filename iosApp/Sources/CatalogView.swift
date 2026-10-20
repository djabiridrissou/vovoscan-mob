import Shared
import SwiftUI

struct CatalogView: View {
    @StateObject private var model = CatalogModel()
    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 14)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Kicker(text: "Catalog")
                Text("Pick a title.").font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                IntentField(label: "Search", value: model.state.query) { model.send(CatalogIntentQuery(value: $0)) }
                if let notice = model.state.offlineNotice { OfflineNotice(text: notice) }
                if let error = model.state.error { ErrorText(text: error) }
                if model.state.empty {
                    Text(model.state.emptyMessage).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                }
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(model.state.visible, id: \.id) { card in
                        NavigationLink(value: card.id) { TitleTile(card: card) }
                            .buttonStyle(.plain)
                    }
                }
                if model.state.canLoadMore && model.state.results == nil {
                    Button(model.state.loadingMore ? "Loading…" : "Load more") { model.send(CatalogIntentLoadMore.shared) }
                        .buttonStyle(GhostButtonStyle())
                }
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .refreshable { model.send(CatalogIntentRefresh.shared) }
        .navigationDestination(for: String.self) { TitleView(titleId: $0) }
        .navigationDestination(for: ReaderRoute.self) { ReaderContainer(route: $0) }
        .task { await model.observe() }
    }
}

/// Tuile d'un titre : couverture, pastille de format, nom.
private struct TitleTile: View {
    let card: TitleCard

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topLeading) {
                Color.brandLine
                AsyncImage(url: card.coverUrl.flatMap(URL.init(string:))) { image in
                    image.resizable().scaledToFill()
                } placeholder: { Color.brandLine }
                Text(card.formatLabel)
                    .font(.brandBody(12, weight: .bold))
                    .foregroundStyle(Color.brandInk)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.brandYellow)
                    .padding(8)
            }
            .aspectRatio(3 / 4, contentMode: .fit)
            .clipped()
            VStack(alignment: .leading, spacing: 2) {
                Text(card.name).font(.brandBody(15)).foregroundStyle(Color.brandPaper).lineLimit(2)
                Text(card.status).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            }
            .padding(12)
        }
        .background(Color.brandInk2)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 2))
    }
}
