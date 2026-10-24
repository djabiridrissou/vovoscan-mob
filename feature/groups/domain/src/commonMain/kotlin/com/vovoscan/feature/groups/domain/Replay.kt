package com.vovoscan.feature.groups.domain

/**
 * A finished game's boards move by move, from its `moves` (sent once it is over), so it can be watched again: the
 * same rules as the server's `domain/games.py`, only to draw (the outcome is the server's). Sticker Clash keeps its
 * rounds in its state already; it has no board to replay.
 */
object Replay {
    /** Boards after 0, 1, … every move; empty when the game can't be replayed. */
    fun boards(game: Game): List<GameState> {
        if (game.status != GameStatus.Over && game.status != GameStatus.Cancelled) return emptyList()
        val moves = game.moves.mapNotNull { m -> m.move?.let { m.seat to it } }
        return when (val final = game.state) {
            is GameState.Four -> four(moves, final)
            is GameState.Panels -> panels(moves, final)
            else -> emptyList()
        }
    }

    private fun four(
        moves: List<Pair<Int, Move>>,
        final: GameState.Four,
    ): List<GameState> {
        val board = MutableList(GameState.Four.ROWS) { MutableList(GameState.Four.COLS) { -1 } }
        var state = GameState.Four(board.map { it.toList() }, moves.firstOrNull()?.first ?: 0, null, emptyList())
        val out = mutableListOf<GameState>(state)
        for ((seat, move) in moves) {
            val col = (move as? Move.Drop)?.col ?: return emptyList()
            val row = state.landing(col) ?: return emptyList()
            board[row][col] = seat
            state = GameState.Four(board.map { it.toList() }, 1 - seat, row to col, emptyList())
            out += state
        }
        // The winning line shows on the last board, as the server drew it.
        out[out.lastIndex] = (out.last() as GameState.Four).copy(line = final.line)
        return out
    }

    private fun panels(
        moves: List<Pair<Int, Move>>,
        final: GameState.Panels,
    ): List<GameState> {
        val n = final.size
        val h = MutableList(n + 1) { MutableList(n) { -1 } }
        val v = MutableList(n) { MutableList(n + 1) { -1 } }
        val boxes = MutableList(n) { MutableList(n) { -1 } }
        val scores = MutableList(final.seats) { 0 }

        fun snapshot(
            turn: Int,
            last: Edge?,
        ) = GameState.Panels(
            n,
            h.map { it.toList() },
            v.map { it.toList() },
            boxes.map { it.toList() },
            scores.toList(),
            turn,
            final.seats,
            last,
        )
        val out = mutableListOf<GameState>(snapshot(moves.firstOrNull()?.first ?: 0, null))
        for ((seat, move) in moves) {
            val edge = (move as? Move.Draw)?.edge ?: return emptyList()
            val grid = if (edge.horizontal) h else v
            if (grid.getOrNull(edge.row)?.getOrNull(edge.col) != -1) return emptyList()
            grid[edge.row][edge.col] = seat
            val around =
                if (edge.horizontal) {
                    listOf(edge.row - 1 to edge.col, edge.row to edge.col)
                } else {
                    listOf(
                        edge.row to edge.col - 1,
                        edge.row to edge.col,
                    )
                }
            var closed = 0
            for ((br, bc) in around) {
                if (br !in 0 until n || bc !in 0 until n || boxes[br][bc] != -1) continue
                if (h[br][bc] != -1 && h[br + 1][bc] != -1 && v[br][bc] != -1 && v[br][bc + 1] != -1) {
                    boxes[br][bc] = seat
                    scores[seat] += 1
                    closed += 1
                }
            }
            out += snapshot(if (closed > 0) seat else (seat + 1) % final.seats, edge)
        }
        return out
    }
}
