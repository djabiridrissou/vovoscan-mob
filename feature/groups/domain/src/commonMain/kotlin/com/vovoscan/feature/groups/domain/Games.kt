package com.vovoscan.feature.groups.domain

/*
 * Group games (docs/GAMES.md). The server runs every rule, clock and rating: a client draws the state it is sent and
 * sends a move. What is here only reads a state (which moves the board still allows, so a tap on a full column or a
 * drawn edge is never sent) and never decides an outcome.
 */

enum class GameKind(
    val wire: String,
    /** Seats the game takes. */
    val seats: List<Int>,
    /** The turn clock, seconds. */
    val turnSeconds: Int,
) {
    /** Sticker Clash: both pick BAM, SNIP or FLAP in secret each round; first to 3. */
    Clash("clash", listOf(2), 45),

    /** Ink Four: drop discs in 7 columns of 6 rows; four in a line. */
    Four("four", listOf(2), 60),

    /** Panels: draw edges of a 4 x 4 grid of boxes; closing one scores it and plays again. */
    Panels("panels", listOf(2, 3), 60),
    ;

    companion object {
        fun of(wire: String?): GameKind? = entries.firstOrNull { it.wire == wire }
    }
}

enum class GameStatus(
    val wire: String,
) {
    /** Waiting for players. */
    Open("open"),
    Playing("playing"),
    Over("over"),
    Cancelled("cancelled"),
    ;

    companion object {
        fun of(wire: String?): GameStatus = entries.firstOrNull { it.wire == wire } ?: Open
    }
}

/** How a game ended: `win`, `draw`, `timeout`, `resign`, `expired` (an open game nobody completed). */
enum class Ending(
    val wire: String,
) {
    Win("win"),
    Draw("draw"),
    Timeout("timeout"),
    Resign("resign"),
    Expired("expired"),
    ;

    companion object {
        fun of(wire: String?): Ending? = entries.firstOrNull { it.wire == wire }
    }
}

/** BAM breaks SNIP, SNIP cuts FLAP, FLAP wraps BAM. */
enum class ClashPick(
    val wire: String,
    val beats: String,
) {
    Bam("bam", "snip"),
    Snip("snip", "flap"),
    Flap("flap", "bam"),
    ;

    companion object {
        fun of(wire: String?): ClashPick? = entries.firstOrNull { it.wire == wire }
    }
}

/** A seat's pick this round, as the caller may see it: none yet, made but secret, or known (one's own, or once over). */
sealed interface SeatPick {
    data object None : SeatPick

    data object Hidden : SeatPick

    data class Known(
        val pick: ClashPick,
    ) : SeatPick
}

data class ClashRound(
    val picks: List<ClashPick>,
    /** The winning seat, null for a tie. */
    val winner: Int?,
)

/** A board as the server sent it. `-1` = empty (a cell, an edge, a box); otherwise the seat that took it. */
sealed interface GameState {
    data class Clash(
        val round: Int,
        val scores: List<Int>,
        val picks: List<SeatPick>,
        val rounds: List<ClashRound>,
    ) : GameState {
        companion object {
            const val TARGET = 3
            const val MAX_ROUNDS = 9
        }
    }

    /** Row 0 is the top; a disc falls to the lowest free row. */
    data class Four(
        val board: List<List<Int>>,
        val turn: Int,
        /** The last disc: (row, col). */
        val last: Pair<Int, Int>?,
        /** The winning line's cells, once there is one. */
        val line: List<Pair<Int, Int>>,
    ) : GameState {
        /** Columns a disc still fits in. */
        fun openColumns(): Set<Int> = (0 until COLS).filter { col -> board.firstOrNull()?.getOrNull(col) == -1 }.toSet()

        /** Where a disc dropped in [col] lands, or null when the column is full. */
        fun landing(col: Int): Int? = (ROWS - 1 downTo 0).firstOrNull { row -> board.getOrNull(row)?.getOrNull(col) == -1 }

        companion object {
            const val COLS = 7
            const val ROWS = 6
        }
    }

    /** [h]: (size + 1) rows x size horizontal edges; [v]: size rows x (size + 1) vertical edges; [boxes]: size x size. */
    data class Panels(
        val size: Int,
        val h: List<List<Int>>,
        val v: List<List<Int>>,
        val boxes: List<List<Int>>,
        val scores: List<Int>,
        val turn: Int,
        val seats: Int,
        val last: Edge?,
    ) : GameState {
        fun drawn(edge: Edge): Boolean =
            when (edge.horizontal) {
                true -> h.getOrNull(edge.row)?.getOrNull(edge.col)
                false -> v.getOrNull(edge.row)?.getOrNull(edge.col)
            } != -1

        fun inGrid(edge: Edge): Boolean =
            if (edge.horizontal) edge.row in 0..size && edge.col in 0 until size else edge.row in 0 until size && edge.col in 0..size
    }

    /** A game the app does not know (a newer server): shown as a card, not played. */
    data object Unknown : GameState
}

data class Edge(
    val horizontal: Boolean,
    val row: Int,
    val col: Int,
)

/** A move as the contract takes it: a pick, a column, an edge. */
sealed interface Move {
    data class Pick(
        val pick: ClashPick,
    ) : Move

    data class Drop(
        val col: Int,
    ) : Move

    data class Draw(
        val edge: Edge,
    ) : Move
}

data class Player(
    val userId: String,
    val name: String,
    val seat: Int,
    /** Once over: 1 = first; ties share a rank. */
    val rank: Int?,
    val ratingBefore: Double?,
    val ratingAfter: Double?,
)

data class Game(
    val id: String,
    val groupId: String,
    val kind: GameKind?,
    val seats: Int,
    val ranked: Boolean,
    val status: GameStatus,
    val ending: Ending?,
    val state: GameState,
    val players: List<Player>,
    /** The seat to play (null for Sticker Clash, where both play each round, and outside play). */
    val turn: Int?,
    /** Seats the game waits for now. */
    val waiting: List<Int>,
    val turnDeadlineMillis: Long?,
    /** The caller's seat, null for a spectator. */
    val mySeat: Int?,
    val invited: List<String>,
    val inviteOnly: Boolean,
    val createdById: String?,
    val messageId: String?,
    val moveCount: Int,
    /** Every move in order once over (a replay). */
    val moves: List<PlayedMove>,
    val createdAtMillis: Long,
    val endedAtMillis: Long?,
) {
    val myTurn: Boolean get() = status == GameStatus.Playing && mySeat != null && mySeat in waiting
    val freeSeats: Int get() = (seats - players.size).coerceAtLeast(0)

    /** May the caller sit down: an open game with a free seat, and invited when the seats are kept for those invited. */
    fun canJoin(userId: String?): Boolean =
        status == GameStatus.Open && mySeat == null && freeSeats > 0 && (!inviteOnly || (userId != null && userId in invited))

    /** Whether [move] is one the board still takes from the caller (the server checks again). */
    fun allows(move: Move): Boolean {
        if (!myTurn) return false
        return when (val s = state) {
            is GameState.Clash -> move is Move.Pick && s.picks.getOrNull(mySeat ?: -1) == SeatPick.None
            is GameState.Four -> move is Move.Drop && move.col in s.openColumns()
            is GameState.Panels -> move is Move.Draw && s.inGrid(move.edge) && !s.drawn(move.edge)
            GameState.Unknown -> false
        }
    }
}

data class PlayedMove(
    val seat: Int,
    val move: Move?,
    val atMillis: Long,
)

/** A group's leaderboard row for one game. */
data class Standing(
    val userId: String,
    val name: String,
    val rating: Double,
    val played: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
)
