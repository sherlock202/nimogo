package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavVoiceTest {
    @Test fun stripsHowYouSayIt() {
        assertEquals("东京站", NavVoice.query("我要去东京站"))
        assertEquals("东京站", NavVoice.query("导航到东京站"))
        assertEquals("上野公园", NavVoice.query("请带我去上野公园"))
        assertEquals("东京站", NavVoice.query("去 东京站。"))
        assertEquals("四谷", NavVoice.query("四谷"))
    }

    @Test fun numberPicksOnlyTheDigit() {
        assertEquals(1, NavVoice.pick("一", 4))
        assertEquals(2, NavVoice.pick("第二个", 4))
        assertEquals(4, NavVoice.pick("4", 4))
        assertNull(NavVoice.pick("四", 3))
        assertNull(NavVoice.pick("四谷", 4))
        assertNull(NavVoice.pick("东京站", 4))
        assertNull(NavVoice.pick("一", 0))
    }

    @Test fun atMostFourUniquePlaces() {
        fun c(name: String, id: String? = name) = NavChoice(name, "", null, NavDest(name, placeId = id))
        val list = NavVoice.top(listOf(c("东京站", "a"), c("东京站", "a"), c("八重洲", "b"), c("丸之内", "c"), c("日本桥", "d"), c("有乐町", "e")))
        assertEquals(listOf("东京站", "八重洲", "丸之内", "日本桥"), list.map { it.name })
    }
}
