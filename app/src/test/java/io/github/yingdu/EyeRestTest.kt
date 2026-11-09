package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EyeRestTest {
    private fun near(a: Float, b: Float, eps: Float = 0.05f) = abs(a - b) < eps

    @Test fun miziSweepsTenTimesOnEachLineAtFullSize() {
        assertEquals(16_000L, EyeLogic.LINE_MS)
        assertEquals(64_000L, EyeLogic.MIZI_MS)
        // 竖：从上到下，再从下到上
        val v0 = EyeLogic.spot(EyeMode.TRAIN, 0)
        assertEquals(EyePhase.MIZI_V, v0.phase)
        assertTrue(near(v0.x, 0f) && near(v0.y, -1f))
        val vBottom = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.STROKE_MS)
        assertTrue(near(vBottom.x, 0f) && near(vBottom.y, 1f))
        val vTop = EyeLogic.spot(EyeMode.TRAIN, 2 * EyeLogic.STROKE_MS)
        assertTrue(near(vTop.y, -1f))
        // 横：从左到右，再回来
        val h0 = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.LINE_MS)
        assertEquals(EyePhase.MIZI_H, h0.phase)
        assertTrue(near(h0.x, -1f) && near(h0.y, 0f))
        val hRight = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.LINE_MS + EyeLogic.STROKE_MS)
        assertTrue(near(hRight.x, 1f))
        // 斜一：左上到右下；斜二：左下到右上
        val d1 = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.LINE_MS * 2)
        assertEquals(EyePhase.MIZI_D1, d1.phase)
        assertTrue(near(d1.x, -1f) && near(d1.y, -1f))
        val d1End = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.LINE_MS * 2 + EyeLogic.STROKE_MS)
        assertTrue(near(d1End.x, 1f) && near(d1End.y, 1f))
        val d2 = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.LINE_MS * 3)
        assertEquals(EyePhase.MIZI_D2, d2.phase)
        assertTrue(near(d2.x, -1f) && near(d2.y, 1f))
        val d2End = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.LINE_MS * 3 + EyeLogic.STROKE_MS)
        assertTrue(near(d2End.x, 1f) && near(d2End.y, -1f))
    }

    @Test fun orbitsThenDepthThenRest() {
        assertEquals(20_000L, EyeLogic.ORBIT_MS)
        assertEquals(16_000L, EyeLogic.DEPTH_MS)
        val cw = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.MIZI_MS)
        assertEquals(EyePhase.CW, cw.phase)
        assertTrue(near(cw.x, 0f))
        assertTrue(near(cw.y, -1f))
        val cwRight = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.MIZI_MS + EyeLogic.LAP_MS / 4)
        assertTrue(near(cwRight.x, 1f))
        assertTrue(near(cwRight.y, 0f))
        val stillCw = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.MIZI_MS + EyeLogic.LAP_MS * 9)
        assertEquals(EyePhase.CW, stillCw.phase)
        assertTrue(near(stillCw.y, -1f))
        val ccw = EyeLogic.spot(EyeMode.TRAIN, EyeLogic.MIZI_MS + EyeLogic.ORBIT_MS + EyeLogic.LAP_MS / 4)
        assertEquals(EyePhase.CCW, ccw.phase)
        assertTrue(near(ccw.x, -1f))
        val depthStart = EyeLogic.MIZI_MS + EyeLogic.ORBIT_MS * 2
        val far = EyeLogic.spot(EyeMode.TRAIN, depthStart)
        val nearDot = EyeLogic.spot(EyeMode.TRAIN, depthStart + EyeLogic.DEPTH_CYCLE_MS / 2)
        val farAgain = EyeLogic.spot(EyeMode.TRAIN, depthStart + EyeLogic.DEPTH_CYCLE_MS * 9)
        assertEquals(EyePhase.DEPTH, far.phase)
        assertTrue(far.scale < 0.1f)
        assertTrue(nearDot.scale > 0.9f)
        assertTrue(farAgain.scale < 0.1f)
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
        assertEquals(EyeLogic.LINE_MS, EyeLogic.nextMark(EyeMode.TRAIN, 1000))
        assertEquals(EyeLogic.total(EyeMode.SLEEP), EyeLogic.nextMark(EyeMode.SLEEP, 1000))
        assertEquals("1:00", EyeLogic.clock(60_000))
        assertEquals("0:20", EyeLogic.clock(20_000))
    }
}
