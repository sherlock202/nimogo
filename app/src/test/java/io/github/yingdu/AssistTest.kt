package io.github.yingdu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistTest {
    private fun line(me: Boolean?, t: String) = AssistLine(0L, me, t)

    @Test fun transcriptTagsAndTrims() {
        val ls = listOf(line(true, "你好"), line(false, "你好，我是小王"), line(null, "嗯"), line(false, " "))
        assertEquals("我：你好\n对方：你好，我是小王\n（分不清）：嗯", AssistAi.transcript(ls))
        // 太长时只留最后的几句（整句）
        assertEquals("（分不清）：嗯", AssistAi.transcript(ls, maxChars = 10))
        // 一句都放不下时至少留最后一句
        assertEquals("（分不清）：嗯", AssistAi.transcript(ls, maxChars = 1))
    }

    @Test fun livePrompts() {
        val sc = AssistScene.DEFAULTS[1]   // 面试，日语
        val u = AssistAi.liveUser("answer", sc, "对方：自己紹介をお願いします")
        assertTrue(u.contains("日语")); assertTrue(u.contains("自己紹介")); assertTrue(u.contains("参考回答"))
        assertTrue(AssistAi.liveUser("question", sc, "").contains("还没有听到对话"))
        assertTrue(AssistAi.liveSystem(sc).contains("面试"))
        assertTrue(AssistAi.reviewSystem(sc).contains("是否适合我"))
        assertTrue(AssistAi.reviewSystem(AssistScene.DEFAULTS[0]).contains("不要下「对方在说谎」的结论"))
    }

    @Test fun answerFocusesOnTheOtherSide() {
        // 我照着提问建议问了一句，对方反问我：要回答的是对方的反问，不是我问的那句
        val ls = listOf(line(true, "你平时周末喜欢做什么？"), line(false, "我喜欢爬山。"), line(false, "那你呢，你周末做什么？"))
        assertEquals("我喜欢爬山。 那你呢，你周末做什么？", AssistAi.lastFromOther(ls))
        val u = AssistAi.liveUser("answer", AssistScene.DEFAULTS[0], AssistAi.transcript(ls), AssistAi.lastFromOther(ls))
        assertTrue(u.contains("对方最近说的是：「我喜欢爬山。 那你呢，你周末做什么？」"))
        assertTrue(u.contains("不要去回答对话里我自己问过对方的问题"))
        // 最后几句是我说的：往前找对方说的
        assertEquals("你是做什么工作的？", AssistAi.lastFromOther(listOf(line(false, "你是做什么工作的？"), line(true, "我是做 IT 的"))))
        // 对方一直没说话
        assertEquals("", AssistAi.lastFromOther(listOf(line(true, "你好"))))
        assertTrue(AssistAi.liveUser("answer", AssistScene.DEFAULTS[0], "我：你好", "").contains("最近没听到对方说话"))
    }

    @Test fun requestBodies() {
        val o = AssistAi.body("groq", "openai/gpt-oss-120b", "S", "U", 100)
        assertEquals("low", o.getString("reasoning_effort"))
        assertEquals("system", o.getJSONArray("messages").getJSONObject(0).getString("role"))
        val g = AssistAi.body("gemini", "gemini-flash-latest", "S", "U", 100)
        assertFalse(g.has("reasoning_effort"))
        val c = AssistAi.body("claude", "claude-opus-5", "S", "U", 100)
        assertEquals("S", c.getString("system")); assertEquals(1, c.getJSONArray("messages").length())
    }

    @Test fun plainStripsMarkdown() {
        assertEquals("标题\n1. 你好 世界", AssistAi.plain("## 标题\n1. **你好** 世界\n\n\n"))
    }

    @Test fun scenesAndSessionsRoundTrip() {
        assertEquals(AssistScene.DEFAULTS, AssistScene.listFromJson(null))
        assertEquals(AssistScene.DEFAULTS, AssistScene.listFromJson("坏的"))
        val l = AssistScene.listFromJson(AssistScene.listToJson(AssistScene.DEFAULTS))
        assertEquals(AssistScene.DEFAULTS, l)
        val s = AssistSession("a1", AssistScene.DEFAULTS[0], 1000L).apply {
            end = 2000L; lines.add(AssistLine(1500L, true, "你好")); lines.add(AssistLine(1600L, null, "嗯"))
            asks.add(AssistAsk(1700L, "question", "1. 你平时喜欢做什么？")); review = "一、……"
        }
        val r = AssistSession.fromJson(JSONObject(s.toJson().toString()))
        assertEquals("a1", r.id); assertEquals(2000L, r.end); assertEquals(2, r.lines.size)
        assertEquals(true, r.lines[0].me); assertNull(r.lines[1].me); assertEquals("question", r.asks[0].kind); assertEquals("一、……", r.review)
    }

    private fun app(): AssistApp {
        val host = object : AppHost {
            override val context: android.content.Context get() = throw UnsupportedOperationException()
            override val main: android.os.Handler get() = throw UnsupportedOperationException()
            override val io: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }
            override fun redraw(app: GlassesApp) {}
            override fun changed() {}
            override fun log(msg: String) {}
            override fun isShowing(app: GlassesApp) = true
            override fun frameIntervalMs(): Long? = null
        }
        return AssistApp(host, object : AssistApp.Env {
            override fun scene() = AssistScene.DEFAULTS[0]
            override fun llm() = listOf("groq", "", "", "")
            override fun setMic(on: Boolean) {}
            override fun isMe(v: Speakers.Voice?): Boolean? = null
            override fun save(s: AssistSession) {}
            override fun asrReady() = true
            override fun takeKeys(on: Boolean) {}
        })
    }

    @Test fun wrapLikeReader() {
        val a = app()
        // 4 格一行：中文 1 格，ASCII 半格；句末标点可以挂在行尾
        assertEquals(listOf("你好世界", "abcdefgh", "再见。"), a.wrap("你好世界abcdefgh再见。", 4))
        assertEquals(listOf("一二三四。"), a.wrap("一二三四。", 4))
        assertEquals(listOf("第一段", "第二段"), a.wrap("第一段\n\n第二段", 10))
    }

    @Test fun glassesTextWhenIdle() {
        val (body, status) = app().glassesText(27, 5)
        assertTrue(status.startsWith("相亲"))
        assertTrue(body.contains("右镜腿"))
    }

    @Test fun keys() {
        assertEquals("answer", AssistApp.keyAction(android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("question", AssistApp.keyAction(android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("next", AssistApp.keyAction(android.view.KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals("prev", AssistApp.keyAction(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals("back", AssistApp.keyAction(android.view.KeyEvent.KEYCODE_HEADSETHOOK))
        assertNull(AssistApp.keyAction(android.view.KeyEvent.KEYCODE_A))
    }
}
