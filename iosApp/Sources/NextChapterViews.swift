import Shared
import SwiftUI

/// What comes after a chapter of our catalog in the language on the page (`NextChapterViewModel`): the end card's way
/// on, the balloon that prepares ahead past 40 %, the toast when one is ready, the series setting.
@MainActor
final class NextChapterModel: ObservableObject {
    @Published private(set) var state: NextChapterUiState
    private let owner = ViewModels()
    private let viewModel: NextChapterViewModel

    init(route: ReaderRoute) {
        viewModel = owner.next(sourceKey: route.sourceKey, chapterId: route.chapterId)
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: NextChapterIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

/// The end card's way on while reading in a language, set by the reader; the card shows it in place of its own
/// "Next chapter".
struct ReaderNextChapter {
    let end: NextEndCard
    let auto: NextAutoSwitch?
    let tap: (NextButton) -> Void
    let setAuto: (Bool) -> Void
}

private struct ReaderNextChapterKey: EnvironmentKey {
    static let defaultValue: ReaderNextChapter? = nil
}

extension EnvironmentValues {
    var readerNextChapter: ReaderNextChapter? {
        get { self[ReaderNextChapterKey.self] }
        set { self[ReaderNextChapterKey.self] = newValue }
    }
}

/// Under "Finished: Chapter 3": always a way on (prepare at its price, follow it, the original, the latest for now).
struct NextEndContent: View {
    let next: ReaderNextChapter

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let note = next.end.note {
                Text(note).font(.brandBody(15)).foregroundStyle(Color.brandPaper).fixedSize(horizontal: false, vertical: true)
            }
            if let progress = next.end.progress {
                ProgressView(value: Double(progress.floatValue)).tint(Color.brandCyan)
            }
            if let primary = next.end.primary { NextButtonView(button: primary, primary: true, tap: next.tap) }
            if let secondary = next.end.secondary { NextButtonView(button: secondary, primary: false, tap: next.tap) }
            if let error = next.end.error {
                Text(error).font(.brandBody(13)).foregroundStyle(Color.brandYellow)
            }
            if let auto = next.auto { NextAutoRow(auto: auto, set: next.setAuto) }
        }
        .padding(.top, 4)
    }
}

private struct NextButtonView: View {
    let button: NextButton
    let primary: Bool
    let tap: (NextButton) -> Void

    var body: some View {
        // Without a key, the way on is the keys: a secondary action, never a red spend.
        if primary && button.kind != .addKey {
            Button(button.label) { Haptics.press(); tap(button) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!button.enabled)
        } else {
            Button(button.label) { tap(button) }
                .buttonStyle(GhostButtonStyle())
                .disabled(!button.enabled)
        }
    }
}

/// "Always prepare the next chapter of this series in Français".
struct NextAutoRow: View {
    let auto: NextAutoSwitch
    let set: (Bool) -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text(auto.label).font(.brandBody(13)).foregroundStyle(Color.brandMuted).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            BrandSwitch(isOn: auto.on, label: auto.label) { if auto.enabled { set(!auto.on) } }
        }
    }
}

/// The balloon past 40 %: what is being prepared (Cancel), then the next offer at its price. Taps stay on it.
struct NextBubbleView: View {
    let bubble: NextBubble
    let tap: (NextButton) -> Void
    let cancel: () -> Void
    let hide: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let line = bubble.busyLine {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small).tint(Color.brandCyan)
                    Text(line).font(.brandBody(13)).foregroundStyle(Color.brandPaper).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    if let label = bubble.cancelLabel {
                        Button(label, action: cancel).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted)
                    }
                }
                if let progress = bubble.progress {
                    ProgressView(value: Double(progress.floatValue)).tint(Color.brandCyan)
                }
            }
            if let line = bubble.offerLine {
                Text(line).font(.brandBody(14)).foregroundStyle(Color.brandPaper).fixedSize(horizontal: false, vertical: true)
            }
            if let offer = bubble.offer { NextButtonView(button: offer, primary: true, tap: tap) }
            if let error = bubble.error {
                Text(error).font(.brandBody(13)).foregroundStyle(Color.brandYellow)
            }
            HStack {
                Spacer()
                Button(bubble.hideLabel, action: hide).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted)
            }
        }
        .padding(14)
        .frame(maxWidth: 360, alignment: .leading)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(bubble.busyLine != nil ? Color.brandCyan : Color.brandYellow, lineWidth: 1.5))
        .contentShape(Rectangle())
        .onTapGesture {}
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

/// "Chapter 4 is ready in Français." with "Read it →"; the view model takes it away after 8 s.
struct NextToastView: View {
    let toast: NextToast
    let tap: (NextButton) -> Void
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Text(toast.line).font(.brandBody(14)).foregroundStyle(Color.brandPaper)
            Spacer(minLength: 0)
            Button(toast.button.label) { tap(toast.button) }.font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandRed)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: 520)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandCyan, lineWidth: 1.5))
        .contentShape(Rectangle())
        .onTapGesture(perform: dismiss)
        .padding(.horizontal, 16)
        .accessibilityElement(children: .combine)
    }
}
