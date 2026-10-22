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
                        .font(.custom("SpaceGrotesk-Regular", size: max(8, min(box.height * 0.42, 34))).weight(.bold))
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
        .accessibilityLabel("Language: \(state.buttonLabel)")
    }
}

/// The language picker: searchable, languages already made first ("Ready": free, shared), then every other one.
struct LanguageSheet: View {
    @ObservedObject var translation: TranslationModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let state = translation.state
        NavigationStack {
            List {
                if let progress = state.progressLabel {
                    Label(progress, systemImage: "waveform").font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandCyan)
                        .listRowBackground(Color.brandInk2)
                }
                if let notice = state.notice {
                    Text(notice).font(.brandBody(13)).foregroundStyle(Color.brandPaper).listRowBackground(Color.brandInk2)
                }
                if let error = state.error {
                    VStack(alignment: .leading, spacing: 8) {
                        ErrorText(text: error)
                        Button("Try again") { translation.send(TranslationIntentRetry.shared) }
                            .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandRed)
                    }
                    .listRowBackground(Color.brandInk2)
                }
                ForEach(state.options, id: \.self) { option in
                    Button {
                        Haptics.select()
                        translation.send(TranslationIntentChoose(code: option.code))
                        // A language already made shows at once; one being made keeps the sheet for its progress.
                        if option.ready { dismiss() }
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
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search a language")
            .onChange(of: query) { _, text in translation.send(TranslationIntentQuery(value: text)) }
            .navigationTitle("Language")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .onAppear { query = state.query }
        .onDisappear { translation.send(TranslationIntentDismissMessage.shared) }
    }
}
