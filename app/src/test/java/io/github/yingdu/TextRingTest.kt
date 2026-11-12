package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRingTest {
    @Test
    fun ringHasFiveLines() {
        val lines = TextRing.lines(0.5, "25:00", listOf("a", "b", "c", "d", "e"), false, 0, 27)
        assertEquals(TextRing.ROWS, lines.size)
    }

    @Test
    fun runningShowsExactlyOneMarker() {
        val lines = TextRing.lines(0.3, "25:00", emptyList(), true, 5_000, 27)
        assertEquals(1, lines.joinToString("").count { it == '◎' })
    }

    @Test
    fun pausedShowsNoMarker() {
        val lines = TextRing.lines(0.3, "25:00", emptyList(), false, 5_000, 27)
        assertEquals(0, lines.joinToString("").count { it == '◎' })
    }

    @Test
    fun finishedRingHasNoRemainingDots() {
        val lines = TextRing.lines(1.0, "完成", emptyList(), false, 0, 27)
        assertEquals(0, lines.joinToString("").count { it == '●' })
    }

    @Test
    fun rightTextNeverPushesLinePastMaxWidth() {
        val long = "非常非常长的动作名字非常非常长的动作名字"
        val lines = TextRing.lines(0.0, "00:10", listOf(long, long, long, long, long), true, 0, 27)
        lines.forEach { assertTrue("too long: $it", it.length <= 27) }
    }
}
