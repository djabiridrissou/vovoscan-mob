import Shared
import SwiftUI

/// Opens the Downloads screen (from the Library).
struct DownloadsRoute: Hashable {}

/// A chapter's download control, from its row: download (arrow), a progress ring with a stop square while queued,
/// waiting or downloading (cyan: work in progress), downloaded (check), failed (retry). Nothing for a book not
/// opened yet. The row says what a tap does (`action`); the screen confirms a delete first.
struct DownloadControl: View {
    let row: DownloadRow
    let onTap: () -> Void

    var body: some View {
        if row.action != .unavailable {
            Button(action: onTap) {
                ZStack {
                    switch row.status {
                    case .notDownloaded:
                        Image(systemName: "arrow.down.circle").foregroundStyle(Color.brandMuted)
                    case .done:
                        Image(systemName: "checkmark.circle").foregroundStyle(Color.brandPaper)
                    case .failed:
                        Image(systemName: "exclamationmark.circle").foregroundStyle(Color.brandDanger)
                    default:
                        Circle().stroke(Color.brandLine, lineWidth: 2.5)
                        Circle()
                            .trim(from: 0, to: CGFloat(row.status == .downloading ? row.progress : 0))
                            .stroke(Color.brandCyan, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        RoundedRectangle(cornerRadius: 1.5).fill(Color.brandPaper).frame(width: 8, height: 8)
                    }
                }
                .font(.system(size: 23))
                .frame(width: 24, height: 24)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(accessibility)
        }
    }

    private var accessibility: String {
        switch row.action {
        case .download: return "Download"
        case .delete: return "Downloaded. Delete"
        case .retry: return "Failed. Try again"
        default: return "Stop the download"
        }
    }
}

/// Downloads: chapters and books on the device by title, the space they use, and Wi-Fi only.
struct DownloadsView: View {
    @StateObject private var model = DownloadsModel()
    @State private var wifiOnly = true
    /// A delete waiting for its confirmation.
    @State private var confirm: Confirm?

    private enum Confirm: Identifiable {
        case chapter(sourceKey: String, chapterId: String)
        case title(sourceKey: String, titleId: String)
        var id: String {
            switch self {
            case let .chapter(source, chapter): return "c|\(source)|\(chapter)"
            case let .title(source, title): return "t|\(source)|\(title)"
            }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Kicker(text: "Downloads")
                Text("Read offline.").font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                if !model.state.total.isEmpty {
                    Text(model.state.total).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                Toggle(isOn: $wifiOnly) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.state.wifiOnlyLabel).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                        Text(model.state.wifiOnlyHelp).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    }
                }
                .tint(Color.brandRed)
                .onAppear { wifiOnly = model.state.wifiOnly }
                .onChange(of: model.state.wifiOnly) { _, on in wifiOnly = on }
                .onChange(of: wifiOnly) { _, on in
                    if on != model.state.wifiOnly { model.send(DownloadsIntentSetWifiOnly(enabled: on)) }
                }
                if let notice = model.state.notice { OfflineNotice(text: notice) }
                if model.state.empty {
                    Text(model.state.emptyMessage).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                }
                ForEach(model.state.groups, id: \.titleId) { group in
                    GroupCard(group: group, confirm: $confirm, model: model)
                }
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .task { await model.observe() }
        .alert(
            confirmTitle,
            isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
            presenting: confirm
        ) { target in
            Button("Delete", role: .destructive) {
                switch target {
                case let .chapter(source, chapter): model.send(DownloadsIntentAct(sourceKey: source, chapterId: chapter))
                case let .title(source, title): model.send(DownloadsIntentDeleteTitle(sourceKey: source, titleId: title))
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Reading it will need the network again.")
        }
    }

    private var confirmTitle: String {
        if case .title = confirm { return "Delete the downloads?" }
        return "Delete this download?"
    }

    private struct GroupCard: View {
        let group: DownloadGroup
        @Binding var confirm: Confirm?
        @ObservedObject var model: DownloadsModel

        var body: some View {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    NavigationLink(value: TitleRoute(sourceKey: group.sourceKey, titleId: group.titleId)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(group.titleName).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                            Text(group.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Button("Delete") { confirm = .title(sourceKey: group.sourceKey, titleId: group.titleId) }
                        .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted)
                }
                ForEach(group.rows, id: \.chapterId) { row in
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.label).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                            if let text = row.text {
                                Text(text).font(.brandBody(13))
                                    .foregroundStyle(row.status == .failed ? Color.brandDanger : Color.brandMuted)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        if row.removable {
                            Button("Remove") { model.send(DownloadsIntentRemove(sourceKey: row.sourceKey, chapterId: row.chapterId)) }
                                .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted)
                        }
                        DownloadControl(row: row) {
                            if row.action == .delete {
                                confirm = .chapter(sourceKey: row.sourceKey, chapterId: row.chapterId)
                            } else {
                                model.send(DownloadsIntentAct(sourceKey: row.sourceKey, chapterId: row.chapterId))
                            }
                        }
                    }
                }
            }
            .padding(16)
            .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
        }
    }
}
