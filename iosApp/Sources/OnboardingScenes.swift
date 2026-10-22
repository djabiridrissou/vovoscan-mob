import Shared
import SwiftUI

// The tutorial's scenes (OnboardingView): a cold open of panels stamped one after the other, a live translated
// page, the wallet counting up, a real title to start with. Pure drawing and motion; the words and data come from
// OnboardingViewModel. Under Reduce Motion every scene shows its final state at once.

/// The cold open: a manga page whose three panels slam in one after the other (an impact each), the mark stamped
/// last with a shake, over speed lines. About 1.6 s.
struct ColdOpen: View {
    let line: String
    let balloonFrom: String
    let balloonTo: String
    @State private var beat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            page
                .frame(height: 300)
                .modifier(Shake(amount: beat >= 4 && !reduceMotion ? 1 : 0))
            Text(line)
                .font(.brandDisplay(22))
                .foregroundStyle(Color.brandYellow)
                .opacity(beat >= 4 ? 1 : 0)
                .offset(y: beat >= 4 ? 0 : 12)
        }
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.9), trigger: beat)
        .task {
            guard !reduceMotion else { beat = 5; return }
            for next in 1...5 {
                try? await Task.sleep(for: .milliseconds(next == 1 ? 180 : 300))
                withAnimation(.spring(response: 0.32, dampingFraction: 0.58)) { beat = next }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(line)
    }

    private var page: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            ZStack(alignment: .topLeading) {
                // Panel 1: the wide top band, a rumble.
                Panel(slant: 0.06) {
                    ZStack {
                        HalftoneBackground(color: .brandInk, spacing: 9, maxOpacity: 0.5)
                        Text("ゴゴゴ").font(.brandDisplay(40)).foregroundStyle(Color.brandInk).rotationEffect(.degrees(-8))
                    }
                }
                .frame(width: w, height: h * 0.42)
                .slam(beat >= 1, from: 1.3)
                // Panel 2: the balloon, read in Japanese then lettered again in the reader's language.
                Panel(slant: -0.05) {
                    ZStack {
                        SpeedLines(center: UnitPoint(x: 0.2, y: 0.5), count: 40, color: .brandInk).opacity(0.5)
                        BalloonText(text: beat >= 3 ? balloonTo : balloonFrom, size: 18)
                            .padding(10)
                            .contentTransition(.opacity)
                    }
                }
                .frame(width: w * 0.58, height: h * 0.55)
                .offset(y: h * 0.45)
                .slam(beat >= 2, from: 1.25)
                // Panel 3: the episode, voiced.
                Panel(slant: 0.04, fill: .brandRed) {
                    VStack(spacing: 6) {
                        Image(systemName: "waveform").font(.system(size: 34, weight: .black)).foregroundStyle(Color.brandInk)
                        Image(systemName: "play.fill").font(.system(size: 22, weight: .black)).foregroundStyle(Color.brandPaper)
                    }
                }
                .frame(width: w * 0.39, height: h * 0.55)
                .offset(x: w * 0.61, y: h * 0.45)
                .slam(beat >= 3, from: 1.25)
                // The mark, stamped over the page.
                LogoMark(size: 74)
                    .scaleEffect(beat >= 4 ? 1 : 2.4)
                    .opacity(beat >= 4 ? 1 : 0)
                    .offset(x: w - 70, y: -22)
                Onomatopoeia(text: "ドン!", size: 34, color: .brandYellow)
                    .opacity(beat >= 5 ? 1 : 0)
                    .scaleEffect(beat >= 5 ? 1 : 0.4)
                    .offset(x: 6, y: h * 0.36)
            }
        }
    }
}

/// A panel: paper inside a hard ink outline, its bottom edge slanted like a cut page.
private struct Panel<Content: View>: View {
    let slant: CGFloat
    var fill: Color = .brandPaper
    @ViewBuilder let content: Content

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(fill)
            .clipShape(SlantedRect(slant: slant))
            .overlay(SlantedRect(slant: slant).stroke(Color.brandInk, lineWidth: 3))
            .background(SlantedRect(slant: slant).fill(Color.brandRed.opacity(0.55)).offset(x: 5, y: 5))
    }
}

/// A rectangle whose bottom edge rises or falls by `slant` of its height.
struct SlantedRect: Shape {
    let slant: CGFloat

    func path(in rect: CGRect) -> Path {
        let drop = rect.height * slant
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - max(0, drop)))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - max(0, -drop)))
        path.closeSubpath()
        return path
    }
}

private extension View {
    /// Stamped in: from larger and see-through to its place, with a small tilt.
    func slam(_ on: Bool, from scale: CGFloat) -> some View {
        scaleEffect(on ? 1 : scale).rotationEffect(.degrees(on ? 0 : -4)).opacity(on ? 1 : 0)
    }
}

/// A short horizontal shake (the impact of the last stamp).
private struct Shake: GeometryEffect {
    var amount: CGFloat
    var animatableData: CGFloat {
        get { amount }
        set { amount = newValue }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 7 * sin(amount * .pi * 6) * (1 - amount), y: 0))
    }
}

/// A speech balloon's lettering: ink on white, in the display font.
private struct BalloonText: View {
    let text: String
    var size: CGFloat = 20

    var body: some View {
        Text(text)
            .font(.brandDisplay(size))
            .foregroundStyle(Color.brandInk)
            .multilineTextAlignment(.center)
            .lineLimit(2)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Ellipse().fill(Color.white))
            .overlay(Ellipse().stroke(Color.brandInk, lineWidth: 2.5))
    }
}

/// The live demo of a translated page: a panel whose balloon is lettered in Japanese, then painted over and lettered
/// again in the reading language (the wipe runs in its reading direction), on a loop; a new language restarts it at
/// once.
struct TranslatedPageDemo: View {
    let kicker: String
    let balloon: DemoBalloon
    @State private var progress: CGFloat = 0
    @State private var flips = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Circle().fill(Color.brandRed).frame(width: 8, height: 8)
                    .opacity(reduceMotion ? 1 : (flips.isMultiple(of: 2) ? 1 : 0.35))
                Text(kicker.uppercased()).font(.brandBody(12, weight: .bold)).tracking(1.4).foregroundStyle(Color.brandMuted)
            }
            ZStack {
                Color.brandPaper
                HalftoneBackground(color: .brandInk, spacing: 10, maxOpacity: 0.35)
                SpeedLines(center: UnitPoint(x: 0.85, y: 0.8), count: 48, color: .brandInk).opacity(0.35)
                ZStack {
                    BalloonText(text: balloon.original, size: 26)
                        .mask(Wipe(progress: 1 - progress, fromLeading: balloon.rtl))
                    BalloonText(text: balloon.translated, size: 24)
                        .environment(\.layoutDirection, balloon.rtl ? .rightToLeft : .leftToRight)
                        .mask(Wipe(progress: progress, fromLeading: !balloon.rtl))
                }
                .padding(18)
            }
            .frame(height: 190)
            .clipShape(SlantedRect(slant: 0.05))
            .overlay(SlantedRect(slant: 0.05).stroke(Color.brandInk, lineWidth: 3))
            .background(SlantedRect(slant: 0.05).fill(Color.brandRed).offset(x: 6, y: 6))
            Text(balloon.caption).font(.brandBody(13)).foregroundStyle(Color.brandPaper.opacity(0.8))
        }
        .sensoryFeedback(.impact(weight: .light), trigger: flips)
        .task(id: balloon.translated) { await loop() }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(balloon.original) → \(balloon.translated). \(balloon.caption)")
    }

    private func loop() async {
        if reduceMotion {
            progress = 1
            return
        }
        progress = 0
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(900))
            if Task.isCancelled { return }
            withAnimation(.easeInOut(duration: 0.45)) { progress = 1 }
            flips += 1
            try? await Task.sleep(for: .milliseconds(2400))
            if Task.isCancelled { return }
            withAnimation(.easeInOut(duration: 0.3)) { progress = 0 }
            flips += 1
        }
    }
}

/// A mask revealing `progress` of the view from one side.
private struct Wipe: View {
    let progress: CGFloat
    let fromLeading: Bool

    var body: some View {
        GeometryReader { geo in
            Rectangle()
                .frame(width: geo.size.width * max(0, min(1, progress)))
                .frame(maxWidth: .infinity, alignment: fromLeading ? .leading : .trailing)
        }
    }
}

/// The wallet as the credits card shows it: the balance counting up from zero, or "own keys" stamped.
struct TourWallet: View {
    let kicker: String
    let balance: Int64?
    let ownKeysLine: String?
    let label: (Int64) -> String
    @State private var shown: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(kicker.uppercased()).font(.brandBody(12, weight: .bold)).tracking(1.4).foregroundStyle(Color.brandInk.opacity(0.7))
                Spacer()
                Image(systemName: ownKeysLine != nil ? "key.fill" : "creditcard.fill").foregroundStyle(Color.brandInk)
            }
            if let ownKeysLine {
                Text(ownKeysLine).font(.brandDisplay(20)).foregroundStyle(Color.brandInk)
            } else if balance != nil {
                CountingLabel(value: shown, label: label).font(.brandDisplay(26)).foregroundStyle(Color.brandInk)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandYellow, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandInk, lineWidth: 2.5))
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.brandPaper).offset(x: 6, y: 6))
        .rotationEffect(.degrees(-1.5))
        .onAppear { count() }
        .onChange(of: balance) { _, _ in count() }
    }

    private func count() {
        guard let balance else { return }
        if reduceMotion {
            shown = Double(balance)
        } else {
            shown = 0
            withAnimation(.easeOut(duration: 1.1)) { shown = Double(balance) }
        }
    }
}

/// A number interpolated frame by frame, written through the view model's label ("Balance: 120 credits").
private struct CountingLabel: View, Animatable {
    var value: Double
    let label: (Int64) -> String
    var animatableData: Double {
        get { value }
        set { value = newValue }
    }

    var body: some View {
        Text(label(Int64(value.rounded()))).monospacedDigit()
    }
}

/// The real title the tour ends on: its cover, tilted, rising in.
struct FeaturedCover: View {
    let kicker: String
    let title: OnboardingTitle
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(alignment: .bottom, spacing: 16) {
            AsyncImage(url: title.coverUrl.flatMap(URL.init(string:))) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                ZStack {
                    Color.brandInk2
                    LogoMark(size: 40)
                }
            }
            .frame(width: 120, height: 180)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandPaper, lineWidth: 2))
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.brandRed).offset(x: 6, y: 6))
            .rotationEffect(.degrees(shown ? -3 : 6))
            .offset(y: shown ? 0 : 40)
            .opacity(shown ? 1 : 0)
            VStack(alignment: .leading, spacing: 6) {
                Text(kicker.uppercased()).font(.brandBody(12, weight: .bold)).tracking(1.4).foregroundStyle(Color.brandCyan)
                Text(title.name).font(.brandDisplay(24)).foregroundStyle(Color.brandPaper).lineLimit(3)
            }
            .opacity(shown ? 1 : 0)
        }
        .onAppear {
            if reduceMotion { shown = true } else { withAnimation(.spring(response: 0.5, dampingFraction: 0.7).delay(0.15)) { shown = true } }
        }
        .accessibilityElement(children: .combine)
    }
}
