import Shared
import SwiftUI
import UIKit

/// Opens a chapter's episode card from a title page. `title` is the page's state when it was opened: chapter label
/// and the next chapter in reading order come from it (shared Kotlin), never computed here.
struct EpisodeRoute: Hashable {
    let chapterId: String
    let title: TitleDetailUiState
    /// The episode's language when the card opens: "" = the original; the next chapter keeps the one just watched.
    var language: String = ""
    /// The chapter's source and title (default: the title page's). A source read on the device has its pages brought to
    /// the server first; the card then plays that copy.
    var sourceKey: String? = nil
    var titleId: String? = nil
}

/// "Next chapter →" at the end of the player swaps the card for the next chapter's, like the reader does.
struct EpisodeContainer: View {
    @State var route: EpisodeRoute

    var body: some View {
        EpisodeView(route: route) {
            route = EpisodeRoute(chapterId: $0, title: route.title, language: $1, sourceKey: route.sourceKey, titleId: route.titleId)
        }
            .id(route.chapterId)
    }
}

/// A chapter's episode card: whether it exists, its live progress (followed only while on screen), what the user may
/// do with their own keys, and the MP4 once ready. No logic here: `EpisodeViewModel` (shared Kotlin) decides.
struct EpisodeView: View {
    @StateObject private var model: EpisodeModel
    @State private var player: PlayerRoute?
    @State private var share: SharedVideo?
    @State private var choosingLanguage = false
    @Environment(\.dismiss) private var dismiss
    private let route: EpisodeRoute
    /// Presented in a sheet (a source's "Watch from here"): a Close button, since there is no stack to go back in.
    private let inSheet: Bool
    /// The next chapter's id and the language to open it in.
    let onOpenChapter: (String, String) -> Void

    init(route: EpisodeRoute, inSheet: Bool = false, onOpenChapter: @escaping (String, String) -> Void) {
        self.route = route
        self.inSheet = inSheet
        self.onOpenChapter = onOpenChapter
        _model = StateObject(
            wrappedValue: EpisodeModel(
                chapterId: route.chapterId,
                language: route.language,
                sourceKey: route.sourceKey ?? route.title.sourceKey,
                titleId: route.titleId ?? route.title.titleId
            )
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Kicker(text: L("episode.kicker"))
                if !route.title.name.isEmpty {
                    Text(route.title.name).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                let label = route.title.chapterLabel(chapterId: route.chapterId)
                if !label.isEmpty { Text(label).font(.brandDisplay(26)).foregroundStyle(Color.brandPaper) }
                if let notice = model.state.offlineNotice { OfflineNotice(text: notice) }
                if model.state.visible { card }
            }
            .padding(20)
        }
        .readableWidth()
        .background(Color.brandInk)
        // Always a way out: the bar with its back button (and the edge swipe) when pushed, Close in a sheet. Shown
        // explicitly, so a screen that hid it before (the reader) never leaves the card without one.
        .toolbar(.visible, for: .navigationBar)
        .toolbarBackground(Color.brandInk, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            if inSheet {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("common.close")) { dismiss() }
                        .foregroundStyle(Color.brandPaper)
                        .accessibilityIdentifier("episode.close")
                }
            }
        }
        .refreshable { model.send(EpisodeIntentRefresh.shared) }
        .task { await model.observe() }
        // Live progress only while the card is on screen.
        .onAppear { model.send(EpisodeIntentStart.shared) }
        .onDisappear { model.send(EpisodeIntentStop.shared) }
        // A fresh download link from the ViewModel: download it, then the share sheet (Save Video, Save to Files…).
        .onChange(of: model.state.videoDownload?.id) {
            guard let download = model.state.videoDownload else { return }
            Task {
                if let file = await VideoFile.download(download.url, as: download.fileName) {
                    share = SharedVideo(url: file)
                    model.send(EpisodeIntentVideoHandled.shared)
                } else {
                    model.send(EpisodeIntentVideoFailed.shared)
                }
            }
        }
        .sheet(item: $share) { ShareSheet(items: [$0.url]) }
        .fullScreenCover(item: $player) { route in
            PlayerView(route: route) { next, language in
                player = nil
                onOpenChapter(next, language)
            }
        }
        .sheet(isPresented: $choosingLanguage) {
            EpisodeLanguageSheet(model: model)
                .presentationDetents([.medium, .large])
                .presentationBackground(Color.brandInk2)
        }
    }

    private var card: some View {
        let state = model.state
        return VStack(alignment: .leading, spacing: 12) {
            if state.comingSoon { Sticker(text: L("episode.comingSoonSticker"), angle: -4).padding(.bottom, 4) }
            if !state.languageOptions.isEmpty { languageButton(state) }
            HStack(alignment: .firstTextBaseline) {
                Text(state.headline).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                Spacer()
                if state.live { LiveBadge() }
            }
            if let detail = state.detail { Text(detail).font(.brandBody(15)).foregroundStyle(Color.brandPaper) }
            if !state.stages.isEmpty {
                HStack(spacing: 8) { ForEach(state.stages, id: \.label) { StageChipView(chip: $0) } }
            }
            if state.phase == .running {
                if let fraction = state.progressFraction {
                    ProgressView(value: fraction.doubleValue).tint(Color.brandCyan)
                } else {
                    ProgressView().progressViewStyle(.linear).tint(Color.brandCyan)
                }
                if let progress = state.progressLabel {
                    Text(progress).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
            }
            if !state.badges.isEmpty {
                HStack(spacing: 8) { ForEach(state.badges, id: \.self) { BadgeView(text: $0) } }
            }
            ForEach(state.notes, id: \.self) { Text($0).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }

            if state.canWatch {
                Button(state.watchLabel) {
                    // Our chapter id: a source's chapter plays its copy on the server.
                    player = PlayerRoute(
                        chapterId: state.chapterId,
                        nextChapterId: route.title.nextChapterId(chapterId: route.chapterId),
                        language: state.language
                    )
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            if state.canPrepare {
                if state.canWatch {
                    Button(state.prepareLabel) { model.send(EpisodeIntentPrepare.shared) }
                        .buttonStyle(GhostButtonStyle())
                        .disabled(state.working)
                } else {
                    Button(state.prepareLabel) { model.send(EpisodeIntentPrepare.shared) }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(state.working)
                        .opacity(state.working ? 0.5 : 1)
                }
            }
            if state.canReread {
                Button(state.rereadLabel) { model.send(EpisodeIntentReread.shared) }
                    .buttonStyle(GhostButtonStyle())
                    .disabled(state.working)
            }
            if let cost = state.costLine {
                HStack(spacing: 6) {
                    Circle().fill(Color.brandCyan).frame(width: 6, height: 6)
                    Text(cost).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandPaper)
                }
            }
            if state.showsKeyNotice {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(state.keyLines, id: \.self) { Text($0).font(.brandBody(13)).foregroundStyle(Color.brandPaper) }
                    if let warning = state.keyWarning { Text(warning).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
                    Text(state.costNote).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
            }
            if state.exportShown { video(state) }
            if let notice = state.notice { Text(notice).font(.brandBody(13)).foregroundStyle(Color.brandPaper) }
            if let error = state.error { ErrorText(text: error) }
            if state.phase == .unavailable {
                Button(L("common.tryAgain")) { model.send(EpisodeIntentRefresh.shared) }.buttonStyle(GhostButtonStyle())
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(state.phase == .running ? Color.brandCyan : Color.brandLine, lineWidth: 1))
    }

    /// The episode's language: the original, or another one (dubbed when ElevenLabs speaks it, else subtitled).
    private func languageButton(_ state: EpisodeUiState) -> some View {
        Button {
            Haptics.tap()
            choosingLanguage = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "globe").font(.system(size: 14, weight: .bold))
                Text(state.languageLabel).font(.brandBody(15, weight: .bold))
                if let mode = state.languageMode {
                    Text(mode.uppercased()).font(.brandBody(11, weight: .bold)).tracking(1.2).foregroundStyle(Color.brandCyan)
                }
                Spacer()
                Image(systemName: "chevron.down").font(.system(size: 12, weight: .bold)).foregroundStyle(Color.brandMuted)
            }
            .foregroundStyle(Color.brandPaper)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(state.working)
        .accessibilityLabel(L("episode.languageA11y", ["language": state.languageLabel]))
    }

    /// The MP4: make it (CPU only, no key), follow its progress, then save or share it.
    private func video(_ state: EpisodeUiState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Rectangle().fill(Color.brandLine).frame(height: 1).padding(.vertical, 4)
            Text(L("episode.videoKicker")).font(.brandBody(12, weight: .bold)).tracking(1.4).foregroundStyle(Color.brandMuted)
            if let label = state.exportLabel { Text(label).font(.brandBody(16)).foregroundStyle(Color.brandPaper) }
            if let detail = state.exportDetail { Text(detail).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
            if let progress = state.exportProgress {
                ProgressView(value: progress.doubleValue).tint(Color.brandCyan)
            }
            if state.canRequestExport {
                Button(state.exportActionLabel) { model.send(EpisodeIntentRequestExport.shared) }
                    .buttonStyle(GhostButtonStyle())
                    .disabled(state.exportWorking)
            }
            if state.canSaveVideo {
                Button(state.saveVideoLabel) { model.send(EpisodeIntentSaveVideo.shared) }
                    .buttonStyle(GhostButtonStyle())
            }
        }
    }
}

/// The episode's languages, searchable (own name, English name or code); the rows come from `EpisodeViewModel`.
private struct EpisodeLanguageSheet: View {
    @ObservedObject var model: EpisodeModel

    var body: some View {
        EpisodeLanguageList(
            options: model.state.languageOptions,
            initialQuery: model.state.languageQuery,
            onQuery: { model.send(EpisodeIntentLanguageQuery(text: $0)) },
            onChoose: { model.send(EpisodeIntentChooseLanguage(code: $0)) }
        )
    }
}

/// A searchable list of an episode's languages (the card's picker and the player's): a pick closes it.
struct EpisodeLanguageList: View {
    let options: [EpisodeLanguageOption]
    let initialQuery: String
    let onQuery: (String) -> Void
    let onChoose: (String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                if options.isEmpty {
                    Text(L("common.noLanguage")).font(.brandBody(13)).foregroundStyle(Color.brandMuted).listRowBackground(Color.brandInk2)
                }
                ForEach(options, id: \.self) { option in
                    Button {
                        Haptics.select()
                        onChoose(option.code)
                        dismiss()
                    } label: {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(option.label).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                                Text(option.detail).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                            }
                            Spacer()
                            if option.selected { Image(systemName: "checkmark").foregroundStyle(Color.brandRed) }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(option.selected ? Color.brandLine : Color.brandInk2)
                    .accessibilityAddTraits(option.selected ? .isSelected : [])
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.brandInk2)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("common.searchLanguage"))
            .onChange(of: query) { _, text in onQuery(text) }
            .navigationTitle(L("episode.languageTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } }
            }
        }
        .onAppear { query = initialQuery }
        .onDisappear { onQuery("") }
    }
}

/// Pipeline chip: done (paper outline, ✓), active (cyan: work in progress), waiting (muted).
private struct StageChipView: View {
    let chip: StageChip

    var body: some View {
        let (fill, text, border): (Color, Color, Color) = {
            switch chip.state {
            case .done: return (.clear, .brandPaper, .brandPaper)
            case .active: return (.brandCyan, .brandInk, .brandCyan)
            default: return (.clear, .brandMuted, .brandLine)
            }
        }()
        Text(chip.state == .done ? "✓ \(chip.label)" : chip.label)
            .font(.brandBody(12, weight: .bold))
            .tracking(1.2)
            .foregroundStyle(text)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(fill, in: RoundedRectangle(cornerRadius: 4))
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(border, lineWidth: 1))
    }
}

private struct BadgeView: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.brandBody(12, weight: .bold))
            .foregroundStyle(Color.brandInk)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.brandYellow, in: RoundedRectangle(cornerRadius: 4))
    }
}

private struct LiveBadge: View {
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(Color.brandCyan).frame(width: 8, height: 8)
            Text(L("episode.live")).font(.brandBody(12, weight: .bold)).foregroundStyle(Color.brandCyan)
        }
    }
}

struct SharedVideo: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// Downloads a signed MP4 link to a temporary file named as the server signed it, for the share sheet.
enum VideoFile {
    static func download(_ link: String, as name: String) async -> URL? {
        guard let url = URL(string: link),
              let (file, response) = try? await URLSession.shared.download(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200
        else { return nil }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("videos", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: target)
        do {
            try FileManager.default.moveItem(at: file, to: target)
            return target
        } catch {
            return nil
        }
    }
}

/// The system share sheet: Save Video (Photos), Save to Files, AirDrop…
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
