package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutTest {
    private fun move(name: String, reps: Int, each: Int, unit: WorkoutUnit = WorkoutUnit.SEC) =
        WorkoutMove("m$name", name, reps, each, unit)

    private fun group(rounds: Int = 1, rest: Int = 0, unit: WorkoutUnit = WorkoutUnit.SEC, vararg moves: WorkoutMove) =
        WorkoutGroup("g", "室内健身", rounds, rest, unit, moves.toList())

    @Test fun squatRepsThenRestThenNext() {
        val g = group(1, 30, WorkoutUnit.SEC, move("深蹲", 10, 2), move("俯卧撑", 10, 2))
        val plan = WorkoutLogic.planOf(g)
        assertEquals(2_000L, plan.moves[0].repMs)
        assertEquals(30_000L, plan.restMs)
        var s = WorkoutLogic.start(WorkoutLogic.fresh(plan), 0)
        assertEquals(0, s.rep)
        assertEquals(2_000L, WorkoutLogic.remaining(s, 0))
        repeat(9) { s = WorkoutLogic.nextSlice(s) }
        assertEquals(WorkoutPhase.REP, s.phase)
        assertEquals(0, s.move)
        assertEquals(9, s.rep)
        s = WorkoutLogic.nextSlice(s)
        assertEquals(WorkoutPhase.REST, s.phase)
        s = WorkoutLogic.start(s, 100)
        s = WorkoutLogic.nextSlice(s)
        assertEquals(WorkoutPhase.REP, s.phase)
        assertEquals(1, s.move)
        assertEquals(0, s.rep)
        assertEquals("俯卧撑", WorkoutLogic.step(s)?.name)
    }

    @Test fun jogOnceThirtyMinutes() {
        val plan = WorkoutLogic.planOf(group(1, 0, WorkoutUnit.SEC, move("慢跑", 1, 30, WorkoutUnit.MIN)))
        assertEquals(30 * 60_000L, plan.moves[0].repMs)
        val s = WorkoutLogic.nextSlice(WorkoutLogic.fresh(plan))
        assertEquals(WorkoutPhase.DONE, s.phase)
    }

    @Test fun restBetweenRoundsAndNotAfterLast() {
        val plan = WorkoutLogic.planOf(group(2, 10, WorkoutUnit.SEC, move("深蹲", 1, 2)))
        var s = WorkoutLogic.nextSlice(WorkoutLogic.fresh(plan))
        assertEquals(WorkoutPhase.REST, s.phase)
        assertEquals("深蹲", WorkoutLogic.nextName(s))
        s = WorkoutLogic.nextSlice(s)
        assertEquals(WorkoutPhase.REP, s.phase)
        assertEquals(1, s.round)
        s = WorkoutLogic.nextSlice(s)
        assertEquals(WorkoutPhase.DONE, s.phase)
    }

    @Test fun noRestSkipsTheGap() {
        val plan = WorkoutLogic.planOf(group(1, 0, WorkoutUnit.SEC, move("深蹲", 1, 2), move("俯卧撑", 1, 2)))
        val s = WorkoutLogic.nextSlice(WorkoutLogic.fresh(plan))
        assertEquals(WorkoutPhase.REP, s.phase)
        assertEquals(1, s.move)
    }

    @Test fun pauseKeepsRemaining() {
        val plan = WorkoutLogic.planOf(group(1, 0, WorkoutUnit.SEC, move("深蹲", 3, 2)))
        val going = WorkoutLogic.start(WorkoutLogic.fresh(plan), 1_000)
        val paused = WorkoutLogic.pause(going, 1_500)
        assertEquals(1_500L, WorkoutLogic.remaining(paused, 9_999))
        val again = WorkoutLogic.start(paused, 2_000)
        assertEquals(3_500L, again.endAt)
    }

    @Test fun slightDelayGivesTheNextRepItsFullTime() {
        val plan = WorkoutLogic.planOf(group(1, 0, WorkoutUnit.SEC, move("深蹲", 10, 2)))
        val s = WorkoutLogic.start(WorkoutLogic.fresh(plan), 0)
        val n = WorkoutLogic.catchUp(s, 2_100)
        assertEquals(1, n.rep)
        assertEquals(2_100L + 2_000L, n.endAt)
        assertEquals(true, n.running)
    }

    @Test fun longGapCountsFinishedReps() {
        val plan = WorkoutLogic.planOf(group(1, 0, WorkoutUnit.SEC, move("深蹲", 10, 2)))
        val s = WorkoutLogic.start(WorkoutLogic.fresh(plan), 0)
        val n = WorkoutLogic.catchUp(s, 5_000)
        assertEquals(2, n.rep)
        assertEquals(5_000L + 2_000L, n.endAt)
    }

    @Test fun skipMoveGoesToRestThenNext() {
        val plan = WorkoutLogic.planOf(group(1, 10, WorkoutUnit.SEC, move("深蹲", 10, 2), move("俯卧撑", 5, 2)))
        val rest = WorkoutLogic.skipMove(WorkoutLogic.fresh(plan))
        assertEquals(WorkoutPhase.REST, rest.phase)
        val next = WorkoutLogic.skipMove(rest)
        assertEquals(WorkoutPhase.REP, next.phase)
        assertEquals(1, next.move)
        assertEquals(0, next.rep)
    }

    @Test fun remainText() {
        assertEquals("2.0", WorkoutLogic.remainText(2_000))
        assertEquals("1.5", WorkoutLogic.remainText(1_500))
        assertEquals("10", WorkoutLogic.remainText(10_000))
        assertEquals("1:00", WorkoutLogic.remainText(60_000))
        assertEquals("1:30", WorkoutLogic.remainText(90_000))
    }

    @Test fun jsonRoundTrip() {
        val g = group(3, 45, WorkoutUnit.SEC, move("深蹲", 10, 2), move("慢跑", 1, 30, WorkoutUnit.MIN))
        val back = WorkoutJson.groupsFrom(WorkoutJson.groups(listOf(g)))
        assertEquals(listOf(g), back)
        val run = WorkoutLogic.start(WorkoutLogic.fresh(WorkoutLogic.planOf(g)), 50)
        val again = WorkoutJson.runFrom(WorkoutJson.run(run))
        assertEquals(run, again)
        assertNull(WorkoutJson.runFrom("nope"))
        assertEquals(emptyList<WorkoutGroup>(), WorkoutJson.groupsFrom(""))
    }
}
