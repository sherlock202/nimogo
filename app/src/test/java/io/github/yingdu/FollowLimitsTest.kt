package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FollowLimitsTest {
    @Test fun searchLines() {
        // 跟上了：正在念的行 → 往后 3 行
        assertEquals(10 to 13, FollowLimits.lines(10, 8, 5, 100, locked = true))
        // 还没跟上（刚手动翻页）：至少到这一屏的最后一行
        assertEquals(8 to 14, FollowLimits.lines(8, 8, 7, 100, locked = false))
        // 到讲稿末尾为止
        assertEquals(98 to 99, FollowLimits.lines(98, 95, 5, 100, locked = true))
    }

    @Test fun skipShortLine() {
        // 三行：0..10、10..12（2 个字的短行）、12..30
        val starts = intArrayOf(0, 10, 12); val ends = intArrayOf(10, 12, 30)
        fun lineOf(o: Int) = (starts.indices.lastOrNull { starts[it] <= o } ?: 0)
        // 念到第 0 行末尾：短的第 1 行当作念过了
        assertEquals(12, FollowLimits.skipShort(9, 3, ::lineOf, { starts[it] }, { ends[it] }))
        assertEquals(12, FollowLimits.skipShort(10, 3, ::lineOf, { starts[it] }, { ends[it] }))
        // 还在第 0 行中间：不跳
        assertEquals(5, FollowLimits.skipShort(5, 3, ::lineOf, { starts[it] }, { ends[it] }))
        // 下一行不短：不跳
        assertEquals(12, FollowLimits.skipShort(12, 3, ::lineOf, { starts[it] }, { ends[it] }))
    }

    @Test fun alignerStaysInWindow() {
        val pinyin = PinyinTable("wo 我\nmen 们\nqu 去\nshan 山\nhai 海\nbian 边\n")
        val dict = HashMap<String, Int>()
        val text = "我们去山边。我们去海边。"
        val script = FollowText.tokenize(text, 0, text.length, pinyin, dict)
        val hyp = FollowText.tokenize("我们去海边", 0, 5, pinyin, dict)
        val a = FollowAligner().apply { offset = 0; locked = true }
        // 只许在第一句（前 5 个音节）里找：后面的「海边」不能跳过去
        val r = a.update(hyp.ids, script, 0, 0, 5)
        assertFalse(r.to > 6)
        // 不限范围时会跳到后面的「海边」
        val b = FollowAligner().apply { offset = 0; locked = true }
        assertEquals(text.indexOf("海边") + 2, b.update(hyp.ids, script, 0).to)
    }
}
