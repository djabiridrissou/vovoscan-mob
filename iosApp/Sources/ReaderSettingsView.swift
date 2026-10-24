import Shared
import SwiftUI

/// Reader settings (gear in the top bar): every choice comes ready from `ReaderUiState.settings`. iOS has no volume
/// key setting: the volume buttons can't turn pages here.
struct ReaderSettingsSheetView: View {
    @ObservedObject var model: ReaderModel

    var body: some View {
        let sheet = model.state.settings
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(ReaderText.shared.SETTINGS_TITLE).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)

                section(ReaderText.shared.SERIES_MODE)
                ChipFlow {
                    ForEach(sheet.seriesModes, id: \.label) { option in
                        Button(option.label) { model.send(ReaderIntentSetMode(mode: option.mode)) }.buttonStyle(ChipStyle(selected: option.selected))
                    }
                }
                HStack {
                    help(sheet.seriesHint)
                    Spacer()
                    if sheet.canResetSeries {
                        Button(ReaderText.shared.RESET_SERIES) { model.send(ReaderIntentResetSeriesMode.shared) }.buttonStyle(ChipStyle(selected: false))
                    }
                }

                section(ReaderText.shared.DEFAULT_MODES)
                label(ReaderText.shared.MANGA)
                ChipFlow {
                    ForEach(sheet.mangaModes, id: \.label) { option in
                        Button(option.label) { model.send(ReaderIntentSetDefaultMode(format: .manga, mode: option.mode)) }
                            .buttonStyle(ChipStyle(selected: option.selected))
                    }
                }
                label(ReaderText.shared.WEBTOON)
                ChipFlow {
                    ForEach(sheet.webtoonModes, id: \.label) { option in
                        Button(option.label) { model.send(ReaderIntentSetDefaultMode(format: .webtoon, mode: option.mode)) }
                            .buttonStyle(ChipStyle(selected: option.selected))
                    }
                }

                section(ReaderText.shared.TAP_ZONES)
                ChipFlow {
                    ForEach(sheet.layouts, id: \.label) { option in
                        Button(option.label) { model.send(ReaderIntentSetTapLayout(layout: option.layout)) }.buttonStyle(ChipStyle(selected: option.selected))
                    }
                }
                label(ReaderText.shared.INVERT)
                ChipFlow {
                    ForEach(sheet.inverts, id: \.label) { option in
                        Button(option.label) { model.send(ReaderIntentSetInvert(invert: option.invert)) }.buttonStyle(ChipStyle(selected: option.selected))
                    }
                }
                toggle(ReaderText.shared.SHOW_TAP_ZONES, ReaderText.shared.SHOW_TAP_ZONES_HELP, sheet.showTapZones) {
                    model.send(ReaderIntentSetShowTapZones(show: $0))
                }

                section(ReaderText.shared.SCROLL_AMOUNT)
                ChipFlow {
                    ForEach(sheet.scrollAmounts, id: \.label) { option in
                        Button(option.label) { model.send(ReaderIntentSetScrollFraction(amount: option.amount)) }
                            .buttonStyle(ChipStyle(selected: option.selected))
                    }
                }
                help(ReaderText.shared.SCROLL_AMOUNT_HELP)

                toggle(ReaderText.shared.DOUBLE_TAP_ZOOM, ReaderText.shared.DOUBLE_TAP_ZOOM_HELP, sheet.doubleTapZoom) {
                    model.send(ReaderIntentSetDoubleTapZoom(enabled: $0))
                }
                toggle(ReaderText.shared.DOUBLE_PAGES, ReaderText.shared.DOUBLE_PAGES_HELP, sheet.doublePages) {
                    model.send(ReaderIntentSetDoublePages(enabled: $0))
                }
                toggle(ReaderText.shared.KEEP_AWAKE, ReaderText.shared.KEEP_AWAKE_HELP, sheet.keepAwake) {
                    model.send(ReaderIntentSetKeepAwake(enabled: $0))
                }
            }
            .padding(20)
        }
        .background(Color.brandInk2)
    }

    private func section(_ text: String) -> some View {
        Text(text).font(.brandDisplay(16)).foregroundStyle(Color.brandPaper).padding(.top, 10)
    }

    private func label(_ text: String) -> some View {
        Text(text).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
    }

    private func help(_ text: String) -> some View {
        Text(text).font(.brandBody(13)).foregroundStyle(Color.brandMuted).fixedSize(horizontal: false, vertical: true)
    }

    private func toggle(_ title: String, _ help: String, _ isOn: Bool, _ set: @escaping (Bool) -> Void) -> some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                self.help(help)
            }
            Spacer()
            Toggle("", isOn: Binding(get: { isOn }, set: set)).labelsHidden().tint(Color.brandRed)
        }
        .padding(.top, 8)
    }
}

/// Chips that wrap onto the next line.
private struct ChipFlow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = arrange(subviews, width: width)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var index = 0
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for _ in 0..<row.count {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(size))
                x += size.width + spacing
                index += 1
            }
        }
    }

    private struct Row { var count = 0; var width: CGFloat = 0; var height: CGFloat = 0; var y: CGFloat = 0 }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows = [Row()]
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            var row = rows[rows.count - 1]
            if row.count > 0 && row.width + spacing + size.width > width {
                let y = row.y + row.height + spacing
                rows.append(Row(count: 0, width: 0, height: 0, y: y))
                row = rows[rows.count - 1]
            }
            row.width += (row.count > 0 ? spacing : 0) + size.width
            row.height = max(row.height, size.height)
            row.count += 1
            rows[rows.count - 1] = row
        }
        return rows
    }
}
