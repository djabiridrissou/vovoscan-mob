import Shared
import SwiftUI

/// Source icon (https or `data:` URL), or its initial in a square when there is none or it fails to load.
struct SourceIcon: View {
    let iconUrl: String?
    let initial: String
    var size: CGFloat = 40

    var body: some View {
        ZStack {
            Color.brandInk
            if let url = iconUrl.flatMap(URL.init(string:)) {
                AsyncImage(url: url) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() } else { letter }
                }
            } else {
                letter
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
    }

    private var letter: some View {
        Text(initial).font(.brandDisplay(size * 0.45)).foregroundStyle(Color.brandPaper)
    }
}

/// "Official" / "Third-party" chip.
struct OriginChip: View {
    let label: String
    let thirdParty: Bool

    var body: some View {
        let color = thirdParty ? Color.brandYellow : Color.brandMuted
        Text(label)
            .font(.brandBody(12, weight: .bold))
            .lineLimit(1)
            .fixedSize()
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .overlay(Capsule().stroke(color, lineWidth: 1))
    }
}

/// Red "Update" badge, ink text.
struct UpdateBadge: View {
    var label = L("sources.update")

    var body: some View {
        Text(label)
            .font(.brandBody(12, weight: .bold))
            .lineLimit(1)
            .fixedSize()
            .foregroundStyle(Color.brandInk)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(Color.brandRed, in: Capsule())
    }
}

/// Small red action (row buttons): ink text on red.
struct SmallActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.brandBody(15, weight: .bold))
            .foregroundStyle(Color.brandInk)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(isEnabled ? Color.brandRed : Color.brandLine, in: RoundedRectangle(cornerRadius: 8))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// Short confirmation or error, dismissed with ✕.
struct MessageBanner: View {
    let text: String
    let error: Bool
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Text(text)
                .font(.brandBody(13))
                .foregroundStyle(error ? Color.brandDanger : Color.brandPaper)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 10)
            Button(action: onDismiss) { Text("✕").font(.brandBody(15)).foregroundStyle(Color.brandMuted).padding(14) }
                .buttonStyle(.plain)
        }
        .padding(.leading, 14)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(error ? Color.brandDanger : Color.brandLine, lineWidth: 1))
    }
}

/// Card: ink-2, 1 pt line border, radius 12.
struct SourceCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
    }
}

/// What a source says about itself: preview before install and detail screen.
struct SourceInfoBlock: View {
    let info: SourceInfoUi

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                SourceIcon(iconUrl: info.iconUrl, initial: info.initial, size: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text(info.name).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                    HStack(spacing: 8) {
                        Text(info.versionLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        OriginChip(label: SourceLabels.shared.chip(thirdParty: info.thirdParty), thirdParty: info.thirdParty)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(info.originLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                if let url = info.originUrl { Text(url).font(.brandBody(13)).foregroundStyle(Color.brandPaper.opacity(0.76)) }
            }
            if !info.summary.isEmpty { Text(info.summary).font(.brandBody(15)).foregroundStyle(Color.brandPaper) }
            let meta = [info.kindsLabel, info.langsLabel].filter { !$0.isEmpty }
            if !meta.isEmpty { Text(meta.joined(separator: " ✦ ")).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
            if info.rightsLabel != nil || info.rightsNotes != nil {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("sources.rights")).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                    if let label = info.rightsLabel { Text(label).font(.brandBody(15)).foregroundStyle(Color.brandPaper) }
                    if let notes = info.rightsNotes { Text(notes).font(.brandBody(13)).foregroundStyle(Color.brandPaper.opacity(0.76)) }
                    if let disclaimer = info.rightsDisclaimer { Text(disclaimer).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
                }
            }
            if info.hostsLabel != nil {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("sources.connectsTo")).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                    ForEach(info.hosts, id: \.self) { host in
                        Text("✦ \(host)").font(.brandBody(13)).foregroundStyle(Color.brandPaper.opacity(0.76))
                    }
                    if let rate = info.rateLabel { Text(rate).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
                }
            }
        }
    }
}

/// A setting field: keyboard by type, default value as a hint, help and error below. Each field keeps its own
/// text (`@State`, the IntentField pattern): a binding computed from a dictionary lets the TextField drift from the
/// value under fast typing. Every keystroke goes to `record` (the form's copy, resent on Save / Confirm); a value
/// Kotlin already has is not resent as an intent.
struct SettingInput: View {
    let field: SettingFieldUi
    let record: (String) -> Void
    let onChange: (String) -> Void
    @State private var text = ""

    var body: some View {
        IntentField(
            label: field.required ? "\(field.label) *" : field.label,
            text: $text,
            value: field.value,
            error: field.error,
            keyboard: keyboard,
            placeholder: field.placeholder,
            help: field.help
        ) {
            record($0)
            if $0 != field.value { onChange($0) }
        }
    }

    private var keyboard: UIKeyboardType {
        switch field.type {
        case .url: return .URL
        case .integer: return .numberPad
        case .text: return .default
        }
    }
}

/// A setting shown read-only (official source).
struct SettingValue: View {
    let field: SettingFieldUi

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(field.label).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            Text(field.value.isEmpty ? (field.placeholder ?? "—") : field.value).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
        }
    }
}
