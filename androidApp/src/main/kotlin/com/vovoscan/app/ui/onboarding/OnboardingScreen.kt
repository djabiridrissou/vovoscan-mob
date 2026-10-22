package com.vovoscan.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.account.Card
import com.vovoscan.app.ui.account.ContentLanguageSheet
import com.vovoscan.app.ui.account.LanguageChoiceCard
import com.vovoscan.app.ui.account.PickerRow
import com.vovoscan.app.ui.account.rich
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.LogoMark
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.settings.KeysScreen
import com.vovoscan.app.ui.sources.AddSourceScreen
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.auth.domain.OnboardingStep
import com.vovoscan.feature.auth.presentation.OnboardingDestination
import com.vovoscan.feature.auth.presentation.OnboardingIntent
import com.vovoscan.feature.auth.presentation.OnboardingUiState
import com.vovoscan.feature.auth.presentation.OnboardingViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The first-launch tutorial, full screen over the tabs: welcome, language, reading, sources, credits, done. Skip on
 * every step, Back from the second; "Add a source" and "Add my keys" open those screens in its place and come back.
 * No logic here (`OnboardingViewModel`); every step saves at once.
 */
@Composable
fun OnboardingScreen(
    onClose: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTitle: (sourceKey: String, titleId: String) -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onIntent
    LaunchedEffect(state.finished) { if (state.finished) onClose() }
    when (state.open) {
        OnboardingDestination.Sources -> {
            BackHandler { send(OnboardingIntent.Returned) }
            AddSourceScreen(repositoryId = null, onBack = { send(OnboardingIntent.Returned) }, onOpenSource = {})
            return
        }
        OnboardingDestination.Keys -> {
            BackHandler { send(OnboardingIntent.Returned) }
            KeysScreen(onBack = { send(OnboardingIntent.Returned) })
            return
        }
        null -> Unit
    }
    BackHandler(enabled = state.canGoBack) { send(OnboardingIntent.Back) }
    Column(Modifier.fillMaxSize().background(Brand.Ink)) {
        TopBar(state, send)
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.weight(1f),
            label = "step",
        ) { step ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    OnboardingStep.Welcome -> Welcome(state)
                    OnboardingStep.Language -> Language(state, send)
                    OnboardingStep.Reading -> Reading(state, send)
                    OnboardingStep.Sources -> Sources(state, send)
                    OnboardingStep.Credits -> Credits(state)
                    OnboardingStep.Done -> Done(state)
                }
            }
        }
        BottomBar(state, send, onOpenLibrary, onOpenTitle)
    }
}

@Composable
private fun TopBar(
    state: OnboardingUiState,
    send: (OnboardingIntent) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (state.canGoBack) {
                TextAction("‹ ${state.backLabel}") { send(OnboardingIntent.Back) }
            } else {
                LogoMark(28)
            }
        }
        if (state.dot >= 0) Dots(state)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (state.showSkip) TextAction(state.skipLabel) { send(OnboardingIntent.Skip) }
        }
    }
}

/** One dot per counted step, the current one a red bar; read out as "Step 2 of 4". */
@Composable
private fun Dots(state: OnboardingUiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = state.progressLabel.orEmpty() },
    ) {
        repeat(state.dots) { index ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (index == state.dot) 22.dp else 6.dp)
                    .background(if (index <= state.dot) Brand.Red else Brand.Line, RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun TextAction(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Paper),
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
    )
}

@Composable
private fun BottomBar(
    state: OnboardingUiState,
    send: (OnboardingIntent) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTitle: (String, String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Brand.Ink).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.error?.let { ErrorText(it) }
        when (state.step) {
            OnboardingStep.Welcome -> PrimaryButton(state.startLabel, { send(OnboardingIntent.Next) })
            OnboardingStep.Credits -> {
                PrimaryButton(state.addKeysLabel, { send(OnboardingIntent.Open(OnboardingDestination.Keys)) })
                GhostButton(state.notNowLabel, { send(OnboardingIntent.Next) })
            }
            OnboardingStep.Done -> {
                val featured = state.featured
                val read = state.featuredAction
                if (featured != null && read != null) {
                    PrimaryButton(read, {
                        onOpenTitle(featured.sourceKey, featured.id)
                        send(OnboardingIntent.Next)
                    })
                    GhostButton(state.doneAction, {
                        onOpenLibrary()
                        send(OnboardingIntent.Next)
                    })
                } else {
                    PrimaryButton(state.doneAction, {
                        onOpenLibrary()
                        send(OnboardingIntent.Next)
                    })
                }
            }
            else -> PrimaryButton(state.nextLabel, { send(OnboardingIntent.Next) }, enabled = !state.readingSaving)
        }
    }
}

@Composable
private fun StepTitle(
    title: String,
    lead: String,
) {
    Column(Modifier.semantics { heading() }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(lead, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.TextSoft))
    }
}

@Composable
private fun Welcome(state: OnboardingUiState) {
    ColdOpen(state.welcomeCold, state.demo.original, state.demo.translated)
    Text(state.welcomeTitle, style = MaterialTheme.typography.displaySmall)
    Text(state.welcomeLead, style = MaterialTheme.typography.bodyLarge.copy(color = Brand.TextSoft))
    Text(state.welcomeList, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted))
    state.welcomeSteps.forEachIndexed { index, text ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .rotate(if (index % 2 == 0) -3f else 3f)
                    .background(Brand.Yellow, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("${index + 1}", style = MaterialTheme.typography.titleMedium.copy(color = Brand.Ink)) }
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 14.dp))
        }
    }
}

@Composable
private fun Language(
    state: OnboardingUiState,
    send: (OnboardingIntent) -> Unit,
) {
    StepTitle(state.languageTitle, state.languageLead)
    LanguageChoiceCard(
        state.language,
        onLocale = { send(OnboardingIntent.ChooseLocale(it)) },
        onRegionQuery = { send(OnboardingIntent.RegionQuery(it)) },
        onRegion = { send(OnboardingIntent.ChooseRegion(it)) },
    )
}

@Composable
private fun Reading(
    state: OnboardingUiState,
    send: (OnboardingIntent) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    StepTitle(state.readingTitle, state.readingLead)
    Card { PickerRow(state.readingLabel, state.readingChoice) { open = true } }
    // The choice at work: the same balloon, lettered again in it.
    TranslatedPageDemo(state.demoKicker, state.demo)
    if (open) {
        ContentLanguageSheet(
            title = state.readingLabel,
            query = state.readingQuery,
            rows = state.readingRows,
            onQuery = { send(OnboardingIntent.ReadingQuery(it)) },
            onChoose = {
                open = false
                send(OnboardingIntent.ChooseReading(it))
            },
            onDismiss = {
                open = false
                send(OnboardingIntent.ReadingQuery(""))
            },
        )
    }
}

@Composable
private fun Sources(
    state: OnboardingUiState,
    send: (OnboardingIntent) -> Unit,
) {
    StepTitle(state.sourcesTitle, state.sourcesLead)
    // The official source first, one tap to turn on (ADR-0055): a red card with a hard offset shadow.
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().offset(6.dp, 6.dp).background(Brand.Red.copy(alpha = 0.35f), RoundedCornerShape(14.dp)))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brand.Ink2, RoundedCornerShape(14.dp))
                .border(2.dp, Brand.Red, RoundedCornerShape(14.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(40)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(state.officialTitle, style = MaterialTheme.typography.titleLarge)
                    Text(state.sourcesCount, style = MaterialTheme.typography.labelSmall.copy(color = Brand.Cyan))
                }
            }
            Text(state.officialBody, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.TextSoft))
            when {
                state.officialUnavailable != null ->
                    Text(state.officialUnavailable.orEmpty(), style = MaterialTheme.typography.bodySmall)
                state.sources.officialOn ->
                    Text("✓ ${state.officialAction}", style = MaterialTheme.typography.titleSmall.copy(color = Brand.Cyan))
                else -> PrimaryButton(state.officialAction, { send(OnboardingIntent.TurnOnOfficial) }, enabled = state.canTurnOnOfficial)
            }
        }
    }
    Card(state.moreTitle) {
        Text(state.moreBody, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(state.moreAction, { send(OnboardingIntent.Open(OnboardingDestination.Sources)) }, Modifier.weight(1f))
            GhostButton(state.laterLabel, { send(OnboardingIntent.Next) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Credits(state: OnboardingUiState) {
    StepTitle(state.creditsTitle, state.creditsLead)
    Card {
        CreditLine("⚿", Brand.Yellow) { Text(rich(state.creditsOwn), style = MaterialTheme.typography.bodyMedium) }
        CreditLine("¤", Brand.Red) { Text(rich(state.creditsWallet), style = MaterialTheme.typography.bodyMedium) }
        CreditLine("⇄", Brand.Cyan) { Text(state.creditsShared, style = MaterialTheme.typography.bodyMedium) }
    }
    if (state.creditsStatus != null) {
        WalletCard(
            kicker = state.walletKicker,
            balance = if (state.credits.ownKeys) null else state.credits.balance,
            ownKeysLine = if (state.credits.ownKeys) state.creditsStatus else null,
            label = state::balanceLabel,
        )
    }
}

@Composable
private fun CreditLine(
    mark: String,
    color: Color,
    content: @Composable () -> Unit,
) {
    Row {
        Text(mark, style = MaterialTheme.typography.titleMedium.copy(color = color), modifier = Modifier.width(26.dp))
        content()
    }
}

@Composable
private fun Done(state: OnboardingUiState) {
    Text(
        "ドン!",
        style = MaterialTheme.typography.displayMedium.copy(color = Brand.Yellow, fontSize = 56.sp),
        modifier = Modifier.padding(top = 24.dp).rotate(-6f),
    )
    Text(state.doneTitle, style = MaterialTheme.typography.displaySmall)
    Text(state.doneLead, style = MaterialTheme.typography.bodyLarge.copy(color = Brand.TextSoft))
    state.featured?.let { FeaturedCover(state.featuredKicker, it) }
}
