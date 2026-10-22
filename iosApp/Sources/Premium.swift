import SwiftUI
import UIKit

// The finish of the app: printed-manga texture (halftone, speed lines), stickers, haptics, skeletons while loading,
// cards that press like paper, and empty states that tell what to do next. Brand rules (brand/BRAND.md): ink background,
// red = action, cyan = work in progress, yellow = highlight; hard offset shadows, no blur; reduced motion respected.

/// Haptics, one vocabulary for the whole app.
enum Haptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func press() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
    static func select() { UISelectionFeedbackGenerator().selectionChanged() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
}

/// Halftone dots fading from a corner, as on the landing page: drawn once, no image.
struct HalftoneBackground: View {
    var color: Color = .brandPaper
    var spacing: CGFloat = 12
    var maxOpacity: Double = 0.10

    var body: some View {
        Canvas { context, size in
            let diagonal = max(1, hypot(size.width, size.height))
            var y: CGFloat = 0
            var row = 0
            while y < size.height + spacing {
                var x: CGFloat = row.isMultiple(of: 2) ? 0 : spacing / 2
                while x < size.width + spacing {
                    // Larger dots in the top right corner, vanishing towards the bottom left.
                    let strength = max(0, 1 - hypot(size.width - x, y) / diagonal * 1.6)
                    if strength > 0.02 {
                        let radius = spacing * 0.42 * strength
                        let dot = CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2)
                        context.fill(Path(ellipseIn: dot), with: .color(color.opacity(maxOpacity * (0.4 + 0.6 * strength))))
                    }
                    x += spacing
                }
                y += spacing * 0.866
                row += 1
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Speed lines bursting from a point (manga focus lines), for heroes and empty states.
struct SpeedLines: View {
    var center = UnitPoint(x: 0.5, y: 0.45)
    var count = 56
    var color: Color = .brandPaper

    var body: some View {
        Canvas { context, size in
            let origin = CGPoint(x: size.width * center.x, y: size.height * center.y)
            let reach = hypot(size.width, size.height)
            var generator = SeededRandom(seed: 7)
            for index in 0..<count {
                let angle = Double(index) / Double(count) * 2 * .pi + generator.next() * 0.08
                let inner = reach * (0.22 + generator.next() * 0.12)
                let width = 0.004 + generator.next() * 0.010
                var wedge = Path()
                wedge.move(to: CGPoint(x: origin.x + cos(angle) * inner, y: origin.y + sin(angle) * inner))
                wedge.addLine(to: CGPoint(x: origin.x + cos(angle - width) * reach, y: origin.y + sin(angle - width) * reach))
                wedge.addLine(to: CGPoint(x: origin.x + cos(angle + width) * reach, y: origin.y + sin(angle + width) * reach))
                wedge.closeSubpath()
                context.fill(wedge, with: .color(color.opacity(0.05 + generator.next() * 0.05)))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Deterministic noise: the same lines on every draw (no flicker when SwiftUI redraws).
private struct SeededRandom {
    private var state: UInt64
    init(seed: UInt64) { state = seed &* 6364136223846793005 &+ 1442695040888963407 }
    mutating func next() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double(state >> 11) / Double(1 << 53)
    }
}

/// Yellow sticker in the display font, tilted 8°, hard ink shadow (brand "Sticker").
struct Sticker: View {
    let text: String
    var color: Color = .brandYellow
    var angle: Double = 8

    var body: some View {
        Text(text)
            .font(.brandDisplay(14))
            .foregroundStyle(Color.brandInk)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(color)
            .overlay(Rectangle().stroke(Color.brandInk, lineWidth: 2))
            .background(Rectangle().fill(Color.brandInk).offset(x: 3, y: 3))
            .rotationEffect(.degrees(angle))
    }
}

/// A big onomatopoeia in the display font, outlined, for empty states.
struct Onomatopoeia: View {
    let text: String
    var size: CGFloat = 56
    var color: Color = .brandRed

    var body: some View {
        ZStack {
            Text(text).font(.brandDisplay(size)).foregroundStyle(Color.brandPaper).offset(x: 4, y: 4)
            Text(text).font(.brandDisplay(size)).foregroundStyle(color)
        }
        .rotationEffect(.degrees(-6))
        .accessibilityHidden(true)
    }
}

/// What to do when a screen has nothing yet: a sound effect, one sentence, one action.
struct EmptyStateCard: View {
    let sfx: String
    let title: String
    let message: String
    var actionLabel: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Onomatopoeia(text: sfx)
            Text(title).font(.brandDisplay(24)).foregroundStyle(Color.brandPaper)
            Text(message).font(.brandBody(15)).foregroundStyle(Color.brandPaper.opacity(0.76))
            if let actionLabel, let action {
                Button(actionLabel, action: action).buttonStyle(PrimaryButtonStyle()).padding(.top, 4)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            ZStack {
                Color.brandInk2
                SpeedLines(center: UnitPoint(x: 0.15, y: 0.1))
                HalftoneBackground(spacing: 10, maxOpacity: 0.08)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.brandLine, lineWidth: 1))
    }
}

/// A card that sinks when pressed (the paper is pushed into the page) with a light tap.
struct PressableCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .brightness(configuration.isPressed ? -0.04 : 0)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
            .onChange(of: configuration.isPressed) { _, pressed in if pressed { Haptics.tap() } }
            // iPad with a trackpad or a mouse: the card lifts under the pointer.
            .hoverEffect(.lift)
    }
}

/// A light band sweeping over a placeholder while it loads; still under Reduce Motion.
struct Shimmer: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var phase: CGFloat = -1

    func body(content: Content) -> some View {
        content
            .overlay {
                if !reduceMotion {
                    GeometryReader { geo in
                        LinearGradient(
                            colors: [.clear, Color.brandPaper.opacity(0.08), .clear],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                        .frame(width: geo.size.width * 0.6)
                        .offset(x: phase * geo.size.width * 1.6)
                    }
                    .clipped()
                }
            }
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.linear(duration: 1.3).repeatForever(autoreverses: false)) { phase = 1 }
            }
    }
}

extension View {
    func shimmering() -> some View { modifier(Shimmer()) }
}

/// The shape of a title tile while the catalog loads.
struct SkeletonTile: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Color.brandLine.aspectRatio(3 / 4, contentMode: .fit)
            VStack(alignment: .leading, spacing: 6) {
                RoundedRectangle(cornerRadius: 3).fill(Color.brandLine).frame(height: 12)
                RoundedRectangle(cornerRadius: 3).fill(Color.brandLine).frame(width: 70, height: 10)
            }
            .padding(12)
        }
        .background(Color.brandInk2)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 2))
        .shimmering()
        .accessibilityHidden(true)
    }
}

/// Content rising into place, one after the other (index × 40 ms); immediate under Reduce Motion.
struct RiseIn: ViewModifier {
    let index: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(shown || reduceMotion ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 14)
            .onAppear {
                guard !shown else { return }
                withAnimation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.45).delay(Double(min(index, 12)) * 0.04)) { shown = true }
            }
    }
}

extension View {
    func riseIn(_ index: Int = 0) -> some View { modifier(RiseIn(index: index)) }
}

/// Screen header: the kicker, then a display title. One look on every tab.
struct ScreenHeader: View {
    let kicker: String
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Kicker(text: kicker)
            Text(title).font(.brandDisplay(30)).foregroundStyle(Color.brandPaper).lineSpacing(-2)
            if let subtitle { Text(subtitle).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
        }
        .padding(.bottom, 4)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// The brand's on/off switch: a plain button (one tap = one intent; the state shown is the ViewModel's), red when on,
/// with a selection haptic and the switch trait for VoiceOver.
struct BrandSwitch: View {
    let isOn: Bool
    let label: String
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button {
            Haptics.select()
            action()
        } label: {
            ZStack(alignment: isOn ? .trailing : .leading) {
                Capsule().fill(isOn ? Color.brandRed : Color.brandLine)
                Circle()
                    .fill(Color.brandPaper)
                    .overlay(Circle().stroke(Color.brandInk, lineWidth: 1.5))
                    .padding(3)
            }
            .frame(width: 52, height: 32)
            .overlay(Capsule().stroke(Color.brandInk, lineWidth: 1.5))
            .animation(reduceMotion ? nil : .spring(response: 0.25, dampingFraction: 0.8), value: isOn)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityValue(isOn ? L("common.on") : L("common.off"))
        .accessibilityAddTraits(.isToggle)
    }
}
