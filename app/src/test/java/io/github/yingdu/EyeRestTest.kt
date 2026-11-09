package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EyeRestTest {
    private fun near(a: Float, b: Float, eps: Float = 0.05f) = abs(a - b) < eps

    @Test fun miziGoesOutAndBackOnEightRays() {
        val start = EyeLogic.spot(EyeMode.TRAIN, 0)
        assertEquals(EyePhase.MIZI, start.phase)
        assertTrue(near(start.x, 0f) && near(start.y, 0f))
        val tip = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.RAY_MS / 2)
        assertTrue(near(tip.x, 0f))
        assertTrue(near(tip.y, -1f))
        val back = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.RAY_MS)
        assertTrue(near(back.x, 0f) && near(back.y, 0f))
        val diagonal = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.RAY_MS + EyeLogic.RAY_MS / 2)
        assertTrue(near(diagonal.x, 0.707f))
        assertTrue(near(diagonal.y, -0.707f))
    }

    @Test fun orbitsThenDepthThenRest() {
        val cw = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.MIZI_MS)
        assertEquals(EyePhase.CW, cw.phase)
        assertTrue(near(cw.x, 0f))
        assertTrue(cw.y < -0.7f)
        val cwRight = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.MIZI_MS + EyeLogic.ORBIT_MS / 4)
        assertTrue(cwRight.x > 0.7f)
        assertTrue(near(cwRight.y, 0f))
        val ccw = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.MIZI_MS + EyeLogic.ORBIT_MS + EyeLogic.ORBIT_MS / 4)
        assertEquals(EyePhase.CCW, ccw.phase)
        assertTrue(ccw.x < -0.7f)
        val depthStart = EyeLogic.MIZI_MS + EyeLogic.ORBIT_MS * 2
        val far = EyeLogic.spot(EyeMode.TRAIN, depthStart)
        val nearDot = EyeLogic.spot(EyeMode.TRAIN, depthStart + EyeLogic.DEPTH_MS / 6)
        assertEquals(EyePhase.DEPTH, far.phase)
        assertTrue(far.scale < 0.1f)
        assertTrue(nearDot.scale > 0.9f)
        val rest = EyeLogic.spot(EyeMode.TRAIN, depthStart + EyeLogic.DEPTH_MS)
        assertEquals(EyePhase.REST, rest.phase)
        assertEquals(0f, rest.scale)
        assertEquals(EyePhase.DONE, EyeLogic.phase(EyeMode.TRAIN, EyeLogic.total(EyeMode.TRAIN)))
    }

    @Test fun hypnosisSwingsThenEndsQuietly() {
        assertEquals(5 * 60_000L, EyeLogic.total(EyeMode.SLEEP))
        val mid = EyeLogic.spot(EyeMode.SLEEP, EyeLogic.SWING_MS / 4)
        assertEquals(EyePhase.SWING, mid.phase)
        assertTrue(mid.x > 0.5f)
        assertTrue(near(mid.y, 0f))
        assertTrue(mid.dim)
        val back = EyeLogic.spot(EyeMode.SLEEP, EyeLogic.SWING_MS / 2)
        assertTrue(near(back.x, 0f))
        assertEquals(EyePhase.DONE, EyeLogic.phase(EyeMode.SLEEP, EyeLogic.SLEEP_MS))
        assertEquals(0f, EyeLogic.spot(EyeMode.SLEEP, EyeLogic.SLEEP_MS).scale)
    }

    @Test fun skipLandsOnTheNextSegment() {
        assertEquals(EyeLogic.MIZI_MS, EyeLogic.nextMark(EyeMode.TRAIN, 1000))
        assertEquals(EyeLogic.total(EyeMode.SLEEP), EyeLogic.nextMark(EyeMode.SLEEP, 1000))
        assertEquals("1:00", EyeLogic.clock(60_000))
        assertEquals("0:20", EyeLogic.clock(20_000))
    }
}
