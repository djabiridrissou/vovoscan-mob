import Shared
import SwiftUI

/// Book settings (gear in the top bar): text size, line spacing, margins, font, justification, the page's colours.
/// Every change is applied to the page behind the sheet at once; the choices come ready from `ReaderUiState.book`.
struct BookSettingsSheetView: View {
    @ObservedObject var model: ReaderModel

    var body: some View {
        let sheet = model.state.book.settings
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(BookText.shared.SETTINGS_TITLE).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)

                section(BookText.shared.TEXT_SIZE)
                HStack(spacing: 12) {
                    step("A−", size: 15, label: BookText.shared.SMALLER, enabled: sheet.textSize.canShrink) {
                        model.send(BookIntentTextSmaller.shared)
                    }
                    Text(sheet.textSize.label).font(.brandBody(15)).foregroundStyle(Color.brandPaper).frame(width: 64)
                    step("A+", size: 19, label: BookText.shared.BIGGER, enabled: sheet.textSize.canGrow) {
                        model.send(BookIntentTextBigger.shared)
                    }
                    if sheet.textSize.canReset {
                        Button(BookText.shared.RESET) { model.send(BookIntentResetTextSize.shared) }.buttonStyle(ChipStyle(selected: false))
                    }
                }
                help(BookText.shared.TEXT_SIZE_HELP)

                section(BookText.shared.LINE_SPACING)
                HStack(spacing: 8) {
                    ForEach(sheet.lineSpacings, id: \.label) { option in
                        Button(option.label) { model.send(BookIntentSetLineSpacing(spacing: option.spacing)) }
                            .buttonStyle(ChipStyle(selected: option.selected))
                    }
                }

                section(BookText.shared.MARGINS)
                HStack(spacing: 8) {
                    ForEach(sheet.margins, id: \.label) { option in
                        Button(option.label) { model.send(BookIntentSetMargins(margins: option.margins)) }
                            .buttonStyle(ChipStyle(selected: option.selected))
                    }
                }

                section(BookText.shared.FONT)
                HStack(spacing: 8) {
                    ForEach(sheet.fonts, id: \.label) { option in
                        // Each font's name is written in that font.
                        Button { model.send(BookIntentSetFont(font: option.font)) } label: {
                            Text(option.label).font(.system(size: 15, design: option.font == .serif ? .serif : .default))
                        }
                        .buttonStyle(FontChipStyle(selected: option.selected))
                    }
                }

                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(BookText.shared.JUSTIFY).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                        help(BookText.shared.JUSTIFY_HELP)
                    }
                    Spacer()
                    Toggle("", isOn: Binding(get: { sheet.justify }, set: { model.send(BookIntentSetJustify(justify: $0)) }))
                        .labelsHidden().tint(Color.brandRed)
                }
                .padding(.top, 8)

                section(BookText.shared.THEME)
                HStack(spacing: 10) {
                    ForEach(sheet.themes, id: \.label) { option in
                        Button { model.send(BookIntentSetTheme(theme: option.theme)) } label: {
                            VStack(spacing: 2) {
                                Text("Aa").font(.brandDisplay(16))
                                Text(option.label).font(.brandBody(13))
                            }
                            .foregroundStyle(Color(hex: UInt32(truncatingIfNeeded: option.text)))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color(hex: UInt32(truncatingIfNeeded: option.background)), in: RoundedRectangle(cornerRadius: 12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(option.selected ? Color.brandRed : Color.brandLine, lineWidth: option.selected ? 2 : 1)
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(option.selected ? .isSelected : [])
                    }
                }
                help(BookText.shared.THEME_HELP)
            }
            .padding(20)
        }
        .background(Color.brandInk2)
    }

    private func step(_ text: String, size: CGFloat, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.brandBody(size, weight: .bold)).foregroundStyle(enabled ? Color.brandPaper : Color.brandLine)
                .frame(width: 44, height: 44)
                .background(Color.brandInk, in: Circle())
                .overlay(Circle().stroke(enabled ? Color.brandPaper : Color.brandLine, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private func section(_ text: String) -> some View {
        Text(text).font(.brandDisplay(16)).foregroundStyle(Color.brandPaper).padding(.top, 10)
    }

    private func help(_ text: String) -> some View {
        Text(text).font(.brandBody(13)).foregroundStyle(Color.brandMuted).fixedSize(horizontal: false, vertical: true)
    }
}

/// A chip whose label keeps its own font (the font chooser), unlike `ChipStyle`.
private struct FontChipStyle: ButtonStyle {
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(selected ? Color.brandInk : Color.brandPaper)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(selected ? Color.brandPaper : Color.brandInk2, in: Capsule())
            .overlay(Capsule().stroke(selected ? Color.brandPaper : Color.brandLine, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// The book's contents (button in the top bar): its parts when it has several, its table of contents (nested), the
/// rows of the section being read marked in red. A tap opens the row and closes the sheet.
struct BookContentsSheet: View {
    @ObservedObject var model: ReaderModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let rows = model.state.book.contents
        VStack(alignment: .leading, spacing: 8) {
            Text(BookText.shared.CONTENTS).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                .padding(.horizontal, 20).padding(.top, 20)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(rows, id: \.key) { row in
                            Button {
                                model.send(BookIntentChooseContents(key: row.key))
                                dismiss()
                            } label: {
                                HStack(spacing: 10) {
                                    Rectangle().fill(row.current && !row.part ? Color.brandRed : Color.clear).frame(width: 3, height: 22)
                                    Text(row.label)
                                        .font(row.part ? .brandDisplay(16) : .brandBody(15, weight: row.current ? .bold : .regular))
                                        .foregroundStyle(row.part && row.current ? Color.brandRed : (row.current || row.part ? Color.brandPaper : Color.brandPaper.opacity(0.76)))
                                        .lineLimit(2)
                                        .multilineTextAlignment(.leading)
                                    Spacer(minLength: 0)
                                }
                                .padding(.leading, 20 + 16 * CGFloat(row.depth))
                                .padding(.trailing, 20)
                                .frame(minHeight: 48)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(!row.enabled)
                            .accessibilityAddTraits(row.current ? .isSelected : [])
                            .id(row.key)
                        }
                    }
                    .padding(.bottom, 16)
                }
                // Opens on the section being read.
                .onAppear {
                    if let here = rows.first(where: { $0.current && !$0.part }) { proxy.scrollTo(here.key, anchor: .center) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2)
    }
}
