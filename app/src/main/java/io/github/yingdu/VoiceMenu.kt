package io.github.yingdu

import android.view.KeyEvent
import io.github.jaredmdobson.concentus.OpusDecoder
import java.util.concurrent.Executors

/**
 * 看板上右双击打开的语音菜单：眼镜上列出能直接启动的功能，念到名字就打开，不用拿手机。
 * 匹配把「打开」这类口头语去掉，再用模糊拼音对同音字（番茄钟 / 翻茄中、2048 / 二零四八）。
 */
class VoiceCommand(val id: String, val label: String, val aliases: List<String>)

object VoiceMenu {
    private val FILLERS = listOf("帮我", "我想", "我要", "请", "打开", "开启", "启动", "开始", "进入")
    private val DIGIT = arrayOf("ling", "yi", "er", "san", "si", "wu", "liu", "qi", "ba", "jiu")

    fun commands(agentName: String, games: List<String>): List<VoiceCommand> {
        val list = mutableListOf(
            VoiceCommand("read", "阅读", listOf("阅读", "看书", "讲稿")),
            VoiceCommand("nav", "导航", listOf("导航")),
            VoiceCommand("pomo", "番茄钟", listOf("番茄钟", "番茄")),
            VoiceCommand("clock", "表盘", listOf("像素表盘", "表盘")),
            VoiceCommand("captions", "字幕", listOf("实时字幕", "字幕")),
            VoiceCommand("assist", "场景助手", listOf("场景助手", "助手")),
            VoiceCommand("agent", "代理", listOf("AI代理", "代理") + agentName.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()),
            VoiceCommand("memory", "全天记忆", listOf("全天记忆", "记忆")),
            VoiceCommand("sights", "景点", listOf("景点介绍", "景点")),
            VoiceCommand("party", "聚会", listOf("聚会工具", "聚会")),
            VoiceCommand("party:dice", "骰子", listOf("骰子")),
            VoiceCommand("party:lots", "抽签", listOf("抽签")),
            VoiceCommand("party:truth", "真心话", listOf("真心话大冒险", "真心话", "大冒险")),
            VoiceCommand("follow", "跟读", listOf("语音跟读", "跟读")),
            VoiceCommand("games", "小游戏", listOf("小游戏", "游戏")),
            VoiceCommand("dash", "看板", listOf("看板", "默认面板")),
        )
        val extra = mapOf(
            "2048" to listOf("2048", "二零四八", "二〇四八"),
            "贪吃蛇" to listOf("贪吃蛇"),
            "俄罗斯方块" to listOf("俄罗斯方块", "方块"),
            "扫雷" to listOf("扫雷"),
            "数独" to listOf("数独"),
            "数字华容道" to listOf("数字华容道", "华容道"),
            "推箱子" to listOf("推箱子"),
            "五子棋" to listOf("五子棋"),
            "记忆翻牌" to listOf("记忆翻牌"),
        )
        for (g in games) list.add(VoiceCommand("game:$g", g, extra[g] ?: listOf(g)))
        return list
    }

    /** 戒指能逐条选的功能（语音还能用别名，比如骰子、看板）。游戏页只列游戏。 */
    private val RING = listOf("read", "nav", "pomo", "clock", "captions", "assist", "agent", "memory", "sights", "party", "follow", "games")

    fun ringItems(all: List<VoiceCommand>, games: Boolean): List<VoiceCommand> =
        if (games) all.filter { it.id.startsWith("game:") }
        else RING.mapNotNull { id -> all.find { it.id == id } }

    /** 一屏里的菜单：选中的那条用【】括起来，选到后面时整窗跟着往下走。 */
    fun window(labels: List<String>, index: Int, rows: Int): List<String> {
        if (labels.isEmpty() || rows <= 0) return emptyList()
        val i = index.coerceIn(0, labels.lastIndex)
        val start = if (labels.size <= rows) 0 else (i - rows + 1).coerceIn(0, labels.size - rows)
        return (start until minOf(start + rows, labels.size)).map { k -> if (k == i) "【${labels[k]}】" else "  ${labels[k]}" }
    }

    /** 戒指：上一条、下一条、中间键确认。音量键和播放键是息屏时系统还能交过来的那几种。 */
    fun keyAction(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_SELECT -> "ok"
        else -> null
    }

    /** 听写对上的那条；对不上返回 null。同一次命中里更长的名字优先（记忆翻牌不会被收成记忆）。 */
    fun match(heard: String, commands: List<VoiceCommand>, pinyin: (Char) -> String?): VoiceCommand? {
        val text = normalize(heard)
        if (text.isEmpty()) return null
        val stripped = strip(text)
        var best: VoiceCommand? = null
        var bestScore = 0
        for (cmd in commands) for (alias in cmd.aliases) {
            val a = normalize(alias)
            if (a.length < 2) continue
            val score = when {
                stripped == a || text == a -> 1000 + a.length
                stripped.contains(a) || text.contains(a) -> 500 + a.length
                else -> pinyinScore(stripped, a, pinyin)
            }
            if (score > bestScore) { bestScore = score; best = cmd }
        }
        return best
    }

    private fun strip(s: String): String {
        var t = s
        var changed = true
        while (changed) {
            changed = false
            for (f in FILLERS) if (t.startsWith(f) && t.length > f.length) { t = t.removePrefix(f); changed = true }
        }
        return t.removeSuffix("一下")
    }

    private fun normalize(s: String) = buildString {
        for (c in s.trim().lowercase()) {
            if (c.isWhitespace() || c in "，。！？、,.!?:：；;\"'“”") continue
            append(c)
        }
    }

    private fun pinyinScore(spoken: String, alias: String, pinyin: (Char) -> String?): Int {
        val a = tokens(alias, pinyin)
        val s = tokens(spoken, pinyin)
        if (a.isEmpty() || s.length < a.length) return 0
        return if ("|$s|".contains("|$a|")) 300 + alias.length else 0
    }

    private fun tokens(s: String, pinyin: (Char) -> String?): String {
        val parts = ArrayList<String>()
        val latin = StringBuilder()
        fun flush() {
            if (latin.isNotEmpty()) { parts.add(FollowText.fuzzy(latin.toString())); latin.setLength(0) }
        }
        for (c in s) {
            val py = when {
                c in '0'..'9' -> DIGIT[c - '0']
                else -> pinyin(c)
            }
            if (py != null) { flush(); parts.add(FollowText.fuzzy(py)) }
            else if (c in 'a'..'z') latin.append(c)
            else flush()
        }
        flush()
        return parts.joinToString("|")
    }
}

/**
 * 眼镜上的语音菜单。画在看板所在的导航页上（和提词器无关）：看板显示着时右双击打开。
 * 打开就开始听，说完停一下就匹配；左单击返回（在游戏名单上先回到功能名单）。
 */
class VoiceMenuApp(private val host: AppHost, private val env: Env) : GlassesApp {
    interface Env {
        fun asrReady(): Boolean
        fun language(): String
        fun setMic(on: Boolean)
        fun pinyin(c: Char): String?
        fun commands(): List<VoiceCommand>
        /** 启动这个功能。返回留在菜单上的一句话；null 表示已经离开菜单，或菜单自己该关掉。 */
        fun launch(id: String): String?
        /** 接管戒指（息屏时的音量键和播放键）。 */
        fun setRing(on: Boolean)
    }

    private enum class Page { MAIN, GAMES }

    override val title = "语音菜单"
    var running = false; private set
    private var page = Page.MAIN
    private var index = 0
    private var note = ""
    private var seg: CaptionSegmenter? = null
    private var decoder: OpusDecoder? = null
    private val pcm = ShortArray(960)
    private val exec = Executors.newSingleThreadExecutor()
    private var busy = false
    private val finals = ArrayDeque<ShortArray>()
    private val hearing get() = seg?.current != null
    var closeSelf: (() -> Unit)? = null

    private val idle = Runnable { if (running && !hearing && !busy) close() }

    override fun onOpen() {
        running = true; page = Page.MAIN; index = 0; note = ""; busy = false; finals.clear()
        env.setRing(true)
        if (!env.asrReady()) note = "离线转文字模型还没下载（全天记忆 › 设置 › 转文字）"
        else {
            decoder = OpusDecoder(NimoMic.SAMPLE_RATE, 1)
            seg = CaptionSegmenter(object : CaptionSegmenter.Listener {
                override fun partial(id: Int, pcm: ShortArray) { armIdle() }
                override fun final(id: Int, pcm: ShortArray) { finals.addLast(pcm); pump() }
                override fun dropped(id: Int) {}
            })
            env.setMic(true)
        }
        host.log("语音菜单：开始听")
        show(); armIdle()
    }

    override fun onClose() {
        running = false
        host.main.removeCallbacks(idle)
        seg = null
        env.setMic(false)
        env.setRing(false)
        host.changed()
    }

    fun onFrames(frames: List<NimoMic.Frame>) {
        if (!running) return
        val s = seg ?: return
        val d = decoder ?: return
        for (f in frames) {
            val n = try { d.decode(f.packet, 0, f.packet.size, pcm, 0, pcm.size, false) } catch (e: Exception) { 0 }
            if (n > 0) s.feed(pcm, n)
        }
    }

    private fun pump() {
        if (busy || !env.asrReady()) return
        val p = finals.removeFirstOrNull() ?: return
        busy = true
        val lang = env.language()
        exec.execute {
            val r = runCatching { LocalAsr.transcribe(AudioClean.process(p, AudioClean.Mode.NONE, floor = 0.2f, boost = true), lang).trim() }
            host.main.post {
                busy = false
                if (!running) return@post
                r.onSuccess { t -> if (t.isNotEmpty()) onHeard(t) else armIdle() }
                    .onFailure { note = "转文字出错：${it.message ?: it}"; show(); armIdle() }
                pump()
            }
        }
    }

    private fun onHeard(t: String) {
        val cmd = VoiceMenu.match(t, env.commands(), env::pinyin)
        host.log("语音菜单：听到「$t」" + (cmd?.let { " → ${it.label}" } ?: "，没对上"))
        if (cmd == null) { note = "没对上「${t.take(12)}」，再说一次"; show(); armIdle(); return }
        activate(cmd)
    }

    /** 戒指：up 上一条，down 下一条，ok 启动当前这条。 */
    fun remote(action: String) {
        if (!running) return
        val items = items()
        if (items.isEmpty()) return
        when (action) {
            "up" -> { index = (index - 1 + items.size) % items.size; note = ""; show(); armIdle() }
            "down" -> { index = (index + 1) % items.size; note = ""; show(); armIdle() }
            "ok" -> activate(items[index.coerceIn(0, items.lastIndex)])
        }
    }

    private fun items() = VoiceMenu.ringItems(env.commands(), page == Page.GAMES)

    private fun activate(cmd: VoiceCommand) {
        host.log("语音菜单：打开 ${cmd.label}")
        if (cmd.id == "games") { page = Page.GAMES; index = 0; note = ""; show(); armIdle(); return }
        if (cmd.id == "dash") { close(); return }
        val err = env.launch(cmd.id)
        if (!running) return
        if (err != null) { note = err; show(); armIdle() } else close()
    }

    private fun armIdle() {
        host.main.removeCallbacks(idle)
        host.main.postDelayed(idle, 45_000)
    }

    private fun close() { closeSelf?.invoke() }
    private fun show() { host.redraw(this); host.changed() }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_LEFT -> if (page == Page.GAMES) { page = Page.MAIN; index = 0; note = ""; show() } else close()
            GlassesInput.CLICK_RIGHT -> close()
            else -> {}
        }
    }

    override fun status() = if (hearing) "$title · 在听" else title

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val (body, st) = screen(27, 5)
        var y = f.header(st) + 2
        for (l in body.lines()) { f.text(l, 6, y); y += f.font.height + 5 }
        return f.done()
    }

    /** 菜单正文和顶栏。画成图片贴在看板上，不打开提词器。 */
    private fun screen(cols: Int, rows: Int): Pair<String, String> {
        val labels = items().map { it.label }
        val itemRows = if (note.isNotEmpty()) (rows - 1).coerceAtLeast(1) else rows
        val lines = ArrayList<String>()
        if (note.isNotEmpty()) lines += AgentApp.wrapCols(note, cols).take(1)
        if (labels.isNotEmpty()) lines += VoiceMenu.window(labels, index.coerceIn(0, labels.lastIndex), itemRows)
        val st = if (hearing) "语音菜单 · 在听" else "上下选　中键打开"
        return lines.take(rows).joinToString("\n") to st
    }
}
