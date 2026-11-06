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
    )

    private fun idOf(heard: String) = VoiceMenu.match(heard, cmds) { py[it] }?.id

    @Test fun exactAndFiller() {
        assertEquals("pomo", idOf("番茄钟"))
        assertEquals("pomo", idOf("打开番茄钟"))
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

    @Test fun ringWindowAndKeys() {
        val labels = VoiceMenu.ringItems(cmds, false).map { it.label }
        assertEquals(listOf("【阅读】", "  导航", "  番茄钟", "  表盘"), VoiceMenu.window(labels, 0, 4))
        assertEquals("【跟读】", VoiceMenu.window(labels, labels.lastIndex - 1, 4).last { it.startsWith("【") })
        assertEquals("up", VoiceMenu.keyAction(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("down", VoiceMenu.keyAction(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals("ok", VoiceMenu.keyAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("ok", VoiceMenu.keyAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertNull(VoiceMenu.keyAction(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test fun gamesPageAndMiss() {
        assertEquals("games", idOf("小游戏"))
        assertEquals("dash", idOf("回到看板"))
        assertNull(idOf("今天天气不错"))
        assertNull(idOf("嗯"))
    }
}
