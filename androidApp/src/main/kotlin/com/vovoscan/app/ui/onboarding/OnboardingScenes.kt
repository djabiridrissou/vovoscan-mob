package com.vovoscan.app.ui.onboarding

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vovoscan.app.ui.common.LogoMark
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.auth.domain.OnboardingTitle
import com.vovoscan.feature.auth.presentation.DemoBalloon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The tutorial's scenes, as on iOS (OnboardingScenes.swift): a cold open of panels stamped one after the other, a
// live translated page, the wallet counting up, a real title to start with. With animations removed in the system
// settings, every scene shows its final state at once.

@Composable
private fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** A panel's shape: a rectangle whose bottom edge rises or falls by [slant] of its height. */
private fun slanted(slant: Float): Shape =
    GenericShape { size, _ ->
        val drop = size.height * slant
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height - maxOf(0f, drop))
        lineTo(0f, size.height - maxOf(0f, -drop))
        close()
    }

@Composable
private fun Panel(
    slant: Float,
    modifier: Modifier,
    fill: Color = Brand.Paper,
    content: @Composable () -> Unit,
) {
    val shape = slanted(slant)
    Box(modifier) {
        Box(Modifier.fillMaxSize().offset(5.dp, 5.dp).background(Brand.Red.copy(alpha = 0.55f), shape))
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(fill)
                .border(3.dp, Brand.Ink, shape),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** Speed lines from [center] (fractions of the box), in [color]. */
@Composable
private fun SpeedLines(
    center: Offset,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val origin = Offset(size.width * center.x, size.height * center.y)
        val reach = size.width + size.height
        repeat(40) { i ->
            val angle = i * (2 * PI / 40) + (i % 3) * 0.04
            val start = 0.18f * reach + (i % 5) * 6f
            val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(color, origin + dir * start, origin + dir * reach, strokeWidth = if (i % 4 == 0) 3f else 1.4f)
        }
    }
}

@Composable
private fun Balloon(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(color = Brand.Ink, fontSize = size.sp, textAlign = TextAlign.Center),
        maxLines = 2,
        modifier =
            modifier
                .background(Color.White, CircleShape)
                .border(2.5.dp, Brand.Ink, CircleShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/**
 * The cold open: a manga page whose three panels slam in one after the other (a haptic each), the mark stamped last
 * with a shake. About 1.6 s.
 */
@Composable
fun ColdOpen(
    line: String,
    balloonFrom: String,
    balloonTo: String,
) {
    val reduced = reducedMotion()
    val haptics = LocalHapticFeedback.current
    var beat by remember { mutableIntStateOf(if (reduced) 5 else 0) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        for (next in 1..5) {
            delay(if (next == 1) 180L else 300L)
            beat = next
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (next == 4) launch { shake.animateTo(1f, tween(380)) }
        }
    }
    Column(
        Modifier.clearAndSetSemantics { contentDescription = line },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(300.dp).graphicsLayer {
                translationX = 7.dp.toPx() * sin(shake.value * PI.toFloat() * 6) * (1 - shake.value)
            },
        ) {
            val w = maxWidth
            val h = maxHeight
            Panel(0.06f, Modifier.size(w, h * 0.42f).slam(beat >= 1, 1.3f)) {
                Text("ゴゴゴ", style = MaterialTheme.typography.displaySmall.copy(color = Brand.Ink), modifier = Modifier.rotate(-8f))
            }
            Panel(-0.05f, Modifier.offset(y = h * 0.45f).size(w * 0.58f, h * 0.55f).slam(beat >= 2, 1.25f)) {
                SpeedLines(Offset(0.2f, 0.5f), Brand.Ink.copy(alpha = 0.5f), Modifier.fillMaxSize())
                Balloon(if (beat >= 3) balloonTo else balloonFrom, 18)
            }
            Panel(0.04f, Modifier.offset(x = w * 0.61f, y = h * 0.45f).size(w * 0.39f, h * 0.55f).slam(beat >= 3, 1.25f), Brand.Red) {
                Text("▶", style = MaterialTheme.typography.displaySmall.copy(color = Brand.Paper))
            }
            Box(Modifier.offset(x = w - 70.dp, y = (-22).dp).slam(beat >= 4, 2.4f)) { LogoMark(74) }
            Text(
                "ドン!",
                style = MaterialTheme.typography.displaySmall.copy(color = Brand.Yellow, fontSize = 34.sp),
                modifier = Modifier.offset(x = 6.dp, y = h * 0.36f).rotate(-6f).slam(beat >= 5, 0.4f),
            )
        }
        Text(
            line,
            style = MaterialTheme.typography.titleLarge.copy(color = Brand.Yellow),
            modifier = Modifier.slam(beat >= 4, 1f),
        )
    }
}

/** Stamped in: from [from] times its size and see-through to its place, on a bouncy spring. */
@Composable
private fun Modifier.slam(
    on: Boolean,
    from: Float,
): Modifier {
    val progress = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) { progress.animateTo(if (on) 1f else 0f, spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow)) }
    return graphicsLayer {
        val p = progress.value
        val scale = from + (1f - from) * p
        scaleX = scale
        scaleY = scale
        rotationZ = -4f * (1f - p)
        alpha = p.coerceIn(0f, 1f)
    }
}

/**
 * The live demo of a translated page: the balloon lettered in Japanese, painted over and lettered again in the
 * reading language (the wipe runs in its reading direction), on a loop; a new language restarts it at once.
 */
@Composable
fun TranslatedPageDemo(
    kicker: String,
    balloon: DemoBalloon,
) {
    val reduced = reducedMotion()
    val haptics = LocalHapticFeedback.current
    // The lettering is typed in from its reading side, replayed on each new language.
    val reveal = remember(balloon.translated) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(balloon.translated) { if (!reduced) reveal.animateTo(1f, tween(450)) }
    // "Hold to see the original" is held.
    var holding by remember(balloon.translated) { mutableStateOf(false) }
    val shown = if (holding) balloon.original else balloon.translated
    val rtl = !holding && balloon.rtl
    val shape = slanted(0.05f)
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "${balloon.caption} $shown" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Brand.Red, CircleShape))
            Text(
                kicker.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(color = Brand.Muted, letterSpacing = 1.4.sp),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(190.dp)) {
            Box(Modifier.fillMaxSize().offset(6.dp, 6.dp).background(Brand.Red, shape))
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(Brand.Paper)
                    .border(3.dp, Brand.Ink, shape),
                contentAlignment = Alignment.Center,
            ) {
                SpeedLines(Offset(0.85f, 0.8f), Brand.Ink.copy(alpha = 0.3f), Modifier.fillMaxSize())
                // One balloon, one text at a time: never two letterings half swapped.
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Balloon(shown, if (holding) 26 else 24, Modifier.wipe(if (holding) 1f else reveal.value, fromLeft = !rtl))
                }
            }
        }
        Text(balloon.caption, style = MaterialTheme.typography.bodySmall.copy(color = Brand.TextSoft))
        balloon.holdLabel?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(color = if (holding) Brand.Ink else Brand.Paper),
                modifier =
                    Modifier
                        .background(if (holding) Brand.Yellow else Brand.Ink2, RoundedCornerShape(999.dp))
                        .border(1.dp, Brand.Line, RoundedCornerShape(999.dp))
                        .pointerInput(balloon.translated) {
                            detectTapGestures(
                                onPress = {
                                    holding = true
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    tryAwaitRelease()
                                    holding = false
                                },
                            )
                        }.semantics {
                            onClick(label) {
                                holding = !holding
                                true
                            }
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Shows [progress] of the content, from its left or its right edge. */
private fun Modifier.wipe(
    progress: Float,
    fromLeft: Boolean,
): Modifier =
    drawWithContent {
        val w = size.width * progress.coerceIn(0f, 1f)
        val left = if (fromLeft) 0f else size.width - w
        clipRect(left, 0f, left + w, size.height) { this@drawWithContent.drawContent() }
    }

/** The wallet: the balance counting up from zero, or the own-keys line. */
@Composable
fun TourWallet(
    kicker: String,
    balance: Long?,
    ownKeysLine: String?,
    label: (Long) -> String,
) {
    val reduced = reducedMotion()
    val shown = remember(balance) { Animatable(if (reduced) (balance ?: 0L).toFloat() else 0f) }
    LaunchedEffect(balance) { if (balance != null && !reduced) shown.animateTo(balance.toFloat(), tween(1100)) }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().rotate(-1.5f)) {
        Box(Modifier.matchParentSize().offset(6.dp, 6.dp).background(Brand.Paper, shape))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brand.Yellow, shape)
                .border(2.5.dp, Brand.Ink, shape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                kicker.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink.copy(alpha = 0.7f), letterSpacing = 1.4.sp),
            )
            when {
                ownKeysLine != null -> Text(ownKeysLine, style = MaterialTheme.typography.titleLarge.copy(color = Brand.Ink))
                balance != null ->
                    Text(label(Math.round(shown.value.toDouble())), style = MaterialTheme.typography.headlineSmall.copy(color = Brand.Ink))
            }
        }
    }
}

/** The real title the tour ends on: its cover, tilted, rising in. */
@Composable
fun FeaturedCover(
    kicker: String,
    title: OnboardingTitle,
) {
    val reduced = reducedMotion()
    val rise = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        delay(150)
        rise.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
    }
    val shape = RoundedCornerShape(8.dp)
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 12.dp)) {
        Box(
            Modifier.size(120.dp, 180.dp).graphicsLayer {
                rotationZ = 6f - 9f * rise.value
                translationY = 40.dp.toPx() * (1 - rise.value)
                alpha = rise.value.coerceIn(0f, 1f)
            },
        ) {
            Box(Modifier.matchParentSize().offset(6.dp, 6.dp).background(Brand.Red, shape))
            Box(Modifier.matchParentSize().clip(shape).background(Brand.Ink2), contentAlignment = Alignment.Center) {
                LogoMark(40)
                AsyncImage(
                    model = title.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Box(Modifier.matchParentSize().border(2.dp, Brand.Paper, shape))
        }
        Column(Modifier.padding(start = 16.dp).graphicsLayer { alpha = rise.value.coerceIn(0f, 1f) }) {
            Text(kicker.uppercase(), style = MaterialTheme.typography.labelSmall.copy(color = Brand.Cyan, letterSpacing = 1.4.sp))
            Text(title.name, style = MaterialTheme.typography.headlineSmall, maxLines = 3)
        }
    }
}
