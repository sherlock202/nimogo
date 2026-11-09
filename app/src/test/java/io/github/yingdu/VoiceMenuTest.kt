package io.github.yingdu

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceMenuTest {
    private val games = listOf("2048", "贪吃蛇", "俄罗斯方块", "扫雷", "数独", "数字华容道", "推箱子", "五子棋", "记忆翻牌")
    private val cmds = VoiceMenu.commands("Muse", games)
    private val py = mapOf(
        '番' to "fan", '翻' to "fan", '茄' to "qie", '钟' to "zhong", '中' to "zhong",
        '二' to "er", '零' to "ling", '四' to "si", '八' to "ba", '〇' to "ling",
        '记' to "ji", '忆' to "yi", '牌' to "pai",
        '贪' to "tan", '吃' to "chi", '蛇' to "she",
        '看' to "kan", '书' to "shu",
        '锻' to "duan", '炼' to "lian",
    )

    private fun idOf(heard: String) = VoiceMenu.match(heard, cmds) { py[it] }?.id

    @Test fun exactAndFiller() {
        assertEquals("pomo", idOf("番茄钟"))
        assertEquals("pomo", idOf("打开番茄钟"))
        assertEquals("workout", idOf("锻炼"))
        assertEquals("workout", idOf("打开锻炼"))
        assertEquals("eyes", idOf("视力训练"))
        assertEquals("sleep", idOf("催眠"))
        assertEquals("read", idOf("我想看书"))
        assertEquals("assist", idOf("请打开场景助手"))
    }

    @Test fun longestAliasWins() {
        assertEquals("game:记忆翻牌", idOf("记忆翻牌"))
        assertEquals("memory", idOf("打开记忆"))
        assertEquals("game:俄罗斯方块", idOf("俄罗斯方块"))
        assertEquals("game:贪吃蛇", idOf("我想看贪吃蛇"))
    }

    @Test fun homophoneAndDigits() {
        assertEquals("pomo", idOf("翻茄中"))
        assertEquals("game:2048", idOf("二零四八"))
        assertEquals("game:2048", idOf("2048"))
    }

    @Test fun ringGridAndKeys() {
        val n = VoiceMenu.ringItems(cmds, false).size
        assertEquals(4, VoiceMenu.columnsFor(n))
        assertEquals(3, VoiceMenu.columnsFor(9))
        assertEquals(1, VoiceMenu.move(0, n, 4, "right"))
        assertEquals(n - 1, VoiceMenu.move(0, n, 4, "left"))
        assertEquals(4, VoiceMenu.move(0, n, 4, "down"))
        // 最左最上抬头到最右最下；一列走完低头到右边一列最上，最右最下再低头回到最左最上
        assertEquals(11, VoiceMenu.move(0, n, 4, "up"))
        assertEquals(1, VoiceMenu.move(12, n, 4, "down"))
        assertEquals(0, VoiceMenu.move(11, n, 4, "down"))
        assertEquals(12, VoiceMenu.move(1, n, 4, "up"))
        val seen = mutableSetOf<Int>()
        var i = 0
        repeat(n) { seen.add(i); i = VoiceMenu.move(i, n, 4, "down") }
        assertEquals(n, seen.size)
        assertEquals(0, i)
        assertEquals("up", VoiceMenu.keyAction(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("down", VoiceMenu.keyAction(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals("left", VoiceMenu.keyAction(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("right", VoiceMenu.keyAction(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("ok", VoiceMenu.keyAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("ok", VoiceMenu.keyAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test fun gamesPageAndMiss() {
        assertEquals("games", idOf("小游戏"))
        assertEquals("dash", idOf("回到看板"))
        assertNull(idOf("今天天气不错"))
        assertNull(idOf("嗯"))
    }
}
