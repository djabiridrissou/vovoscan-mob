import Shared
import SwiftUI

/// The first-launch tutorial, full screen over the tabs: welcome, language, reading, sources, credits, done. Skip on
/// every step, Back from the second; "Add a source" and "Add my keys" open those screens in its place and come back.
/// No logic here (OnboardingViewModel); every step saves at once.
struct OnboardingView: View {
    let onClose: () -> Void
    let openTab: (AppTab) -> Void
    /// The real title the tour ends on: opened in the catalog once the tutorial closes.
    let openTitle: (TitleRoute) -> Void
    @StateObject private var model = OnboardingModel()
    @State private var regionsOpen = false
    @State private var readingOpen = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let state = model.state
        NavigationStack {
            ZStack {
                Color.brandInk.ignoresSafeArea()
                if state.step == .welcome || state.step == .done {
                    SpeedLines(center: UnitPoint(x: 0.85, y: 0.18)).ignoresSafeArea().opacity(0.8)
                    HalftoneBackground(color: .brandRed, spacing: 11, maxOpacity: 0.16).ignoresSafeArea()
                }
                VStack(spacing: 0) {
                    topBar(state)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            step(state)
                        }
                        .padding(.horizontal, 24)
                        .padding(.vertical, 20)
                        .id(state.step)
                        .transition(reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                                                          removal: .opacity))
                    }
                    .scrollDismissesKeyboard(.interactively)
                    bottomBar(state)
                }
                .animation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.84), value: state.step)
            }
            .navigationBarHidden(true)
            .navigationDestination(isPresented: Binding(
                get: { state.open == .sources },
                set: { if !$0 { model.send(OnboardingIntentReturned.shared) } }
            )) { SourcesView() }
            .navigationDestination(isPresented: Binding(
                get: { state.open == .keys },
                set: { if !$0 { model.send(OnboardingIntentReturned.shared) } }
            )) { KeysScreen() }
        }
        .tint(Color.brandRed)
        .sensoryFeedback(.selection, trigger: state.step)
        .sensoryFeedback(.error, trigger: state.error)
        .sheet(isPresented: $regionsOpen) {
            RegionSheet(choice: state.language,
                        query: { model.send(OnboardingIntentRegionQuery(text: $0)) },
                        choose: { model.send(OnboardingIntentChooseRegion(code: $0)) })
        }
        .sheet(isPresented: $readingOpen) {
            ContentLanguageSheet(
                title: state.readingLabel,
                rows: state.readingRows,
                query: { model.send(OnboardingIntentReadingQuery(text: $0)) },
                choose: { model.send(OnboardingIntentChooseReading(code: $0)) }
            )
        }
        .onChange(of: state.finished) { _, finished in if finished { onClose() } }
        .task { await model.observe() }
    }

    // Chrome.

    private func topBar(_ state: OnboardingUiState) -> some View {
        HStack {
            if state.canGoBack {
                Button {
                    model.send(OnboardingIntentBack.shared)
                } label: {
                    Label(state.backLabel, systemImage: "chevron.left").labelStyle(.titleAndIcon)
                }
                .font(.brandBody(15, weight: .bold))
                .foregroundStyle(Color.brandPaper)
            } else {
                LogoMark(size: 28)
            }
            Spacer()
            if state.dot >= 0 { ProgressDots(count: Int(state.dots), current: Int(state.dot)) }
            Spacer()
            if state.showSkip {
                Button(state.skipLabel) { model.send(OnboardingIntentSkip.shared) }
                    .font(.brandBody(15, weight: .bold))
                    .foregroundStyle(Color.brandMuted)
            } else {
                Color.clear.frame(width: 28, height: 28)
            }
        }
        .padding(.horizontal, 20)
        .frame(height: 52)
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func bottomBar(_ state: OnboardingUiState) -> some View {
        VStack(spacing: 10) {
            if let error = state.error { ErrorText(text: error) }
            switch state.step {
            case .welcome:
                Button(state.startLabel) { model.send(OnboardingIntentNext.shared) }.buttonStyle(PrimaryButtonStyle())
            case .sources:
                Button(state.nextLabel) { model.send(OnboardingIntentNext.shared) }.buttonStyle(PrimaryButtonStyle())
            case .credits:
                Button(state.addKeysLabel) { model.send(OnboardingIntentOpen(destination: .keys)) }.buttonStyle(PrimaryButtonStyle())
                Button(state.notNowLabel) { model.send(OnboardingIntentNext.shared) }.buttonStyle(GhostButtonStyle())
            case .done:
                if let featured = state.featured, let read = state.featuredAction {
                    Button(read) {
                        Haptics.success()
                        openTitle(TitleRoute(sourceKey: featured.sourceKey, titleId: featured.id))
                        model.send(OnboardingIntentNext.shared)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    Button(state.doneAction) {
                        openTab(.library)
                        model.send(OnboardingIntentNext.shared)
                    }
                    .buttonStyle(GhostButtonStyle())
                } else {
                    Button(state.doneAction) {
                        Haptics.success()
                        openTab(.library)
                        model.send(OnboardingIntentNext.shared)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }
            default:
                Button {
                    model.send(OnboardingIntentNext.shared)
                } label: {
                    HStack(spacing: 8) {
                        if state.readingSaving { ProgressView().tint(Color.brandInk) }
                        Text(state.nextLabel)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(state.readingSaving)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 16)
        .background(Color.brandInk.opacity(0.94))
    }

    // Steps.

    @ViewBuilder
    private func step(_ state: OnboardingUiState) -> some View {
        switch state.step {
        case .welcome: welcome(state)
        case .language: language(state)
        case .reading: reading(state)
        case .sources: sources(state)
        case .credits: credits(state)
        default: done(state)
        }
    }

    private func welcome(_ state: OnboardingUiState) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            ColdOpen(line: state.welcomeCold, balloonFrom: state.demo.original, balloonTo: state.demo.translated)
            Text(state.welcomeTitle).font(.brandDisplay(40)).foregroundStyle(Color.brandPaper).lineSpacing(-4)
            Text(state.welcomeLead).font(.brandBody(17)).foregroundStyle(Color.brandPaper.opacity(0.86))
            Text(state.welcomeList).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted).padding(.top, 6)
            ForEach(Array(state.welcomeSteps.enumerated()), id: \.offset) { index, text in
                HStack(spacing: 14) {
                    Text("\(index + 1)")
                        .font(.brandDisplay(18))
                        .foregroundStyle(Color.brandInk)
                        .frame(width: 34, height: 34)
                        .background(Color.brandYellow, in: RoundedRectangle(cornerRadius: 6))
                        .rotationEffect(.degrees(index.isMultiple(of: 2) ? -3 : 3))
                    Text(text).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                }
                .riseIn(index + 1)
            }
        }
    }

    private func language(_ state: OnboardingUiState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            StepTitle(title: state.languageTitle, lead: state.languageLead)
            LanguageChoiceCard(
                choice: state.language,
                choose: { model.send(OnboardingIntentChooseLocale(code: $0)) },
                openRegions: { regionsOpen = true }
            )
        }
    }

    private func reading(_ state: OnboardingUiState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            StepTitle(title: state.readingTitle, lead: state.readingLead)
            Card {
                Text(state.readingLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                PickerRow(label: state.readingChoice) { readingOpen = true }
            }
            // The choice at work: the same balloon, lettered again in it.
            TranslatedPageDemo(kicker: state.demoKicker, balloon: state.demo)
                .padding(.top, 6)
        }
    }

    private func sources(_ state: OnboardingUiState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            StepTitle(title: state.sourcesTitle, lead: state.sourcesLead)
            // The official source first, one tap to turn on (ADR-0055).
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    LogoMark(size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(state.officialTitle).font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
                        Text(state.sourcesCount).font(.brandBody(12, weight: .bold)).foregroundStyle(Color.brandCyan)
                    }
                }
                Text(state.officialBody).font(.brandBody(14)).foregroundStyle(Color.brandPaper.opacity(0.86))
                if let unavailable = state.officialUnavailable {
                    Text(unavailable).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                } else if state.sources.officialOn {
                    Label(state.officialAction, systemImage: "checkmark.circle.fill")
                        .font(.brandDisplay(20))
                        .foregroundStyle(Color.brandCyan)
                        .transition(reduceMotion ? .opacity : .scale(scale: 2.2).combined(with: .opacity))
                } else {
                    Button {
                        Haptics.press()
                        model.send(OnboardingIntentTurnOnOfficial.shared)
                    } label: {
                        HStack(spacing: 8) {
                            if state.turningOn { ProgressView().tint(Color.brandInk) }
                            Text(state.officialAction)
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!state.canTurnOnOfficial)
                }
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.brandRed, lineWidth: 2))
            .background(RoundedRectangle(cornerRadius: 14).fill(Color.brandRed.opacity(0.35)).offset(x: 6, y: 6))
            .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.55), value: state.sources.officialOn)
            .sensoryFeedback(.success, trigger: state.sources.officialOn)
            Card {
                Text(state.moreTitle).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                Text(state.moreBody).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                HStack(spacing: 10) {
                    Button(state.moreAction) { model.send(OnboardingIntentOpen(destination: .sources)) }.buttonStyle(GhostButtonStyle())
                    Button(state.laterLabel) { model.send(OnboardingIntentNext.shared) }.buttonStyle(GhostButtonStyle())
                }
            }
        }
    }

    private func credits(_ state: OnboardingUiState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            StepTitle(title: state.creditsTitle, lead: state.creditsLead)
            Card {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "key.fill").foregroundStyle(Color.brandYellow).frame(width: 22)
                    richText(state.creditsOwn).foregroundStyle(Color.brandPaper)
                }
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "creditcard.fill").foregroundStyle(Color.brandRed).frame(width: 22)
                    richText(state.creditsWallet).foregroundStyle(Color.brandPaper)
                }
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "person.2.fill").foregroundStyle(Color.brandCyan).frame(width: 22)
                    Text(state.creditsShared).font(.brandBody(14)).foregroundStyle(Color.brandPaper)
                }
            }
            if state.creditsStatus != nil {
                WalletCard(
                    kicker: state.walletKicker,
                    balance: state.credits.ownKeys ? nil : state.credits.balance?.int64Value,
                    ownKeysLine: state.credits.ownKeys ? state.creditsStatus : nil,
                    label: { state.balanceLabel(count: $0) }
                )
                .padding(.top, 4)
            }
        }
    }

    private func done(_ state: OnboardingUiState) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            Onomatopoeia(text: "ドン!", size: 56, color: .brandYellow)
            Text(state.doneTitle).font(.brandDisplay(40)).foregroundStyle(Color.brandPaper).lineSpacing(-4)
            Text(state.doneLead).font(.brandBody(17)).foregroundStyle(Color.brandPaper.opacity(0.86))
            if let featured = state.featured {
                FeaturedCover(kicker: state.featuredKicker, title: featured).padding(.top, 12)
            }
        }
        .padding(.top, 24)
    }
}

/// A step's display title and lead.
private struct StepTitle: View {
    let title: String
    let lead: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.brandDisplay(32)).foregroundStyle(Color.brandPaper).lineSpacing(-3)
            Text(lead).font(.brandBody(15)).foregroundStyle(Color.brandPaper.opacity(0.8))
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// The tutorial's progress: one dot per counted step, the current one a red bar.
private struct ProgressDots: View {
    let count: Int
    let current: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0 ..< count, id: \.self) { index in
                Capsule()
                    .fill(index == current ? Color.brandRed : (index < current ? Color.brandPaper : Color.brandLine))
                    .frame(width: index == current ? 22 : 8, height: 8)
            }
        }
        .animation(.snappy, value: current)
        .accessibilityElement()
        .accessibilityLabel(L("onboarding.progress", ["step": current + 1, "total": count]))
    }
}
