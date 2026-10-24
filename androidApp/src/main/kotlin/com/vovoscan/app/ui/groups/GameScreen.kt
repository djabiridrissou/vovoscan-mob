package com.vovoscan.app.ui.groups

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.groups.domain.GameState
import com.vovoscan.feature.groups.presentation.GameIntent
import com.vovoscan.feature.groups.presentation.GameUiState
import com.vovoscan.feature.groups.presentation.GameViewModel
import com.vovoscan.feature.groups.presentation.PlayerUi
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * One game (shared `GameViewModel`): the board as the server sends it, live for players and spectators, a tap sends
 * the move (checked first), the turn clock, the result, a replay once it is over, the group's leaderboard. iOS draws
 * the same in `GameViews.swift`.
 */

/** The seats' inks, the same on every board and in the web's games. */
internal fun seatInk(seat: Int): Color =
    when (seat) {
        0 -> Brand.Red
        1 -> Brand.Cyan
        2 -> Brand.Yellow
        else -> Brand.Muted
    }

@Composable
fun GameScreen(
    gameId: String,
    onBack: () -> Unit,
    viewModel: GameViewModel = koinViewModel(key = "game:$gameId") { parametersOf(gameId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onIntent
    LifecycleEventEffect(Lifecycle.Event.ON_START) { send(GameIntent.Start) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { send(GameIntent.Stop) }
    Column(Modifier.fillMaxSize()) {
        BackBar(state.title.ifEmpty { I18n.text("games.title") }, onBack) {
            state.clock?.let { clock ->
                Text(
                    clock,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            color = if (state.urgent) Brand.Red else Brand.Paper,
                            fontWeight = FontWeight.Bold,
                        ),
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
        if (state.loading && state.kind == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Cyan) }
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                state.status,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                modifier = Modifier.semantics { contentDescription = state.status },
            )
            Players(state.players)
            Box(Modifier.fillMaxWidth().widthIn(max = 520.dp), contentAlignment = Alignment.Center) {
                when (val board = state.board) {
                    is GameState.Four -> FourBoard(board, state, viewModel)
                    is GameState.Panels -> PanelsBoard(board, state, viewModel)
                    is GameState.Clash -> ClashBoard(state, send)
                    GameState.Unknown -> Text(I18n.text("games.unknown"), style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.replayStep?.let { step -> Replay(step, state, send) }
            state.error?.let { ErrorText(it) }
            if (state.canJoin) PrimaryButton(I18n.text("games.take"), { send(GameIntent.Join) }, enabled = !state.sending)
            if (state.canLeave) GhostButton(state.leaveLabel, { send(GameIntent.Leave) })
            Text(state.rules, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted))
            if (state.leaderboard.isNotEmpty()) Leaderboard(state)
        }
    }
}

/** A plain top bar: ←, a title, and what the screen puts on its right. */
@Composable
internal fun BackBar(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clickable(onClick = onBack)
                .semantics { contentDescription = I18n.text("common.back") },
            contentAlignment = Alignment.Center,
        ) { Text("←", style = MaterialTheme.typography.titleLarge.copy(color = Brand.Paper)) }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Players(players: List<PlayerUi>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        players.forEach { p ->
            Column(
                Modifier
                    .weight(1f)
                    .background(Brand.Ink2, RoundedCornerShape(10.dp))
                    .border(if (p.toPlay) 2.dp else 1.dp, if (p.toPlay) seatInk(p.seat) else Brand.Line, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(14.dp).background(seatInk(p.seat), CircleShape).border(2.dp, Brand.Ink, CircleShape))
                Text(
                    p.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (p.you) FontWeight.Bold else FontWeight.Normal),
                    maxLines = 1,
                )
                p.score?.let { Text(it, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black)) }
                p.rank?.let { rank ->
                    Text(
                        listOfNotNull(rank, p.ratingChange).joinToString(" ✦ "),
                        style = MaterialTheme.typography.bodySmall.copy(color = Brand.Yellow),
                    )
                }
            }
        }
    }
}

/** Ink Four: 7 columns, a tap on a column drops a disc where it lands. */
@Composable
private fun FourBoard(
    board: GameState.Four,
    state: GameUiState,
    viewModel: GameViewModel,
) {
    val haptics = LocalHapticFeedback.current
    val cols = GameState.Four.COLS
    val rows = GameState.Four.ROWS
    val line = board.line.toSet()
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(cols / rows.toFloat())
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(2.dp, Brand.Paper, RoundedCornerShape(12.dp))
            .pointerInput(state.dropColumns, state.sending) {
                detectTapGestures { at ->
                    val col = floor(at.x / (size.width / cols.toFloat())).toInt()
                    if (col in state.dropColumns && !state.sending) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.onIntent(GameIntent.Drop(col))
                    }
                }
            },
    ) {
        val cell = size.width / cols
        val r = cell * 0.38f
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val seat = board.board.getOrNull(row)?.getOrNull(col) ?: -1
                val center = Offset(col * cell + cell / 2, row * cell + cell / 2)
                drawCircle(if (seat < 0) Brand.Ink else seatInk(seat), r, center)
                if ((row to col) in line) drawCircle(Brand.Paper, r + 3.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                if (board.last == (row to col)) drawCircle(Brand.Ink, r * 0.25f, center)
            }
        }
        // The columns a tap may drop in now, marked at the top.
        state.dropColumns.forEach { col ->
            drawLine(
                Brand.Paper.copy(alpha = 0.5f),
                Offset(col * cell + cell * 0.3f, 3f),
                Offset(col * cell + cell * 0.7f, 3f),
                3.dp.toPx(),
                StrokeCap.Round,
            )
        }
    }
}

/** Panels: dots on a grid, a tap draws the edge nearest to it, closed boxes take their player's ink. */
@Composable
private fun PanelsBoard(
    board: GameState.Panels,
    state: GameUiState,
    viewModel: GameViewModel,
) {
    val haptics = LocalHapticFeedback.current
    val n = board.size
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .pointerInput(board, state.canDraw, state.sending) {
                detectTapGestures { at ->
                    if (!state.canDraw) return@detectTapGestures
                    val pad = size.width * PANEL_PAD
                    val step = (size.width - 2 * pad) / n
                    val x = (at.x - pad) / step
                    val y = (at.y - pad) / step
                    // The nearer grid line wins: a horizontal edge when the tap is closer to a row of dots.
                    val dh = abs(y - y.roundToInt())
                    val dv = abs(x - x.roundToInt())
                    val horizontal = dh <= dv
                    val row = if (horizontal) y.roundToInt() else floor(y).toInt()
                    val col = if (horizontal) floor(x).toInt() else x.roundToInt()
                    if (viewModel.canDraw(horizontal, row, col)) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.onIntent(GameIntent.Draw(horizontal, row, col))
                    }
                }
            },
    ) {
        val pad = size.width * PANEL_PAD
        val step = (size.width - 2 * pad) / n
        val stroke = 5.dp.toPx()
        for (row in 0 until n) {
            for (col in 0 until n) {
                val owner = board.boxes.getOrNull(row)?.getOrNull(col) ?: -1
                if (owner >= 0) {
                    drawRoundRect(
                        seatInk(owner).copy(alpha = 0.75f),
                        Offset(pad + col * step + stroke, pad + row * step + stroke),
                        Size(step - 2 * stroke, step - 2 * stroke),
                        CornerRadius(4.dp.toPx()),
                    )
                }
            }
        }

        fun edgeColor(
            seat: Int?,
            last: Boolean,
        ): Color =
            when {
                seat == null || seat < 0 -> Brand.Line
                last -> Brand.Paper
                else -> seatInk(seat)
            }
        for (row in 0..n) {
            for (col in 0 until n) {
                val seat = board.h.getOrNull(row)?.getOrNull(col)
                val last = board.last?.let { it.horizontal && it.row == row && it.col == col } == true
                drawLine(
                    edgeColor(seat, last),
                    Offset(pad + col * step, pad + row * step),
                    Offset(pad + (col + 1) * step, pad + row * step),
                    if (seat != null && seat >= 0) stroke else stroke * 0.4f,
                    StrokeCap.Round,
                )
            }
        }
        for (row in 0 until n) {
            for (col in 0..n) {
                val seat = board.v.getOrNull(row)?.getOrNull(col)
                val last = board.last?.let { !it.horizontal && it.row == row && it.col == col } == true
                drawLine(
                    edgeColor(seat, last),
                    Offset(pad + col * step, pad + row * step),
                    Offset(pad + col * step, pad + (row + 1) * step),
                    if (seat != null && seat >= 0) stroke else stroke * 0.4f,
                    StrokeCap.Round,
                )
            }
        }
        for (row in 0..n) {
            for (col in 0..n) drawCircle(Brand.Paper, stroke * 0.8f, Offset(pad + col * step, pad + row * step))
        }
    }
}

private const val PANEL_PAD = 0.06f

/** Sticker Clash: the three picks, each seat's state this round, the rounds so far. */
@Composable
private fun ClashBoard(
    state: GameUiState,
    send: (GameIntent) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.seatPicks.forEachIndexed { seat, pick ->
                Box(
                    Modifier
                        .weight(1f)
                        .background(Brand.Ink2, RoundedCornerShape(10.dp))
                        .border(1.dp, seatInk(seat), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(pick, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)) }
            }
        }
        if (state.picks.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.picks.forEach { p ->
                    Column(
                        Modifier
                            .weight(1f)
                            .background(if (p.enabled) Brand.Paper else Brand.Ink2, RoundedCornerShape(12.dp))
                            .border(2.dp, Brand.Ink, RoundedCornerShape(12.dp))
                            .clickable(enabled = p.enabled && !state.sending) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                send(GameIntent.Pick(p.pick))
                            }.padding(vertical = 16.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "${p.glyph}, ${p.label}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            p.glyph,
                            style =
                                MaterialTheme.typography.headlineSmall.copy(
                                    color = if (p.enabled) Brand.Ink else Brand.Muted,
                                    fontWeight = FontWeight.Black,
                                ),
                        )
                        Text(p.label, style = MaterialTheme.typography.labelSmall.copy(color = if (p.enabled) Brand.Ink else Brand.Muted))
                    }
                }
            }
        }
        state.rounds.asReversed().forEach { round ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${round.number}", style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted))
                Text(round.picks.joinToString(" · "), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Text(round.line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A finished game, move by move. */
@Composable
private fun Replay(
    step: Int,
    state: GameUiState,
    send: (GameIntent) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        state.replayLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        Slider(
            value = step.toFloat(),
            onValueChange = { send(GameIntent.ReplayTo(it.roundToInt())) },
            valueRange = 0f..state.replaySteps.toFloat().coerceAtLeast(1f),
            steps = (state.replaySteps - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = Brand.Red, activeTrackColor = Brand.Red, inactiveTrackColor = Brand.Line),
        )
    }
}

@Composable
private fun Leaderboard(state: GameUiState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            I18n.text("games.leaderboard").uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(color = Brand.Muted, letterSpacing = 1.2.sp),
        )
        state.leaderboard.forEachIndexed { i, s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (s.you) Brand.Ink2 else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge.copy(color = if (i == 0) Brand.Yellow else Brand.Muted))
                Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                Text(s.record, style = MaterialTheme.typography.bodySmall)
                Text(s.rating, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}
