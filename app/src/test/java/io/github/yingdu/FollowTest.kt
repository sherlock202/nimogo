package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowTest {
    private val table by lazy {
        val f = listOf("src/main/assets/follow/pinyin.txt", "app/src/main/assets/follow/pinyin.txt").map { java.io.File(it) }.first { it.isFile }
        PinyinTable(f.readText())
    }
    private val script = "各位好，今天我想和大家聊一聊東京的交通。首先是電車，東京的電車非常準時。" +
        "其次是公交车，公交车的路线很多。最后说一说出租车，出租车比较贵，但是很方便。谢谢大家。"

    private fun follow(a: FollowAligner, dict: MutableMap<String, Int>, said: String): FollowAligner.Result {
        val s = FollowText.tokenize(script, 0, script.length, table, dict)
        var cur = 0
        while (cur < s.size && s.starts[cur] < a.offset) cur++
        val h = FollowText.tokenize(said, 0, said.length, table, dict)
        return a.update(h.ids, s, cur)
    }

    @Test fun pinyinTable() {
        assertTrue(table.size > 20_000)
        assertEquals("dong", table.of('東')); assertEquals("dong", table.of('东'))
        assertEquals("s", FollowText.fuzzy("sh").take(1))
        assertEquals(FollowText.fuzzy("zhang"), FollowText.fuzzy("zan"))
        assertEquals(FollowText.fuzzy("ning"), FollowText.fuzzy("lin"))
    }

    @Test fun followsSimplifiedSpeechOnTraditionalScript() {
        val a = FollowAligner(); val dict = HashMap<String, Int>()
        assertTrue(follow(a, dict, "各位好").moved)
        assertEquals(script.indexOf("好") + 1, a.offset)
        // 简体、同音错字（在→再 这类）：「今天我想和大家聊一聊东京的交通」
        val r = follow(a, dict, "各位好今天我想和大家聊一聊东京的交通")
        assertTrue(r.moved)
        assertEquals(script.indexOf("交通") + 2, a.offset)
        follow(a, dict, "首先是电车东京的电车飞常准时")            // 「非」识别成「飞」
        assertEquals(script.indexOf("準時") + 2, a.offset)
    }

    @Test fun skipsAheadButNeedsEnoughEvidence() {
        val a = FollowAligner(); val dict = HashMap<String, Int>()
        follow(a, dict, "各位好今天我想和大家")
        val at = a.offset
        // 只说了两个字「出租」：太少，不跳
        assertFalse(follow(a, dict, "出租").moved)
        assertEquals(at, a.offset)
        // 跳过中间几句，直接念「最后说一说出租车」：跳过去
        val r = follow(a, dict, "最后说一说出租车")
        assertTrue(r.moved); assertEquals("跳过", r.reason)
        assertEquals(script.indexOf("出租车") + 3, a.offset)
    }

    @Test fun ignoresOffScriptTalk() {
        val a = FollowAligner(); val dict = HashMap<String, Int>()
        follow(a, dict, "各位好今天我想和大家聊一聊")
        val at = a.offset
        assertFalse(follow(a, dict, "不好意思麦克风有点问题").moved)
        assertEquals(at, a.offset)
    }

    @Test fun viewRow() {
        // 5 行一屏，让第 20 行出现在倒数第二行：顶部是第 17 行
        assertEquals(17, FollowView.topFor(20, 5, 2, 100))
        assertEquals(0, FollowView.topFor(1, 5, 2, 100))
        assertEquals(100, FollowView.topFor(300, 5, 2, 100))
        assertEquals(16, FollowView.topFor(20, 5, 1, 100))
    }

    @Test fun wavRoundTrip() {
        val pcm = ShortArray(1000) { ((it * 37) % 65536 - 32768).toShort() }
        val back = WavPcm.read(OggOpus.wav(pcm))
        assertEquals(pcm.size, back.size)
        assertTrue(pcm.contentEquals(back))
        assertEquals(0, WavPcm.read(ByteArray(10)).size)
    }

    @Test fun offScriptSentenceEndingWithCommonWords() {
        val script2 = "这个观点的代表人物，就是亚流。所以今天整堂课，重点就围绕一个问题。"
        val dict = HashMap<String, Int>()
        val a = FollowAligner()
        val s = FollowText.tokenize(script2, 0, script2.length, table, dict)
        a.offset = script2.indexOf("人物") + 2
        var cur = 0
        while (cur < s.size && s.starts[cur] < a.offset) cur++
        val said = "还搞那个蓝牙戒指是可以翻页是其他的不是他就是"
        val h = FollowText.tokenize(said, 0, said.length, table, dict)
        val r = a.update(h.ids, s, cur)
        assertFalse(r.reason, r.moved)
        // 真的往下念：「就是亚流所以今天」
        val h2 = FollowText.tokenize("就是亚流所以今天", 0, 8, table, dict)
        assertTrue(a.update(h2.ids, s, cur).moved)
    }

    @Test fun findsStartAnywhereAndAcceptsShortJumpsAndRereads() {
        // 2026-10-21 第二份日志：从眼镜第 10 行开始，人却在念后面的「重点就围绕一个问题」（漏了「个」）
        val script3 = "有人说耶稣很伟大是上帝的儿子但不是和父同等的神。这个观点的代表人物就是亚流。所以今天整堂课重点就围绕一个问题：" +
            "如果耶稣不是完全的真神，那么基督教的救恩还成立吗？对早期教会来说，承认耶稣是真神已经非常自然。"
        val dict = HashMap<String, Int>()
        val s = FollowText.tokenize(script3, 0, script3.length, table, dict)
        fun go(a: FollowAligner, said: String): FollowAligner.Result {
            var cur = 0
            while (cur < s.size && s.starts[cur] < a.offset) cur++
            return a.update(FollowText.tokenize(said, 0, said.length, table, dict).ids, s, cur)
        }
        val a = FollowAligner()
        val r1 = go(a, "围绕一问题")
        assertTrue(r1.reason, r1.moved); assertEquals("找到位置", r1.reason)
        assertEquals(script3.indexOf("问题") + 2, a.offset)
        // 「还成立吗」识别成「还沉立吗」：前后鼻音
        assertTrue(go(a, "这还沉立吗").moved)
        assertEquals(script3.indexOf("立吗") + 2, a.offset)
        // 回头重念前面一句（往回）
        val back = FollowAligner().apply { offset = script3.indexOf("所以"); locked = true }
        val rb = go(back, "已经非常自然")
        assertTrue(rb.reason, rb.moved)
    }

    @Test fun shortTitleLineAtStart() {
        // 讲稿第一行是两个字的标题：还没跟上时，就在起点附近的也是对上 2 个字就跟
        val script4 = "黑羊\n作词：某某\n红绿灯两种颜色吗\n中间黄灯难道它就没有存在感吗"
        val dict = HashMap<String, Int>()
        val s = FollowText.tokenize(script4, 0, script4.length, table, dict)
        val a = FollowAligner()
        val r = a.update(FollowText.tokenize("黑羊", 0, 2, table, dict).ids, s, 0)
        assertTrue(r.reason, r.moved)
        assertEquals(2, a.offset)
        // 远处两个字（「中间」）还不够：要 3 个字
        val b = FollowAligner()
        assertFalse(b.update(FollowText.tokenize("黄灯", 0, 2, table, dict).ids, s, 0).moved)
    }

    @Test fun kanaAndJapaneseNames() {
        // 讲稿「编曲：ナスカ」「黒い羊」：识别成平假名、片假名、或者中文音译都能对上
        val script5 = "黒い羊 中文版\n编曲：ナスカ\n原唱：欅坂46\n红绿灯两种颜色吗"
        val dict = HashMap<String, Int>()
        val s = FollowText.tokenize(script5, 0, script5.length, table, dict)
        fun at(said: String): FollowAligner.Result {
            val a = FollowAligner()
            return a.update(FollowText.tokenize(said, 0, said.length, table, dict).ids, s, 0).also { if (it.moved) assertTrue(a.offset > 0) }
        }
        assertEquals("na", FollowText.kana('ナ')); assertEquals("na", FollowText.kana('な')); assertEquals(null, FollowText.kana('ー'))
        assertTrue(at("黒い羊").moved)
        assertTrue(at("编曲なすか").moved)
        assertTrue(at("编曲ナスカ").moved)
        assertTrue(at("原唱榉坂").moved)
    }

    @Test fun glyphFallback() {
        val f = java.io.File(listOf("src/main/assets/follow/simplify.txt", "app/src/main/assets/follow/simplify.txt").first { java.io.File(it).isFile })
        val g = GlyphFallback(f.readText())
        assertTrue(g.size > 2000)
        assertEquals("原唱：榉坂46 东京驿", g.apply("原唱：欅坂46 東京駅"))
        assertEquals("红绿灯", g.apply("红绿灯"))
    }
}
