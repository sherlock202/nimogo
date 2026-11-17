package io.github.yingdu

/**
 * 同一次按键有时会报两遍（输入上报一次，页面再转发一次；或者按下/松开合成的单击和眼镜自己报的单击）。
 * 重复的那一遍如果也交给番茄钟、锻炼，暂停会被马上按回去，看起来像没反应。
 *
 * 规则：同一种手势（同一个代码）在 90ms 以内再来一次，当作重复丢掉。
 * 人连着点的第二下隔得更开，要留下来，主界面上才能分出单击和双击。
 */
internal class TempleClicks {
    private var lastCode = 0
    private var lastAt = 0L

    /** 这一下要不要交给应用。要的话返回 true 并记下来；重复的返回 false。 */
    fun tryDeliver(now: Long, code: Int): Boolean {
        if (code == lastCode && now - lastAt < REPEAT_MS) return false
        lastCode = code
        lastAt = now
        return true
    }

    private companion object {
        const val REPEAT_MS = 90L
    }
}
