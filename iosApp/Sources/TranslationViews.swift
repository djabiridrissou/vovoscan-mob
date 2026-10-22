import Shared
import SwiftUI

/// The lettering to draw over a page, by page index (the reader's language); empty for the original lettering.
private struct PageLetteringKey: EnvironmentKey {
    static let defaultValue: (Int) -> [Lettering] = { _ in [] }
}

extension EnvironmentValues {
    var pageLettering: (Int) -> [Lettering] {
        get { self[PageLetteringKey.self] }
        set { self[PageLetteringKey.self] = newValue }
    }
}

/// The translation set over a page: patches of the balloon's paint over the original letters, then the translated text
/// fitted in its box. Every rectangle is a fraction of the page (LetteringLayout, shared Kotlin): it follows any size
/// and zoom.
struct LetteringOverlay: View {
    let items: [Lettering]

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            ZStack(alignment: .topLeading) {
                ForEach(items, id: \.id) { item in
                    let paint = Color(hexString: item.fill)
                    ForEach(Array(item.patches.enumerated()), id: \.offset) { _, patch in
                        Group {
                            if patch.ellipse { Ellipse().fill(paint) } else { Rectangle().fill(paint) }
                        }
                        .frame(width: size.width * patch.rect.w, height: size.height * patch.rect.h)
                        .offset(x: size.width * patch.rect.x, y: size.height * patch.rect.y)
                    }
                    let box = CGSize(width: size.width * item.textRect.w, height: size.height * item.textRect.h)
                    Text(item.text)
                        .font(.custom("SpaceGrotesk-Bold", size: max(8, min(box.height * 0.42, 34))))
                        .foregroundStyle(Color(hexString: item.ink))
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.2)
                        .lineSpacing(-1)
                        .frame(width: box.width, height: box.height)
                        .offset(x: size.width * item.textRect.x, y: size.height * item.textRect.y)
                        .accessibilityLabel(item.text)
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
        }
        .allowsHitTesting(false)
        .transition(.opacity)
    }
}

extension Color {
    /// `#rrggbb` from the server (checked in shared Kotlin); anything else is white.
    init(hexString: String) {
        let digits = hexString.hasPrefix("#") ? String(hexString.dropFirst()) : hexString
        self.init(hex: UInt32(digits, radix: 16) ?? 0xFFFFFF)
    }
}

/// The reader's language button: the language in use, red while a translation shows, cyan while one is made.
struct LanguageButton: View {
    @ObservedObject var translation: TranslationModel
    let action: () -> Void

    var body: some View {
        let state = translation.state
        Button(action: action) {
            HStack(spacing: 6) {
                if state.working {
                    ProgressView().controlSize(.mini).tint(Color.brandCyan)
                } else {
                    Image(systemName: "character.bubble").font(.system(size: 13, weight: .bold))
                }
                Text(state.buttonLabel).lineLimit(1)
            }
            .font(.brandBody(13, weight: .bold))
            .foregroundStyle(state.translated ? Color.brandInk : Color.brandPaper)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(state.translated ? Color.brandRed : Color.brandInk2, in: Capsule())
            .overlay(Capsule().stroke(state.working ? Color.brandCyan : (state.translated ? Color.brandRed : Color.brandLine), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("translation.languageA11y", ["language": state.buttonLabel]))
    }
}

/// The language picker: searchable, languages already made first ("Ready": free, shared), then every other one. A pick
/// closes it at once: a language already made swaps in, a new one is quoted in the dock over the page (reader SPEC §11).
struct LanguageSheet: View {
    @ObservedObject var translation: TranslationModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let state = translation.state
        NavigationStack {
            List {
                ForEach(state.options, id: \.self) { option in
                    Button {
                        Haptics.select()
                        translation.send(TranslationIntentChoose(code: option.code))
                        dismiss()
                    } label: {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(option.label).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                                if !option.englishName.isEmpty {
                                    Text(option.englishName).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                                }
                            }
                            Spacer()
                            if let status = option.statusLabel {
                                Text(status).font(.brandBody(12, weight: .bold))
                                    .foregroundStyle(option.ready ? Color.brandYellow : Color.brandCyan)
                            }
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
            .onChange(of: query) { _, text in translation.send(TranslationIntentQuery(value: text)) }
            .navigationTitle(L("translation.languageTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } }
            }
        }
        .onAppear { query = state.query }
    }
}

/// What a dock shows, from either shared state (the reader's `TranslationDock`, the player's `PlayerLanguageDock`).
struct LanguageDockContent {
    let title: String
    let line: String?
    let progress: Float?
    let busy: Bool
    let confirmLabel: String?
    let addKeyLabel: String?
    let retryLabel: String?
    let closeLabel: String
    let failed: Bool
}

/// The panel docked at the bottom while a language is on its way (reader SPEC §11): its cost, then a bar while the
/// reader (or the episode) goes on, or why it failed. The page under it keeps what it shows.
struct LanguageDock: View {
    let content: LanguageDockContent
    let confirm: () -> Void
    let addKey: () -> Void
    let retry: () -> Void
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                if content.busy { ProgressView().controlSize(.small).tint(Color.brandCyan) }
                Text(content.title).font(.brandDisplay(17)).foregroundStyle(Color.brandPaper)
                Spacer(minLength: 0)
            }
            if let line = content.line {
                Text(line).font(.brandBody(14))
                    .foregroundStyle(content.failed || (content.confirmLabel == nil && content.addKeyLabel != nil) ? Color.brandYellow : Color.brandPaper)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let progress = content.progress {
                ProgressView(value: Double(progress)).tint(Color.brandCyan)
            }
            HStack(spacing: 10) {
                if let label = content.confirmLabel {
                    Button(label) { Haptics.press(); confirm() }.buttonStyle(PrimaryButtonStyle())
                }
                if let label = content.retryLabel {
                    Button(label, action: retry).buttonStyle(PrimaryButtonStyle())
                }
                if let label = content.addKeyLabel {
                    Button(label, action: addKey).buttonStyle(GhostButtonStyle())
                }
                Button(content.closeLabel, action: close)
                    .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted)
            }
        }
        .padding(16)
        .frame(maxWidth: 520, alignment: .leading)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(content.busy || content.progress != nil ? Color.brandCyan : Color.brandLine, lineWidth: 1.5))
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .accessibilityElement(children: .contain)
    }
}

extension TranslationDock {
    var content: LanguageDockContent {
        LanguageDockContent(
            title: title, line: line, progress: progress?.floatValue, busy: busy, confirmLabel: confirmLabel,
            addKeyLabel: addKeyLabel, retryLabel: retryLabel, closeLabel: closeLabel, failed: failed
        )
    }
}

extension PlayerLanguageDock {
    var content: LanguageDockContent {
        LanguageDockContent(
            title: title, line: line, progress: progress?.floatValue, busy: busy, confirmLabel: startLabel,
            addKeyLabel: addKeyLabel, retryLabel: retryLabel, closeLabel: closeLabel, failed: failed
        )
    }
}

/// The end card's "Read it in another language", set by the reader for chapters that can be translated.
struct ReaderLanguageAction {
    let label: String
    let open: () -> Void
}

private struct ReaderLanguageActionKey: EnvironmentKey {
    static let defaultValue: ReaderLanguageAction? = nil
}

extension EnvironmentValues {
    var readerLanguageAction: ReaderLanguageAction? {
        get { self[ReaderLanguageActionKey.self] }
        set { self[ReaderLanguageActionKey.self] = newValue }
    }
}
