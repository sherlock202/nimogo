package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoBrightnessFilterTest {
    @Test
    fun jitterInAConstantEnvironmentNeverMoves() {
        val f = AutoBrightnessFilter()
        var moved = 0
        for (i in 0 until 120) {
            val target = if (i % 2 == 0) 35.0 else 45.0
            if (f.feed(i * 1_000L, target, 40.0) != null) moved++
        }
        assertEquals(0, moved)
    }

    @Test
    fun aRealChangeMovesRightAway() {
        val f = AutoBrightnessFilter()
        val r = f.feed(0, 80.0, 40.0)
        assertNotNull(r)
        assertEquals(80.0, r!!, 0.001)
    }

    @Test
    fun aSecondMoveWaitsForTheMinimumGap() {
        val f = AutoBrightnessFilter()
        assertNotNull(f.feed(0, 80.0, 40.0))
        assertNull(f.feed(1_000, 0.0, 80.0))
        assertNotNull(f.feed(7_000, 0.0, 80.0))
    }

    @Test
    fun resetComparesAgainstTheCurrentBrightnessAgain() {
        val f = AutoBrightnessFilter()
        assertNotNull(f.feed(0, 80.0, 40.0))
        f.reset()
        // 手动调到 80 以后，环境还是 80：不用动
        assertNull(f.feed(10_000, 80.0, 80.0))
    }
}
