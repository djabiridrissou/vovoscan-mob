package com.vovoscan.feature.groups.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GamesTest {
    private val empty4 = GameState.Four(List(GameState.Four.ROWS) { List(GameState.Four.COLS) { -1 } }, 0, null, emptyList())

    @Test
    fun aDiscFallsToTheLowestFreeRow() {
        assertEquals(5, empty4.landing(3))
        val full = empty4.copy(board = empty4.board.map { row -> row.mapIndexed { c, v -> if (c == 0) 1 else v } })
        assertEquals(null, full.landing(0))
        assertEquals((1..6).toSet(), full.openColumns())
    }

    @Test
    fun theCallerMovesOnlyOnTheirTurnAndOnlyWhereTheBoardTakesIt() {
        val g = game(empty4, mySeat = 0, waiting = listOf(0))
        assertTrue(g.myTurn)
        assertTrue(g.allows(Move.Drop(3)))
        assertFalse(g.allows(Move.Drop(7)))
        assertFalse(g.allows(Move.Pick(ClashPick.Bam)))
        assertFalse(g.copy(waiting = listOf(1)).allows(Move.Drop(3)))
        val clash = GameState.Clash(1, listOf(0, 0), listOf(SeatPick.Hidden, SeatPick.None), emptyList())
        assertFalse(game(clash, mySeat = 0, waiting = listOf(0, 1)).allows(Move.Pick(ClashPick.Snip)))
        assertTrue(game(clash, mySeat = 1, waiting = listOf(0, 1)).allows(Move.Pick(ClashPick.Snip)))
    }

    @Test
    fun anInviteOnlyGameSeatsOnlyThoseInvited() {
        val open = game(empty4, status = GameStatus.Open, mySeat = null, players = 1).copy(inviteOnly = true, invited = listOf("u2"))
        assertTrue(open.canJoin("u2"))
        assertFalse(open.canJoin("u3"))
        assertTrue(open.copy(inviteOnly = false).canJoin("u3"))
        assertFalse(open.copy(seats = 1).canJoin("u2"))
    }

    @Test
    fun clashPicksBeatInACircle() {
        assertEquals("snip", ClashPick.Bam.beats)
        assertEquals("flap", ClashPick.Snip.beats)
        assertEquals("bam", ClashPick.Flap.beats)
    }

    @Test
    fun aFinishedFourGameReplaysMoveByMove() {
        val line = listOf(5 to 0, 5 to 1, 5 to 2, 5 to 3)
        val moves =
            listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1, 0 to 2, 1 to 2, 0 to 3).map { (seat, col) ->
                PlayedMove(seat, Move.Drop(col), 0)
            }
        val g = game(empty4.copy(line = line), status = GameStatus.Over).copy(moves = moves)
        val boards = Replay.boards(g).map { it as GameState.Four }
        assertEquals(8, boards.size)
        assertEquals(1, boards[2].board[4][0])
        assertEquals(4 to 0, boards[2].last)
        assertEquals(line, boards.last().line)
        assertEquals(emptyList(), boards[6].line)
        assertEquals(emptyList(), Replay.boards(g.copy(status = GameStatus.Playing)))
    }

    @Test
    fun closingABoxScoresItAndPlaysAgain() {
        val n = 2
        val final =
            GameState.Panels(
                n,
                List(n + 1) {
                    List(n) { -1 }
                },
                List(n) { List(n + 1) { -1 } },
                List(n) { List(n) { -1 } },
                listOf(0, 0),
                0,
                2,
                null,
            )
        val edges = listOf(Edge(true, 0, 0), Edge(true, 1, 0), Edge(false, 0, 0), Edge(false, 0, 1))
        val moves = edges.mapIndexed { i, e -> PlayedMove(i % 2, Move.Draw(e), 0) }
        val boards = Replay.boards(game(final, status = GameStatus.Over).copy(moves = moves)).map { it as GameState.Panels }
        assertEquals(5, boards.size)
        assertEquals(1, boards.last().boxes[0][0])
        assertEquals(listOf(0, 1), boards.last().scores)
        assertEquals(1, boards.last().turn)
        assertEquals(1, boards[1].turn)
        // A move drawn twice can't be replayed.
        assertEquals(emptyList(), Replay.boards(game(final, status = GameStatus.Over).copy(moves = moves + moves.last())))
    }

    private fun game(
        state: GameState,
        status: GameStatus = GameStatus.Playing,
        mySeat: Int? = 0,
        waiting: List<Int> = emptyList(),
        players: Int = 2,
    ) = Game(
        id = "g1",
        groupId = "grp",
        kind = GameKind.Four,
        seats = 2,
        ranked = false,
        status = status,
        ending = null,
        state = state,
        players = (0 until players).map { Player("u${it + 1}", "P$it", it, null, null, null) },
        turn = null,
        waiting = waiting,
        turnDeadlineMillis = null,
        mySeat = mySeat,
        invited = emptyList(),
        inviteOnly = false,
        createdById = "u1",
        messageId = null,
        moveCount = 0,
        moves = emptyList(),
        createdAtMillis = 0,
        endedAtMillis = null,
    )
}
