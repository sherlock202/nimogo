package io.github.yingdu

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsTest {
    @Test fun csvWithoutHeader() {
        val cards = CardStore.parse("apple,苹果\nbook,书")
        assertEquals(2, cards.size)
        assertEquals("apple", cards[0].front)
        assertEquals("苹果", cards[0].back)
        assertEquals("book", cards[1].front)
    }

    @Test fun chineseCommaAndQuotes() {
        val a = CardStore.parse("apple，苹果")
        assertEquals("apple", a[0].front)
        assertEquals("苹果", a[0].back)
        val b = CardStore.parse("\"New York\",\"纽约\"")
        assertEquals("New York", b[0].front)
        assertEquals("纽约", b[0].back)
        val c = CardStore.parse("“hello, world”，“你好”")
        assertEquals("hello, world", c[0].front)
        assertEquals("你好", c[0].back)
        val d = CardStore.parse("\"say \"\"hi\"\"\",\"打招呼\"")
        assertEquals("say \"hi\"", d[0].front)
        assertEquals("打招呼", d[0].back)
    }

    @Test fun skipsBlankAndKeepsOneField() {
        val cards = CardStore.parse("\n\napple,苹果\n\nonly\n  ,  \n")
        assertEquals(listOf("apple", "only"), cards.map { it.front })
        assertEquals("", cards[1].back)
    }

    @Test fun firstLineIsContentNotHeader() {
        val cards = CardStore.parse("正面,反面\naaa，bbb")
        assertEquals("正面", cards[0].front)
        assertEquals("反面", cards[0].back)
        assertEquals("aaa", cards[1].front)
    }

    @Test fun roundTrip() {
        val decks = listOf(StudyDeck("c1", "英语", listOf(StudyCard("cat", "猫"), StudyCard("dog", "狗"))))
        val back = CardStore.decode(CardStore.encode(decks))
        assertEquals(decks, back)
        assertTrue(CardStore.decode("").isEmpty())
        assertTrue(CardStore.decode("{").isEmpty())
    }

    @Test fun fitClips() {
        assertEquals("abcd\nef", CardStore.fit("abcdef", 4, 3))
        assertEquals("abcd\nefg…", CardStore.fit("abcdefghi", 4, 2))
    }

    @Test fun autoOrderFrontThenBack() {
        assertEquals(0 to true, CardsApp.nextSide(0, false, 2, false))
        assertEquals(1 to false, CardsApp.nextSide(0, true, 2, false))
        assertEquals(0 to false, CardsApp.nextSide(1, true, 2, false))
    }

    @Test fun autoOrderInvertedBackThenFront() {
        assertEquals(0 to false, CardsApp.nextSide(0, true, 2, true))
        assertEquals(1 to true, CardsApp.nextSide(0, false, 2, true))
        assertEquals(0 to true, CardsApp.nextSide(1, false, 2, true))
    }

    @Test fun ringKeys() {
        assertEquals("flip", CardsApp.keyAction(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("flip", CardsApp.keyAction(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("flip", CardsApp.keyAction(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals("flip", CardsApp.keyAction(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals("prev", CardsApp.keyAction(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("next", CardsApp.keyAction(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("prev", CardsApp.keyAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals("next", CardsApp.keyAction(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(null, CardsApp.keyAction(KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
