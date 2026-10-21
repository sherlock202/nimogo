package io.github.yingdu

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptRemoteTest {
    @Test fun keys() {
        assertEquals(-1, PromptRemote.key(KeyEvent.KEYCODE_DPAD_UP)!!.lines)
        assertEquals(1, PromptRemote.key(KeyEvent.KEYCODE_DPAD_DOWN)!!.lines)
        assertEquals(-1, PromptRemote.key(KeyEvent.KEYCODE_DPAD_LEFT)!!.pages)
        assertEquals(1, PromptRemote.key(KeyEvent.KEYCODE_DPAD_RIGHT)!!.pages)
        assertEquals(1, PromptRemote.key(KeyEvent.KEYCODE_PAGE_DOWN)!!.pages)
        assertEquals(1, PromptRemote.key(KeyEvent.KEYCODE_MEDIA_NEXT)!!.pages)
        assertNull(PromptRemote.key(KeyEvent.KEYCODE_A))
        assertEquals(-1, PromptRemote.volume(true, 2).lines)
        assertEquals(1, PromptRemote.volume(false, 1).pages)
    }

    @Test fun swipes() {
        assertEquals(1, PromptRemote.swipe(5f, -300f, 100f)!!.lines)      // 往上滑：下一行
        assertEquals(-1, PromptRemote.swipe(0f, 300f, 100f)!!.lines)
        assertEquals(1, PromptRemote.swipe(-300f, 20f, 100f)!!.pages)     // 往左滑：下一页
        assertEquals(-1, PromptRemote.swipe(300f, 20f, 100f)!!.pages)
        assertNull(PromptRemote.swipe(30f, 30f, 100f))                    // 点一下
        assertEquals(350L, PromptRemote.repeatGap(PromptRemote.Move(0, 1, "")))
    }
}
