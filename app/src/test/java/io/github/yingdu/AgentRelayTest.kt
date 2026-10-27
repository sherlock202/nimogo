package io.github.yingdu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRelayTest {
    @Test fun parsesRelayMessage() {
        assertEquals(4 to "日经指数基金已加入关注\n详细……", AgentInbox.parseRelay("NimoGo #4：日经指数基金已加入关注\n详细……"))
        assertEquals(12 to "完成", AgentInbox.parseRelay("NimoGo #12: 完成"))
        assertEquals(3 to "需要确认：每周五 14:30 检查？", AgentInbox.parseRelay("Muse\nNimoGo # 3 ：需要确认：每周五 14:30 检查？"))
        assertNull(AgentInbox.parseRelay("今天的新闻摘要"))
        assertNull(AgentInbox.parseRelay("NimoGo #4："))          // 没有内容
        assertNull(AgentInbox.parseRelay(null, ""))
    }

    @Test fun takesLongestOfNotificationTexts() {
        // 通知的短正文常被截断，展开的长文是全的
        val r = AgentInbox.parseRelay("NimoGo #4：日经…", "NimoGo #4：日经指数基金已加入关注，周五检查", "Muse")
        assertEquals(4 to "日经指数基金已加入关注，周五检查", r)
    }

    @Test fun relayBodyIsAgentReplyNotMine() {
        val b = AgentInbox.relayBody("需要确认：每周五检查？\n详细")
        assertTrue(b.startsWith(AgentInbox.RELAY))
        assertFalse(b.trimStart().startsWith(AgentInbox.MINE))      // 哨兵、查评论都不会当成我的回复
        assertTrue(AgentInbox.needsConfirm(b))
        assertEquals("需要确认：每周五检查？", AgentInbox.summary(b))
        val json = "[{\"id\":1,\"body\":${JSONObject.quote(b)}},{\"id\":2,\"body\":${JSONObject.quote(AgentInbox.replyBody("确认"))}}]"
        assertEquals(listOf(1L), AgentInbox.parseComments(json).map { it.id })
    }

    @Test fun relayKeyDedups() {
        assertEquals(AgentInbox.relayKey(4, "完成 "), AgentInbox.relayKey(4, "完成"))
        assertTrue(AgentInbox.relayKey(4, "完成") != AgentInbox.relayKey(5, "完成"))
        assertTrue(AgentInbox.relayKey(4, "完成") != AgentInbox.relayKey(4, "失败"))
    }

    @Test fun replyAppendedToBody() {
        val b = AgentInbox.bodyWithReply("<!-- nimogo id=c1 at=x -->\n命令\n\n（说明）\n", "2026-10-27T10:00:00+09:00", "确认")
        assertEquals("<!-- nimogo id=c1 at=x -->\n命令\n\n（说明）\n\n<!-- nimogo reply at=2026-10-27T10:00:00+09:00 -->\n回复：确认", b)
    }

    @Test fun commandKeepsRelayKeys() {
        val c = AgentCommand(4, "命令", 1L).apply { museKeys.add("4:abc") }
        assertEquals(listOf("4:abc"), AgentCommand.fromJson(JSONObject(c.toJson().toString())).museKeys)
        assertTrue(AgentCommand.fromJson(JSONObject("{\"n\":1}")).museKeys.isEmpty())
    }
}
