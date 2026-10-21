package io.github.yingdu

import android.view.KeyEvent

/**
 * 提词遥控：蓝牙戒指、方向键、翻页笔、音量键 → 翻行 / 翻页。纯逻辑，方便单元测试。
 * 上下翻行，左右翻页（和用户约定的一样）。戒指有两种：发按键的，和假装手指在屏幕上滑的；两种都认。
 */
object PromptRemote {
    /** 一次操作：往后几行、往后几页（负数往前）。 */
    data class Move(val lines: Int, val pages: Int, val label: String)

    /** 按键 → 操作；不认识的键返回 null（照常交给系统）。音量键另外处理（看「音量键翻页」的设置）。 */
    fun key(code: Int): Move? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Move(-1, 0, "↑ 上一行")
        KeyEvent.KEYCODE_DPAD_DOWN -> Move(1, 0, "↓ 下一行")
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND ->
            Move(0, -1, "← 上一页")
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_SPACE -> Move(0, 1, "→ 下一页")
        else -> null
    }

    /** 音量键：mode 是「音量键翻页」的设置（1 翻页，其他按翻行）。 */
    fun volume(up: Boolean, mode: Int): Move =
        if (mode == 1) (if (up) Move(0, -1, "音量 + 上一页") else Move(0, 1, "音量 − 下一页"))
        else (if (up) Move(-1, 0, "音量 + 上一行") else Move(1, 0, "音量 − 下一行"))

    /**
     * 滑动 → 操作（像刷短视频：手指往上滑是下一个）。dx、dy 是手指移动的像素，min 是算作滑动的最小距离。
     * 往上滑下一行、往下滑上一行；往左滑下一页、往右滑上一页。
     */
    fun swipe(dx: Float, dy: Float, min: Float): Move? {
        val ax = kotlin.math.abs(dx); val ay = kotlin.math.abs(dy)
        if (maxOf(ax, ay) < min) return null
        return if (ay >= ax) (if (dy < 0) Move(1, 0, "上滑 下一行") else Move(-1, 0, "下滑 上一行"))
        else (if (dx < 0) Move(0, 1, "左滑 下一页") else Move(0, -1, "右滑 上一页"))
    }

    /** 按住连发时多久翻一次（毫秒）：翻页慢一点，翻行快一点。 */
    fun repeatGap(m: Move) = if (m.pages != 0) 350L else 150L
}
