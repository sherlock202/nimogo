package io.github.yingdu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInboxTest {
    @Test fun repoAndTitle() {
        assertTrue(AgentInbox.validRepo("sherlock202/muse-inbox"))
        assertFalse(AgentInbox.validRepo("muse-inbox"))
        assertFalse(AgentInbox.validRepo("a/b/c"))
        assertEquals("帮我查一下明天的天气", AgentInbox.title("帮我查一下明天的天气\n"))
        assertEquals(41, AgentInbox.title("字".repeat(50)).length)
    }

    @Test fun bodies() {
        val b = AgentInbox.issueBody("c1", "2026-10-26T10:00:00+09:00", "订周五的餐厅")
        assertTrue(b.startsWith("<!-- nimogo id=c1 at=2026-10-26T10:00:00+09:00 -->\n订周五的餐厅"))
        assertTrue(AgentInbox.replyBody("确认").startsWith(AgentInbox.MINE))
    }

    @Test fun commentsSkipMine() {
        val json = """[{"id":1,"body":"已订好：周五 19:00 两位\n详细……"},{"id":2,"body":"<!-- nimogo -->\n确认"},{"id":3,"body":"## 需要确认：要付定金 3000 日元吗？"}]"""
        val cs = AgentInbox.parseComments(json)
        assertEquals(listOf(1L, 3L), cs.map { it.id })
        assertEquals("已订好：周五 19:00 两位", AgentInbox.summary(cs[0].body))
        assertEquals("需要确认：要付定金 3000 日元吗？", AgentInbox.summary(cs[1].body))
        assertTrue(AgentInbox.needsConfirm(cs[1].body))
        assertFalse(AgentInbox.needsConfirm(cs[0].body))
    }

    @Test fun commandRoundTrip() {
        val c = AgentCommand(12, "订餐厅", 1000L).apply { lastCommentId = 5; open = false; result = "订好了"; log.add("我：订餐厅") }
        val r = AgentCommand.listFromJson(AgentCommand.listToJson(listOf(c)))[0]
        assertEquals(12, r.number); assertEquals(5L, r.lastCommentId); assertFalse(r.open); assertEquals("已完成", r.status)
        assertEquals(listOf("我：订餐厅"), r.log)
        assertEquals("等你确认", AgentCommand(1, "x", 0).apply { confirm = "需要确认：……" }.status)
        assertTrue(AgentCommand.listFromJson("坏的").isEmpty())
    }

    @Test fun guideMentionsRepoAndLabel() {
        val g = AgentInbox.guide("me/inbox", "nimogo", "Muse")
        assertTrue(g.contains("me/inbox")); assertTrue(g.contains("「nimogo」")); assertTrue(g.contains("需要确认："))
    }

    @Test fun wrap() {
        assertEquals(listOf("一二三四", "五"), AgentApp.wrapCols("一二三四五", 4))
    }
}
