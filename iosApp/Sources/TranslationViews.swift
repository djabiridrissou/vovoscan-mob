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

/// The language picker: searchable, languages already made first ("Ready": free, shared), then every other one.
struct LanguageSheet: View {
    @ObservedObject var translation: TranslationModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    /** "Add a key" from an offer: the keys, pushed inside the sheet (the reader stays where it was). */
    @State private var keysOpen = false

    var body: some View {
        let state = translation.state
        NavigationStack {
            List {
                if let checking = state.checking {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small).tint(Color.brandCyan)
                        Text(checking).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    }
                    .listRowBackground(Color.brandInk2)
                }
                if let offer = state.offer {
                    OfferCard(offer: offer, translate: {
                        Haptics.press()
                        translation.send(TranslationIntentConfirmOffer.shared)
                    }, cancel: {
                        translation.send(TranslationIntentDismissOffer.shared)
                    }, addKey: { keysOpen = true })
                    .listRowBackground(Color.brandInk2)
                }
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
                        Button(L("common.tryAgain")) { translation.send(TranslationIntentRetry.shared) }
                            .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandRed)
                    }
                    .listRowBackground(Color.brandInk2)
                }
                ForEach(state.options, id: \.self) { option in
                    Button {
                        Haptics.select()
                        translation.send(TranslationIntentChoose(code: option.code))
                        // A language already made shows at once; a new one keeps the sheet for its cost and progress.
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
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("common.searchLanguage"))
            .onChange(of: query) { _, text in translation.send(TranslationIntentQuery(value: text)) }
            .navigationTitle(L("translation.languageTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } }
            }
            .navigationDestination(isPresented: $keysOpen) { KeysScreen() }
        }
        .onAppear { query = state.query }
        .onDisappear {
            translation.send(TranslationIntentDismissMessage.shared)
            translation.send(TranslationIntentDismissOffer.shared)
        }
    }
}

/// "Translate into Yorùbá?": what it costs this account, then Translate / Add a key / Not now. Nothing is paid before.
private struct OfferCard: View {
    let offer: TranslationOffer
    let translate: () -> Void
    let cancel: () -> Void
    let addKey: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(offer.title).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
            Text(offer.line).font(.brandBody(14)).foregroundStyle(offer.canTranslate ? Color.brandPaper : Color.brandYellow)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                if offer.canTranslate {
                    Button(offer.translateLabel, action: translate).buttonStyle(PrimaryButtonStyle())
                }
                if let label = offer.addKeyLabel {
                    Button(label, action: addKey).buttonStyle(GhostButtonStyle())
                }
                Button(offer.cancelLabel, action: cancel)
                    .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted)
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .contain)
    }
}
