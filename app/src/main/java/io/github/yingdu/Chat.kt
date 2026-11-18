package io.github.yingdu

import io.github.jaredmdobson.concentus.OpusDecoder
import java.util.concurrent.Executors

/**
 * 和 AI 直接聊天。大模型和转文字都用场景助手里已经填好的那一套。
 * 眼镜上说完一句（停 2 秒以上）就发出去；一句最长 30 秒。手机上也可以打字。回答显示在眼镜上。
 */
class ChatTurn(val me: Boolean, val text: String, val at: Long)

class ChatApp(private val host: AppHost, private val env: Env) : GlassesApp {
    interface Env {
        /** (服务 id, 接口地址, Key, 型号)，和场景助手现场出建议用的是同一套。 */
        fun llm(): List<String>
        fun setMic(on: Boolean)
        fun asrReady(): Boolean
        /** 把这一句转成文字（后台转，回到主线程再回调）。 */
        fun transcribe(pcm: ShortArray, done: (Result<String>) -> Unit)
    }

    override val title = "聊天"

    val turns = ArrayList<ChatTurn>()
    var running = false; private set
    var thinking = false; private set
    var error = ""; private set
    val hearing get() = seg?.current != null

    private var seg: CaptionSegmenter? = null
    private var decoder: OpusDecoder? = null
    private val pcm = ShortArray(960)
    private val llmExec = Executors.newSingleThreadExecutor()
    private var serial = 0
    /** 正在等回答时又说了一句：先存着，这句回答完再发。 */
    private var nextLine: String? = null
    private var page = 0
    private var textCols = 27
    private var textRows = 5
    private var lastFrameAt = 0L

    override fun onOpen() {
        running = true
        error = if (env.asrReady()) "" else "还不能听：先在「场景助手 › 设置」里设好转文字。手机上可以打字。"
        decoder = OpusDecoder(NimoMic.SAMPLE_RATE, 1)
        seg = CaptionSegmenter(object : CaptionSegmenter.Listener {
            override fun partial(id: Int, pcm: ShortArray) {}
            override fun final(id: Int, pcm: ShortArray) {
                if (!env.asrReady()) return
                env.transcribe(pcm) { r ->
                    if (!running) return@transcribe
                    r.onSuccess { t ->
                        val s = t.trim()
                        when {
                            s.isEmpty() -> {}
                            // 没人说话时麦克风听到的「嗯」「啊」或者 Whisper 编的句子，不当成用户说的话
                            SpeechToText.isFiller(s) || SpeechToText.isHallucination(s) -> host.log("聊天：丢掉像噪音的「${s.take(20)}」")
                            else -> say(s)
                        }
                    }.onFailure {
                        error = "转文字出错：${it.message ?: it.javaClass.simpleName}"
                        host.log("聊天：$error")
                        show()
                    }
                }
            }
            override fun dropped(id: Int) {}
        }, endQuietFrames = CaptionSegmenter.CHAT_END_QUIET_FRAMES, maxFrames = CaptionSegmenter.CHAT_MAX_FRAMES)
        if (env.asrReady()) env.setMic(true)
        lastFrameAt = System.currentTimeMillis()
        host.main.removeCallbacks(watch)
        host.main.postDelayed(watch, 5_000)
        host.log("聊天：开始")
        show()
    }

    override fun onClose() {
        running = false
        serial++
        seg?.flush(); seg = null
        env.setMic(false)
        host.main.removeCallbacks(watch)
        thinking = false
        host.changed()
    }

    override fun onResume() { show() }

    private val watch: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (env.asrReady() && System.currentTimeMillis() - lastFrameAt > 5_000) env.setMic(true)
            host.main.postDelayed(this, 5_000)
        }
    }

    fun onFrames(frames: List<NimoMic.Frame>) {
        val s = seg ?: return
        val d = decoder ?: return
        lastFrameAt = System.currentTimeMillis()
        val was = s.current != null
        for (f in frames) {
            val n = try { d.decode(f.packet, 0, f.packet.size, pcm, 0, pcm.size, false) } catch (e: Exception) { 0 }
            if (n > 0) s.feed(pcm, n)
        }
        if (was != (s.current != null)) show()
    }

    /** 我说的一句（语音或手机打字）。正在等上一句的回答时先排着。 */
    fun say(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (thinking) {
            nextLine = listOfNotNull(nextLine, t).joinToString("\n")
            show()
            return
        }
        turns.add(ChatTurn(true, t, System.currentTimeMillis()))
        while (turns.size > MAX_TURNS) turns.removeAt(0)
        page = 0
        ask()
    }

    fun clear() {
        serial++
        turns.clear()
        nextLine = null
        thinking = false
        error = ""
        page = 0
        show()
    }

    private fun ask() {
        val cfg = env.llm()
        if (cfg.size < 4 || cfg[2].isBlank()) {
            thinking = false
            error = "还没填大模型的 Key（「场景助手 › 设置」）"
            show()
            return
        }
        val (provider, base, key, model) = cfg
        val mine = turns.toList()
        // 最新一句是用户刚说的（say 里刚加进去的）；前面的几句作为对话历史，按真正的角色交替发给模型
        val latest = mine.last().text.trim()
        val history = mine.dropLast(1).takeLast(12).map { (if (it.me) "user" else "assistant") to it.text.trim() }
        val id = ++serial
        thinking = true
        error = ""
        show()
        val system = "你在和用户直接聊天。用户戴着智能眼镜，用语音或手机打字和你说话，你的回答会显示在眼镜上。" +
            "用户的话是语音识别的结果，可能有错字或断句不对。你要回答他这句话的意思，不要重复、复述或引用他的原话，不要说「你说的是」「我听到你说」这类话。" +
            "用用户说的语言回答，口语、简短，一般两三句。不要 Markdown，不要标题，不要寒暄套话。"
        llmExec.execute {
            val r = runCatching { AssistAi.chat(provider, base, key, model, system, latest, 800, 40_000, history) }
            host.main.post {
                if (id != serial) return@post
                thinking = false
                r.onSuccess { text ->
                    error = ""
                    turns.add(ChatTurn(false, text.trim(), System.currentTimeMillis()))
                    while (turns.size > MAX_TURNS) turns.removeAt(0)
                    page = 0
                    host.log("聊天：回答（$model，${text.length} 字）")
                }.onFailure {
                    error = it.message ?: it.javaClass.simpleName
                    host.log("聊天：出错：$error")
                }
                val queued = nextLine
                nextLine = null
                if (!queued.isNullOrBlank()) say(queued) else show()
            }
        }
    }

    fun flip(d: Int) {
        val n = pages().size
        val p = (page + d).coerceIn(0, maxOf(0, n - 1))
        if (p != page) { page = p; show() }
    }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.DOUBLE_RIGHT -> flip(1)
            GlassesInput.DOUBLE_LEFT -> flip(-1)
            else -> {}
        }
    }

    private fun show() { host.redraw(this); host.changed() }

    override fun status() = title + when {
        thinking -> " · 在想"
        hearing -> " · 在听"
        else -> ""
    }

    private fun wrap(s: String, cols: Int): List<String> {
        val cap = cols.coerceIn(4, 60) * 2
        val out = ArrayList<String>()
        for (para in s.lines()) {
            val p = para.trim()
            if (p.isEmpty()) continue
            val sb = StringBuilder(); var w = 0
            for (c in p) {
                val cw = if (c.code < 0x80) 1 else 2
                if (w + cw > cap && sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0); w = 0 }
                sb.append(c); w += cw
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
        }
        return out
    }

    private fun pages(): List<List<String>> {
        val last = turns.lastOrNull { !it.me }?.text ?: return listOf(emptyList())
        val rows = wrap(last, textCols)
        val room = maxOf(1, textRows - 1)
        return if (rows.isEmpty()) listOf(emptyList()) else rows.chunked(room)
    }

    override fun glassesText(cols: Int, rows: Int): Pair<String, String> {
        textCols = cols; textRows = rows
        val ear = when {
            thinking -> "在想"
            hearing -> "● 在听"
            error.isNotEmpty() -> "出错"
            else -> "在听"
        }
        val me = turns.lastOrNull { it.me }?.text?.trim().orEmpty()
        val head = if (me.isEmpty()) "" else wrap("我：$me", cols).lastOrNull().orEmpty()
        val body = when {
            thinking -> listOf(head, "在想…")
            error.isNotEmpty() && turns.none { !it.me } -> wrap(error, cols)
            turns.none { !it.me } -> wrap(if (error.isNotEmpty()) error else "说吧，说完停 2 秒我就回答。手机上也可以打字。", cols)
            else -> {
                val ps = pages()
                val p = page.coerceIn(0, ps.size - 1)
                listOf(head) + ps[p]
            }
        }
        val ps = pages()
        val mark = if (!thinking && ps.size > 1) " ${page.coerceIn(0, ps.size - 1) + 1}/${ps.size}" else ""
        return body.filter { it.isNotEmpty() }.takeLast(rows).joinToString("\n").ifEmpty { " " } to "聊天 · $ear$mark"
    }

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        var y = f.header("聊天", if (thinking) "在想" else "在听")
        val last = turns.lastOrNull()
        f.text(last?.let { (if (it.me) "我：" else "") + it.text } ?: "说吧", 6, y)
        return f.done()
    }

    private companion object {
        const val MAX_TURNS = 40
    }
}
