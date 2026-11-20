package io.github.yingdu

import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** 一张学习卡：正面、反面。 */
data class StudyCard(val front: String, val back: String)

/** 一组学习卡。 */
data class StudyDeck(val id: String, val name: String, val cards: List<StudyCard>)

/** 导入结果。id 为空表示没导成。 */
data class CardImport(val id: String?, val message: String)

/**
 * CSV 和本地保存。没有表头，第一行就是卡片。
 * 一行里第一项是正面，第二项是反面。逗号可以用英文逗号、中文逗号或制表符。
 * 项可以用英文引号或中文引号包起来，引号里面可以有逗号。
 */
object CardStore {
    private const val MAX_FIELD = 500

    fun parse(text: String): List<StudyCard> {
        val out = ArrayList<StudyCard>()
        for (raw in text.removePrefix("\uFEFF").split('\n', '\r')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val fields = if (line.any { isQuote(it) }) quoted(line) else plain(line)
            val front = fields.getOrElse(0) { "" }.trim().take(MAX_FIELD)
            val back = fields.getOrElse(1) { "" }.trim().take(MAX_FIELD)
            if (front.isEmpty() && back.isEmpty()) continue
            out.add(StudyCard(front, back))
        }
        return out
    }

    /** 按提词器一屏的字数、行数折行。放不下时最后一行用省略号。 */
    fun fit(text: String, cols: Int, rows: Int): String {
        val w = cols.coerceAtLeast(1)
        val h = rows.coerceAtLeast(1)
        val lines = ArrayList<String>()
        for (para in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (para.isEmpty()) { lines.add(""); continue }
            var i = 0
            while (i < para.length) {
                val end = (i + w).coerceAtMost(para.length)
                lines.add(para.substring(i, end))
                i = end
            }
        }
        if (lines.isEmpty()) return ""
        if (lines.size <= h) return lines.joinToString("\n")
        val keep = lines.take(h).toMutableList()
        val last = keep[h - 1]
        keep[h - 1] = if (last.length >= w) last.dropLast(1) + "…" else "$last…"
        return keep.joinToString("\n")
    }

    fun encode(decks: List<StudyDeck>): String {
        val arr = JSONArray()
        for (d in decks) {
            val cards = JSONArray()
            for (c in d.cards) cards.put(JSONObject().put("f", c.front).put("b", c.back))
            arr.put(JSONObject().put("id", d.id).put("name", d.name).put("cards", cards))
        }
        return arr.toString()
    }

    fun decode(json: String): List<StudyDeck> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<StudyDeck>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cardsArr = o.optJSONArray("cards") ?: JSONArray()
            val cards = ArrayList<StudyCard>()
            for (j in 0 until cardsArr.length()) {
                val c = cardsArr.optJSONObject(j) ?: continue
                cards.add(StudyCard(c.optString("f"), c.optString("b")))
            }
            val id = o.optString("id")
            if (id.isEmpty()) continue
            out.add(StudyDeck(id, o.optString("name").ifEmpty { "未命名" }, cards))
        }
        return out
    }

    private fun isQuote(c: Char) = c == '"' || c == '“' || c == '”' || c == '＂'

    private fun isSep(c: Char) = c == ',' || c == '，' || c == '\t'

    private fun plain(line: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in line) {
            if (isSep(ch)) { parts.add(sb.toString()); sb.clear() } else sb.append(ch)
        }
        parts.add(sb.toString())
        return parts
    }

    private fun quoted(line: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (q) {
                if (isQuote(ch)) {
                    val next = line.getOrNull(i + 1)
                    if (next != null && isQuote(next)) { sb.append('"'); i += 2; continue }
                    q = false
                } else sb.append(ch)
            } else when {
                isQuote(ch) -> q = true
                isSep(ch) -> { parts.add(sb.toString()); sb.clear() }
                ch.isWhitespace() && sb.isEmpty() -> {}
                else -> sb.append(ch)
            }
            i++
        }
        parts.add(sb.toString())
        return parts
    }
}

/**
 * 眼镜上播放一组学习卡。
 * 自动：先停一面、再停另一面，然后下一张，循环。默认先正面。倒置时先反面。
 * 镜腿左键翻面，右键下一张。蓝牙戒指上下翻面，左上一张，右下一张。
 * 画面自己画：和正反面同一行，左边是组名，中间是第几张。
 */
class CardsApp(
    private val host: AppHost,
    private val decks: () -> List<StudyDeck>,
    private val frontSec: () -> Int,
    private val backSec: () -> Int,
    private val autoPref: () -> Boolean,
    private val invertPref: () -> Boolean,
    /** 每组上次播到第几张（下次从这张开始）。 */
    private val loadPos: (String) -> Int,
    private val savePos: (String, Int) -> Unit,
    private val keys: (Boolean) -> Unit,
) : GlassesApp {
    override val title = "学习卡"
    var deckId = ""
    var index = 0
    var back = false
    var auto = true
    /** 倒置：一张卡先出现反面，再出现正面。 */
    var invert = false
    var playing = false
        private set

    private var lastAct = ""
    private var lastActAt = 0L
    private val tick = Runnable { advance() }

    fun prepare(id: String): String? {
        val d = decks().find { it.id == id } ?: return "找不到这组"
        if (d.cards.isEmpty()) return "这组还没有卡片"
        deckId = id
        index = loadPos(id).let { if (it in d.cards.indices) it else 0 }
        back = false
        return null
    }

    override fun onOpen() {
        auto = autoPref()
        invert = invertPref()
        back = invert
        playing = true
        waitLogged = false
        keys(true)
        host.log("学习卡：${if (auto) "自动" else "手动"}播放，从第 ${index + 1} 张开始")
        showThenArm()
    }

    override fun onClose() {
        playing = false
        hold(false)
    }

    /** 眼镜上退出了页面，卡片还在，先停掉计时和按键。恢复显示时再接着放。 */
    fun onHide() { hold(false) }

    override fun onResume() {
        if (!playing) return
        hold(true)
        showThenArm()
    }

    private fun hold(on: Boolean) {
        if (!on) {
            host.main.removeCallbacks(tick)
            armToken++
        }
        keys(on)
    }

    /** 卡片改过之后，正在播放的话重新画。 */
    fun refreshIfShowing() {
        if (!playing) return
        val n = deck()?.cards?.size ?: 0
        if (n == 0) index = 0 else if (index >= n) { index = 0; savePos(deckId, index) }
        showThenArm()
    }

    fun reschedule() { if (playing) arm() }

    /** 播放中改倒置：当前这张立刻从新的第一面重新开始。 */
    fun applyInvert(on: Boolean) {
        invert = on
        if (!playing) return
        back = on
        showThenArm()
        host.changed()
    }

    fun act(action: String) {
        val now = SystemClock.uptimeMillis()
        if (action == lastAct && now - lastActAt < 80) return
        lastAct = action
        lastActAt = now
        val n = deck()?.cards?.size ?: return
        if (n == 0) return
        when (action) {
            "flip" -> back = !back
            "next" -> { index = (index + 1) % n; back = invert; savePos(deckId, index) }
            "prev" -> { index = (index - 1 + n) % n; back = invert; savePos(deckId, index) }
            else -> return
        }
        showThenArm()
        host.changed()
    }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_LEFT -> act("flip")
            GlassesInput.CLICK_RIGHT -> act("next")
            else -> {}
        }
    }

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val d = deck()
        val card = d?.cards?.getOrNull(index)
        val small = f.small
        val side = if (card == null) "" else if (back) "反面" else "正面"
        val prog = if (d == null || card == null) "" else "${index + 1}/${d.cards.size}"
        val name = d?.name.orEmpty()
        if (side.isNotEmpty()) f.text(side, f.w - 6, 6, Frame.DIM, small, Frame.RIGHT)
        if (prog.isNotEmpty()) f.text(prog, f.w / 2, 6, Frame.FULL, small, Frame.CENTER)
        if (name.isNotEmpty()) {
            val rightEdge = when {
                prog.isNotEmpty() -> f.w / 2 - small.measure(prog) / 2 - 8
                side.isNotEmpty() -> f.w - 6 - small.measure(side) - 8
                else -> f.w - 6
            }
            f.text(small.ellipsize(name, (rightEdge - 6).coerceAtLeast(0)), 6, 6, Frame.DIM, small)
        }
        f.dotted(26)
        val body = when {
            d == null -> "学习卡"
            card == null -> "这组没有卡片"
            else -> (if (back) card.back else card.front).ifBlank { "（空）" }
        }
        val maxW = f.w - 12
        val lineH = f.font.height + 6
        val y0 = 32
        val maxLines = ((f.h - y0 - 4) / lineH).coerceAtLeast(1)
        val wrapped = f.font.wrap(body, maxW).let { if (it.isEmpty()) listOf(" ") else it }
        val shown = wrapped.take(maxLines).toMutableList()
        if (wrapped.size > maxLines && shown.isNotEmpty()) {
            val t = shown.last()
            val marked = if (t.isEmpty()) "…" else t.dropLast(1) + "…"
            shown[shown.lastIndex] = f.font.ellipsize(marked, maxW)
        }
        val block = shown.size * lineH
        val top = y0 + ((f.h - 4 - y0 - block) / 2).coerceAtLeast(0)
        shown.forEachIndexed { i, line -> f.text(line, 6, top + i * lineH) }
        return f.done()
    }

    /** 自己画一帧。返回 null，不走提词器文字页。 */
    override fun glassesText(cols: Int, rows: Int): Pair<String, String>? = null

    override fun status(): String {
        val d = deck() ?: return "学习卡"
        val n = d.cards.size
        if (n == 0) return "${d.name} · 没有卡片"
        val side = if (back) "反面" else "正面"
        val mode = if (auto) "自动" else "手动"
        val order = if (invert) " · 倒置" else ""
        return "第 ${index + 1}/$n 张 · $side · $mode$order"
    }

    private fun deck(): StudyDeck? = decks().find { it.id == deckId }

    /** 作废还没开始的计时（画面还在路上，或者已经收起）。 */
    private var armToken = 0

    private fun show() { host.redraw(this) }

    /** 先等这一帧到眼镜上，再开始数这一面的秒数。语音菜单要先换页面，换页面的时间不算进去。 */
    private fun showThenArm() {
        host.main.removeCallbacks(tick)
        if (!playing || !auto || deck()?.cards.isNullOrEmpty()) { armToken++; show(); return }
        val token = ++armToken
        val sec = (if (back) backSec() else frontSec()).coerceIn(1, 60)
        host.afterShown {
            if (token != armToken || !playing || !auto) return@afterShown
            host.main.postDelayed(tick, sec * 1000L)
        }
        show()
    }

    /** 这一面已经在眼镜上（只改了秒数）：从现在重新数。 */
    private fun arm() {
        host.main.removeCallbacks(tick)
        armToken++
        if (!playing || !auto) return
        if (deck()?.cards.isNullOrEmpty()) return
        val sec = (if (back) backSec() else frontSec()).coerceIn(1, 60)
        host.main.postDelayed(tick, sec * 1000L)
    }

    /** 自动播放时暂时不在眼镜上（切页面、收起）这件事已经记过一次。 */
    private var waitLogged = false

    private fun advance() {
        if (!playing || !auto) return
        if (!host.isShowing(this)) {
            // 不能就此停掉：从语音菜单切过来、眼镜刚换页面时这里会碰上一下，停掉后就一直不翻了，看起来像手动播放
            if (!waitLogged) { waitLogged = true; host.log("学习卡：现在没在眼镜上显示，等显示出来再接着自动播放") }
            host.main.postDelayed(tick, 1_000)
            return
        }
        waitLogged = false
        val n = deck()?.cards?.size ?: return
        if (n == 0) return
        val step = nextSide(index, back, n, invert)
        if (step.first != index) savePos(deckId, step.first)
        index = step.first
        back = step.second
        showThenArm()
        host.changed()
    }

    companion object {
        /** 蓝牙戒指：上下翻面，左上一张，右下一张。音量键也算上下。 */
        fun keyAction(code: Int): String? = when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> "flip"
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> "prev"
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "next"
            else -> null
        }

        /**
         * 自动播放的下一步。[firstBack] 为真时先反面、再正面。
         * 返回下一张的序号，以及下一张要显示反面。
         */
        fun nextSide(index: Int, showingBack: Boolean, count: Int, firstBack: Boolean): Pair<Int, Boolean> {
            if (count <= 0) return index to showingBack
            return if (showingBack == firstBack) index to !firstBack
            else (index + 1) % count to firstBack
        }

        fun actionName(action: String) = when (action) {
            "flip" -> "翻面"
            "prev" -> "上一张"
            "next" -> "下一张"
            else -> action
        }
    }
}
