package com.vovoscan.feature.groups.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.groups.domain.ClashPick
import com.vovoscan.feature.groups.domain.Edge
import com.vovoscan.feature.groups.domain.Ending
import com.vovoscan.feature.groups.domain.Game
import com.vovoscan.feature.groups.domain.GameKind
import com.vovoscan.feature.groups.domain.GameState
import com.vovoscan.feature.groups.domain.GameStatus
import com.vovoscan.feature.groups.domain.GroupsAccess
import com.vovoscan.feature.groups.domain.GroupsRepository
import com.vovoscan.feature.groups.domain.GroupsUser
import com.vovoscan.feature.groups.domain.Move
import com.vovoscan.feature.groups.domain.Replay
import com.vovoscan.feature.groups.domain.SeatPick
import com.vovoscan.feature.groups.domain.Standing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUi(
    val userId: String,
    val name: String,
    /** The seat's ink: 0 red, 1 cyan, 2 yellow (the same on every board). */
    val seat: Int,
    /** "3" (rounds or boxes), null for Ink Four. */
    val score: String?,
    val you: Boolean,
    /** Whose move it is now (both seats in a Sticker Clash round until they pick). */
    val toPlay: Boolean,
    /** Once over: "1st", "2nd"…; with a rating change "+14" / "−9" for a ranked game. */
    val rank: String?,
    val ratingChange: String?,
)

/** A Sticker Clash pick to choose, drawn as its onomatopoeia. */
data class PickUi(
    val pick: ClashPick,
    val glyph: String,
    val label: String,
    val enabled: Boolean,
)

data class RoundUi(
    val number: Int,
    /** Each seat's pick glyph. */
    val picks: List<String>,
    /** "Aya takes it", "Tie". */
    val line: String,
)

data class StandingUi(
    val name: String,
    val rating: String,
    /** "12 W ✦ 3 L ✦ 1 D". */
    val record: String,
    val you: Boolean,
)

data class GameUiState(
    val visible: Boolean = false,
    val loading: Boolean = true,
    val kind: GameKind? = null,
    val title: String = "",
    val rules: String = "",
    /** "Your turn", "Waiting for Aya", "Aya won", "Draw", "Open ✦ 1 seat left". */
    val status: String = "",
    val players: List<PlayerUi> = emptyList(),
    /** The board drawn: the live one, or the replay's step. */
    val board: GameState = GameState.Unknown,
    /** Ink Four: the columns a tap may drop in now. */
    val dropColumns: Set<Int> = emptySet(),
    /** Panels: may the caller draw now (each edge is checked with [GameViewModel.canDraw]). */
    val canDraw: Boolean = false,
    /** Sticker Clash: the three picks and this round's state per seat. */
    val picks: List<PickUi> = emptyList(),
    val seatPicks: List<String> = emptyList(),
    val rounds: List<RoundUi> = emptyList(),
    /** "0:42" while a turn runs. */
    val clock: String? = null,
    val urgent: Boolean = false,
    val canJoin: Boolean = false,
    val canLeave: Boolean = false,
    val leaveLabel: String = "",
    val sending: Boolean = false,
    val over: Boolean = false,
    /** Replay of a finished Ink Four or Panels game: step / last step; null when there is none. */
    val replayStep: Int? = null,
    val replaySteps: Int = 0,
    val leaderboard: List<StandingUi> = emptyList(),
    val error: String? = null,
) {
    val replayLabel: String? get() = replayStep?.let { t("games.replay.step", "step" to it, "total" to replaySteps) }
}

sealed interface GameIntent {
    data object Start : GameIntent

    data object Stop : GameIntent

    data object Join : GameIntent

    /** Before the start: give the seat back; while playing: resign. */
    data object Leave : GameIntent

    data class Pick(
        val pick: ClashPick,
    ) : GameIntent

    data class Drop(
        val col: Int,
    ) : GameIntent

    data class Draw(
        val horizontal: Boolean,
        val row: Int,
        val col: Int,
    ) : GameIntent

    /** A finished game: watch it again move by move. */
    data class ReplayTo(
        val step: Int,
    ) : GameIntent

    data object DismissError : GameIntent
}

/**
 * One game: the board the server sends (live while it is played, for players and spectators), a move sent on a tap
 * (checked against the board first, the server checks again), the turn clock, the result with ranks and rating changes,
 * a replay once it is over, and the group's leaderboard for this game.
 */
class GameViewModel(
    private val gameId: String,
    private val repository: GroupsRepository,
    private val access: GroupsAccess = GroupsAccess.Off,
    private val user: GroupsUser = GroupsUser.None,
    private val clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GameUiState())
    val state: StateFlow<GameUiState> = mutableState.asStateFlow()

    private var game: Game? = null
    private var boards: List<GameState> = emptyList()
    private var liveJob: Job? = null
    private var tickJob: Job? = null
    private var started = false
    private var on = false
    private var boardLoaded = false

    init {
        access
            .enabled()
            .distinctUntilChanged()
            .onEach { enabled ->
                on = enabled
                mutableState.update { it.copy(visible = enabled) }
                if (enabled) load()
            }.launchIn(viewModelScope)
    }

    fun onIntent(intent: GameIntent) {
        when (intent) {
            GameIntent.Start -> {
                started = true
                follow()
                tick()
            }
            GameIntent.Stop -> {
                started = false
                liveJob?.cancel()
                tickJob?.cancel()
            }
            GameIntent.Join -> act { repository.joinGame(gameId) }
            GameIntent.Leave -> act { repository.leaveGame(gameId) }
            is GameIntent.Pick -> move(Move.Pick(intent.pick))
            is GameIntent.Drop -> move(Move.Drop(intent.col))
            is GameIntent.Draw -> move(Move.Draw(Edge(intent.horizontal, intent.row, intent.col)))
            is GameIntent.ReplayTo -> replayTo(intent.step)
            GameIntent.DismissError -> mutableState.update { it.copy(error = null) }
        }
    }

    /** Panels: whether a tap on this edge would be sent. */
    fun canDraw(
        horizontal: Boolean,
        row: Int,
        col: Int,
    ): Boolean = game?.allows(Move.Draw(Edge(horizontal, row, col))) == true && !state.value.sending

    private fun load() {
        viewModelScope.launch {
            when (val result = repository.game(gameId)) {
                is AppResult.Ok -> show(result.value)
                is AppResult.Err -> mutableState.update { it.copy(loading = false, error = groupError(result.error)) }
            }
            follow()
        }
    }

    private fun follow() {
        if (!started || !on || liveJob?.isActive == true) return
        if (game?.status == GameStatus.Over || game?.status == GameStatus.Cancelled) return
        liveJob =
            viewModelScope.launch {
                repository.gameLive(gameId) { g ->
                    show(g)
                    g.status != GameStatus.Over && g.status != GameStatus.Cancelled
                }
            }
    }

    private fun tick() {
        tickJob?.cancel()
        tickJob =
            viewModelScope.launch {
                while (isActive && started) {
                    game?.let { mutableState.update { s -> s.copy(clock = clockLabel(it), urgent = urgent(it)) } }
                    delay(1_000)
                }
            }
    }

    private fun act(call: suspend () -> AppResult<Game>) {
        if (state.value.sending) return
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is AppResult.Ok -> show(result.value)
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
            mutableState.update { it.copy(sending = false) }
            follow()
        }
    }

    private fun move(move: Move) {
        val g = game ?: return
        if (!g.allows(move)) return
        act { repository.move(gameId, move) }
    }

    private fun replayTo(step: Int) {
        if (boards.isEmpty()) return
        val s = step.coerceIn(0, boards.lastIndex)
        mutableState.update { it.copy(replayStep = s, board = boards[s]) }
    }

    private fun show(g: Game) {
        val wasOver = game?.status == GameStatus.Over
        game = g
        val me = user.id()
        val over = g.status == GameStatus.Over || g.status == GameStatus.Cancelled
        if (over && !wasOver) {
            boards = Replay.boards(g)
            loadLeaderboard(g)
        }
        if (!boardLoaded) {
            boardLoaded = true
            if (g.ranked) loadLeaderboard(g)
        }
        val clash = g.state as? GameState.Clash
        val playing = g.status == GameStatus.Playing
        mutableState.update { s ->
            s.copy(
                loading = false,
                kind = g.kind,
                title =
                    (
                        g.kind?.let(
                            MessageLines::gameName,
                        ) ?: t("games.aGame")
                    ).let { if (g.ranked) t("games.ranked", "game" to it) else it },
                rules = g.kind?.let { t("games.${it.wire}.rules") }.orEmpty(),
                status = GameLines.status(g),
                players = players(g, me),
                board = if (over && boards.isNotEmpty()) boards.last() else g.state,
                dropColumns = if (playing && g.myTurn) (g.state as? GameState.Four)?.openColumns().orEmpty() else emptySet(),
                canDraw = playing && g.myTurn && g.state is GameState.Panels,
                picks =
                    if (clash != null) {
                        ClashPick.entries.map { p ->
                            PickUi(p, p.wire.uppercase(), t("games.clash.${p.wire}"), g.allows(Move.Pick(p)))
                        }
                    } else {
                        emptyList()
                    },
                seatPicks = clash?.picks?.map(::seatPick).orEmpty(),
                rounds = clash?.let { rounds(it, g) }.orEmpty(),
                clock = clockLabel(g),
                urgent = urgent(g),
                canJoin = g.canJoin(me),
                canLeave = g.mySeat != null && (g.status == GameStatus.Open || playing),
                leaveLabel = if (playing) t("games.resign") else t("games.giveSeat"),
                over = over,
                replayStep = if (over && boards.size > 1) boards.lastIndex else null,
                replaySteps = (boards.size - 1).coerceAtLeast(0),
            )
        }
    }

    private fun loadLeaderboard(g: Game) {
        val kind = g.kind ?: return
        if (!g.ranked) return
        viewModelScope.launch {
            val result = repository.leaderboard(g.groupId, kind, 1)
            if (result is AppResult.Ok) mutableState.update { it.copy(leaderboard = result.value.map(::standing)) }
        }
    }

    private fun standing(s: Standing) =
        StandingUi(
            name = s.name,
            rating = s.rating.toInt().toString(),
            record = t("games.record", "wins" to s.wins, "losses" to s.losses, "draws" to s.draws),
            you = s.userId == user.id(),
        )

    private fun players(
        g: Game,
        me: String?,
    ): List<PlayerUi> {
        val scores =
            when (val s = g.state) {
                is GameState.Clash -> s.scores
                is GameState.Panels -> s.scores
                else -> null
            }
        return g.players.map { p ->
            val change =
                if (p.ratingBefore != null && p.ratingAfter != null) {
                    val d = (p.ratingAfter - p.ratingBefore).let { kotlin.math.round(it).toInt() }
                    if (d >= 0) "+$d" else "−${-d}"
                } else {
                    null
                }
            PlayerUi(
                userId = p.userId,
                name = p.name,
                seat = p.seat,
                score = scores?.getOrNull(p.seat)?.toString(),
                you = p.userId == me,
                toPlay = g.status == GameStatus.Playing && p.seat in g.waiting,
                rank = p.rank?.let { t("games.rank", "rank" to it) },
                ratingChange = change,
            )
        }
    }

    private fun seatPick(p: SeatPick): String =
        when (p) {
            SeatPick.None -> t("games.clash.thinking")
            SeatPick.Hidden -> t("games.clash.picked")
            is SeatPick.Known -> p.pick.wire.uppercase()
        }

    private fun rounds(
        s: GameState.Clash,
        g: Game,
    ): List<RoundUi> =
        s.rounds
            .mapIndexed { i, r ->
                RoundUi(
                    number = i + 1,
                    picks = r.picks.map { it.wire.uppercase() },
                    line =
                        r.winner
                            ?.let { w -> g.players.firstOrNull { it.seat == w }?.name }
                            ?.let { t("games.clash.roundWon", "name" to it) } ?: t("games.clash.tie"),
                )
            }.reversed()

    private fun clockLabel(g: Game): String? {
        val deadline = g.turnDeadlineMillis ?: return null
        if (g.status != GameStatus.Playing) return null
        val left = ((deadline - clock.nowMillis()) / 1000).coerceAtLeast(0)
        return "${left / 60}:${(left % 60).toString().padStart(2, '0')}"
    }

    private fun urgent(g: Game): Boolean {
        val deadline = g.turnDeadlineMillis ?: return false
        return g.myTurn && deadline - clock.nowMillis() < URGENT_MS
    }

    private companion object {
        const val URGENT_MS = 10_000L
    }
}

internal object GameLines {
    /** The card's and the screen's one line. */
    fun status(g: Game): String {
        val waitingNames = g.players.filter { it.seat in g.waiting }.joinToString(", ") { it.name }
        return when (g.status) {
            GameStatus.Open -> t("games.open", "count" to g.freeSeats)
            GameStatus.Playing ->
                when {
                    g.myTurn -> t("games.yourTurn")
                    waitingNames.isNotEmpty() -> t("games.waitingFor", "names" to waitingNames)
                    else -> t("games.playing")
                }
            GameStatus.Over -> result(g)
            GameStatus.Cancelled -> t("games.cancelled")
        }
    }

    fun result(g: Game): String {
        val winners = g.players.filter { it.rank == 1 }.map { it.name }
        val ending = g.ending
        return when {
            ending == Ending.Expired -> t("games.cancelled")
            winners.isEmpty() || winners.size == g.players.size -> t("games.result.draw")
            ending == Ending.Timeout -> t("games.result.timeout", "names" to winners.joinToString(", "), "count" to winners.size)
            ending == Ending.Resign -> t("games.result.resign", "names" to winners.joinToString(", "), "count" to winners.size)
            else -> t("games.result.won", "names" to winners.joinToString(", "), "count" to winners.size)
        }
    }
}
