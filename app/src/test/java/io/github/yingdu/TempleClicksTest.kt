package io.github.yingdu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TempleClicksTest {
    private val left = 0x13
    private val right = 0x03
    private val doubleLeft = 0x14

    @Test
    fun quickRepeatOfTheSameClickIsDropped() {
        val t = TempleClicks()
        assertTrue(t.tryDeliver(0, left))
        assertFalse(t.tryDeliver(200, left))
    }

    @Test
    fun aClickAfterTheRepeatWindowCountsAgain() {
        val t = TempleClicks()
        assertTrue(t.tryDeliver(0, right))
        assertTrue(t.tryDeliver(1_000, right))
    }

    @Test
    fun aDoubleClickAfterASingleClickIsStillDelivered() {
        val t = TempleClicks()
        assertTrue(t.tryDeliver(0, left))
        assertTrue(t.tryDeliver(250, doubleLeft))
    }

    @Test
    fun aDroppedRepeatDoesNotExtendTheWindow() {
        val t = TempleClicks()
        assertTrue(t.tryDeliver(0, right))
        assertFalse(t.tryDeliver(300, right))
        assertTrue(t.tryDeliver(500, right))
    }
}
