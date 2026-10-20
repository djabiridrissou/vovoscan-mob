import Shared
import SwiftUI

/// Écran « Sources » : afficher ou masquer chaque source, et la ranger. Aucune logique ici (SourcesViewModel).
struct SourcesView: View {
    @StateObject private var model = SourcesModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Kicker(text: "Sources")
                Text("Choose what you read.").font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                Text("Sources read on your device are fetched by your phone, never through vovoscan.")
                    .font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                if let notice = model.state.pendingNotice { OfflineNotice(text: notice) }
                if let error = model.state.error { ErrorText(text: error) }
                ForEach(model.state.rows, id: \.key) { row in
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.name).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                            Text("\(row.kindsLabel) ✦ \(row.engineLabel)").font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        }
                        Spacer()
                        arrow("arrow.up", enabled: row.canMoveUp) { model.send(SourcesIntentMoveUp(key: row.key)) }
                        arrow("arrow.down", enabled: row.canMoveDown) { model.send(SourcesIntentMoveDown(key: row.key)) }
                        Toggle("", isOn: Binding(get: { row.enabled }, set: { _ in model.send(SourcesIntentToggle(key: row.key)) }))
                            .labelsHidden()
                            .tint(Color.brandRed)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
                }
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .refreshable { model.send(SourcesIntentRefresh.shared) }
        .task { await model.observe() }
    }

    private func arrow(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).foregroundStyle(enabled ? Color.brandPaper : Color.brandLine) }
            .disabled(!enabled)
            .padding(4)
    }
}

/// Sélecteur de source en tête du catalogue : les sources visibles, dans l'ordre choisi.
struct SourcePicker: View {
    let chips: [SourceChip]
    let selected: String
    let onSelect: (String) -> Void

    var body: some View {
        if chips.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(chips, id: \.key) { chip in
                        let active = chip.key == selected
                        Button { onSelect(chip.key) } label: {
                            Text(chip.name)
                                .font(.brandBody(15, weight: .bold))
                                .foregroundStyle(active ? Color.brandInk : Color.brandPaper)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(active ? Color.brandRed : Color.brandInk2, in: Capsule())
                                .overlay(Capsule().stroke(active ? Color.brandRed : Color.brandLine, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}
