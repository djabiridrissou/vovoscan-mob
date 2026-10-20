import Shared
import SwiftUI

/// Opens the page of a title from a source.
struct TitleRoute: Hashable {
    let sourceKey: String
    let titleId: String
}

/// Title page. Details (catalog) and following (library) share it, unaware of each other.
struct TitleView: View {
    @StateObject private var model: TitleModel

    private let route: TitleRoute

    init(route: TitleRoute) {
        self.route = route
        _model = StateObject(wrappedValue: TitleModel(sourceKey: route.sourceKey, titleId: route.titleId))
    }

    private func read(_ chapterId: String, _ page: Int = 0) -> ReaderRoute {
        ReaderRoute(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: chapterId, startPage: page)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                AsyncImage(url: model.state.coverUrl.flatMap(URL.init(string:))) { image in
                    image.resizable().scaledToFill()
                } placeholder: { Color.brandInk2 }
                .frame(height: 260)
                .frame(maxWidth: .infinity)
                .clipped()
                VStack(alignment: .leading, spacing: 12) {
                    if model.state.unavailable {
                        Text("This title isn't saved on this device yet.").font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                    }
                    if let error = model.state.error { ErrorText(text: error) }
                    Text(model.state.name).font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                    Text(model.state.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    // Resume where the reader left off, otherwise start at the first chapter (list runs newest to oldest).
                    if let resume = model.follow.resumeChapterId {
                        NavigationLink(value: read(resume, Int(model.follow.resumePage))) {
                            Text(model.follow.resumeLabel(book: model.state.isBook) ?? "Continue →")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    } else if let first = model.state.chapters.last {
                        NavigationLink(value: read(first.id)) { Text(model.state.isBook ? "Read →" : "Start reading →") }
                            .buttonStyle(PrimaryButtonStyle())
                    }
                    if !model.state.name.isEmpty {
                        Button(model.follow.label) { model.toggleFollow() }.buttonStyle(GhostButtonStyle())
                    }
                    Text(model.state.synopsis).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                    if !model.state.genres.isEmpty {
                        Text(model.state.genres.joined(separator: " ✦ ")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    }
                    if !model.state.chapters.isEmpty {
                        Text(model.state.isBook ? "Contents" : "Chapters").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                    }
                    ForEach(model.state.chapters, id: \.id) { chapter in
                        NavigationLink(value: read(chapter.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(chapter.label).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                            Text(chapter.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20)
            }
        }
        .background(Color.brandInk)
        .refreshable { model.refresh() }
        .task { await model.observe() }
        .task { await model.observeFollow() }
    }
}
