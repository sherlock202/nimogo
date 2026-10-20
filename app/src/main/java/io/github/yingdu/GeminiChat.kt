package io.github.yingdu

import io.github.jaredmdobson.concentus.OpusDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 和 Gemini 语音对话（NimoGo）：眼镜麦克风录下一句话，直接把声音交给 Gemini（不用先转文字，少一次请求），
 * 它同时给出「听到的原话」和「回答」。可以打开 Google 搜索，回答天气、新闻、营业时间这类要最新信息的问题。
 * 用 Gemini 原生接口 models.generateContent（搜索工具在原生接口里最稳）。
 * 请求体和解析不依赖 Android，有单元测试。
 */
object GeminiChat {
    const val API = "https://generativelanguage.googleapis.com/v1beta"
    const val DEFAULT_MODEL = "gemini-3.8-flash"
    /** 记住最近几轮（只存文字，声音不再发第二次）。 */
    const val MAX_TURNS = 10

    class Turn(val user: String, val model: String, val at: Long = System.currentTimeMillis())

    fun system(now: String, city: String) = "你是用户戴在智能眼镜上的语音助手。用户对着眼镜说话，你的回答会显示在眼镜上一块很小的单色屏上。" +
        "回答要短：一般不超过 80 个字，先说结论，必要时再补一两句；不要用 Markdown、标题、表格、列表符号、emoji、网址。" +
        "用用户说话的语言回答（中文用简体）。" +
        "输出格式：第一行写「问：」加上你听到的用户原话（逐字照写）；第二行起写「答：」加上回答。" +
        "如果录音里听不清、只是噪声，或者明显不是在跟你说话（比如旁边的人在聊天），就只输出「问：」，后面留空，不要回答。" +
        "现在是 $now" + (if (city.isNotBlank()) "，用户在 $city 附近" else "") + "。"

    /** 请求体：前几轮的文字 + 这一句的声音（WAV，base64）。 */
    fun body(history: List<Turn>, wavB64: String, search: Boolean, system: String): JSONObject {
        val contents = JSONArray()
        for (t in history.takeLast(MAX_TURNS)) {
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", t.user))))
            contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "问：${t.user}\n答：${t.model}"))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", wavB64)))
            .put(JSONObject().put("text", "（这是用户刚说的话，按格式输出「问：」和「答：」）"))))
        val b = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        if (search) b.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return b
    }

    /** 从响应里取文字；被拦截、没有内容时抛出带原因的异常。 */
    fun text(json: String): String {
        val o = JSONObject(json)
        o.optJSONObject("error")?.let { throw IOException(it.optString("message")) }
        o.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { throw IOException("被 Gemini 拦截（$it）") }
        val c = o.optJSONArray("candidates")?.optJSONObject(0) ?: throw IOException("Gemini 没有给出回答")
        val parts = c.optJSONObject("content")?.optJSONArray("parts")
        val t = if (parts == null) "" else (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
            .filter { !it.optBoolean("thought", false) }.joinToString("") { it.optString("text") }
        if (t.isBlank()) {
            val why = c.optString("finishReason")
            if (why.isNotEmpty() && why != "STOP") throw IOException("Gemini 没有回答（$why）")
        }
        return t
    }

    /** 「问：…\n答：…」→ (听到的话, 回答)。格式不对时整段当回答。 */
    fun split(t: String): Pair<String, String> {
        val s = t.trim()
        val qi = s.indexOf("问：").takeIf { it >= 0 } ?: s.indexOf("问:")
        val ai = s.indexOf("答：").takeIf { it >= 0 } ?: s.indexOf("答:")
        if (qi < 0 && ai < 0) return "" to clean(s)
        val q = if (qi >= 0) s.substring(qi + 2, if (ai > qi) ai else s.length).trim() else ""
        val a = if (ai >= 0) s.substring(ai + 2).trim() else ""
        return q to clean(a)
    }

    /** 去掉模型偶尔还会带的 Markdown 记号和引用标号，换行合并成空格（眼镜屏小，按宽度重新折行）。 */
    fun clean(s: String): String = s
        .replace(Regex("""\[\d+(?:,\s*\d+)*]"""), "")
        .replace(Regex("""[*#`_]{1,3}"""), "")
        .replace(Regex("""^\s*[-•]\s+""", RegexOption.MULTILINE), "")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        .trim()

    /** 发请求（后台线程）。开着搜索时如果接口不接受（HTTP 400），去掉搜索再试一次。 */
    fun ask(key: String, model: String, history: List<Turn>, wav: ByteArray, search: Boolean, system: String): Pair<String, String> {
        val b64 = java.util.Base64.getEncoder().encodeToString(wav)
        return try {
            split(text(post(key, model, body(history, b64, search, system))))
        } catch (e: HttpError) {
            if (!search || e.code != 400) throw e
            split(text(post(key, model, body(history, b64, false, system))))
        }
    }

    class HttpError(val code: Int, msg: String) : IOException(msg)

    private fun post(key: String, model: String, body: JSONObject): String {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val c = URL("$API/models/${model.trim()}:generateContent").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key.trim())
        c.setFixedLengthStreamingMode(bytes.size)
        try {
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw HttpError(code, "HTTP $code ${msg ?: text.take(200)}")
            }
            return text
        } finally { c.disconnect() }
    }
}

/**
 * Gemini 对话（全屏功能）：打开后一直听，说完一句（停顿约 0.8 秒）就发给 Gemini，回答显示在眼镜上，接着听下一句（记得上下文）。
 * 镜腿：左单击结束；右单击翻回答的下一页；右双击清空上下文、换个话题。一分钟没人说话自动结束。
 */
class GeminiChatApp(private val host: AppHost, private val env: Env) : GlassesApp {
    interface Env {
        /** Gemini API Key（没填为空）。 */
        fun key(): String
        fun model(): String
        fun search(): Boolean
        fun city(): String
        fun setMic(on: Boolean)
        /** 结束对话（眼镜回到之前的画面）。 */
        fun end()
    }

    enum class State { LISTEN, HEAR, THINK, ERROR }

    override val title = "Gemini"

    val turns = ArrayList<GeminiChat.Turn>()
    /** 手机上显示的全部记录（清空上下文不影响，最多 100 轮）。 */
    val log = ArrayList<GeminiChat.Turn>()
    var running = false; private set
    var state = State.LISTEN; private set
    var error = ""; private set
    /** 刚才没听清（显示一下提示）。 */
    private var missed = false
    private var page = 0

    private var seg: CaptionSegmenter? = null
    private var decoder: OpusDecoder? = null
    private val pcm = ShortArray(960)
    private val exec = Executors.newSingleThreadExecutor()
    private var lastFrameAt = 0L
    private var lastActivity = 0L
    private var reqId = 0

    override fun onOpen() {
        running = true; error = ""; missed = false; page = 0
        state = if (env.key().isBlank()) State.ERROR else State.LISTEN
        if (state == State.ERROR) error = "先在手机上「Gemini 对话」里填 Gemini API Key"
        decoder = OpusDecoder(NimoMic.SAMPLE_RATE, 1)
        seg = CaptionSegmenter(object : CaptionSegmenter.Listener {
            override fun partial(id: Int, pcm: ShortArray) {}
            override fun final(id: Int, pcm: ShortArray) { onUtterance(pcm) }
            override fun dropped(id: Int) { if (state == State.HEAR) { state = State.LISTEN; show() } }
        })
        env.setMic(true)
        lastFrameAt = System.currentTimeMillis(); lastActivity = lastFrameAt
        host.main.removeCallbacks(watch); host.main.postDelayed(watch, 5_000)
        host.log("Gemini 对话：开始")
        show()
    }

    override fun onClose() {
        running = false
        reqId++
        seg = null
        env.setMic(false)
        host.main.removeCallbacks(watch)
        host.changed()
    }

    /** 几秒没收到声音就重开麦克风；一直没人说话就结束。 */
    private val watch: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastFrameAt > 5_000) env.setMic(true)
            val idle = if (turns.isEmpty()) IDLE_FIRST_MS else IDLE_MS
            if (state != State.THINK && state != State.HEAR && now - lastActivity > idle) {
                host.log("Gemini 对话：${idle / 1000} 秒没说话，结束"); env.end(); return
            }
            host.main.postDelayed(this, 5_000)
        }
    }

    /** 眼镜麦克风的声音（主线程）。 */
    fun onFrames(frames: List<NimoMic.Frame>) {
        val s = seg ?: return
        val d = decoder ?: return
        lastFrameAt = System.currentTimeMillis()
        // 思考中不听（免得把自己读回答、旁人说话当成下一句）
        if (state == State.THINK || state == State.ERROR) return
        for (f in frames) {
            val n = try { d.decode(f.packet, 0, f.packet.size, pcm, 0, pcm.size, false) } catch (e: Exception) { 0 }
            if (n > 0) s.feed(pcm, n)
        }
        if (s.current != null && state == State.LISTEN) { state = State.HEAR; missed = false; lastActivity = System.currentTimeMillis(); show() }
    }

    private fun onUtterance(p: ShortArray) {
        if (!running || state == State.THINK) return
        val key = env.key()
        if (key.isBlank()) { state = State.ERROR; error = "先在手机上「Gemini 对话」里填 Gemini API Key"; show(); return }
        state = State.THINK; missed = false
        lastActivity = System.currentTimeMillis()
        show()
        val id = ++reqId
        val history = ArrayList(turns)
        val model = env.model(); val search = env.search()
        val sys = GeminiChat.system(java.text.SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", java.util.Locale.CHINA).format(java.util.Date()), env.city())
        exec.execute {
            val r = runCatching {
                val wav = OggOpus.wav(AudioClean.process(p, AudioClean.Mode.NONE, floor = 0.2f, boost = true))
                GeminiChat.ask(key, model, history, wav, search, sys)
            }
            host.main.post {
                if (id != reqId || !running) return@post
                lastActivity = System.currentTimeMillis()
                r.onSuccess { (heard, answer) ->
                    error = ""
                    if (heard.isBlank() && answer.isBlank()) missed = true
                    else {
                        val t = GeminiChat.Turn(heard.ifBlank { "（没听清）" }, answer.ifBlank { "（没有回答）" })
                        turns.add(t); while (turns.size > GeminiChat.MAX_TURNS) turns.removeAt(0)
                        log.add(t); while (log.size > 100) log.removeAt(0)
                        page = 0
                    }
                    state = State.LISTEN
                }.onFailure {
                    error = "Gemini 出错：${it.message ?: it.javaClass.simpleName}"
                    host.log(error)
                    state = State.LISTEN
                }
                show()
            }
        }
    }

    /** 清空上下文（换话题）。 */
    fun newTopic() { turns.clear(); page = 0; missed = false; error = ""; lastActivity = System.currentTimeMillis(); show() }

    private fun show() { host.redraw(this); host.changed() }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_LEFT -> env.end()
            GlassesInput.CLICK_RIGHT -> { page++; lastActivity = System.currentTimeMillis(); show() }
            GlassesInput.DOUBLE_RIGHT -> newTopic()
            else -> {}
        }
    }

    override fun status() = "$title · " + when (state) {
        State.LISTEN -> "在听"; State.HEAR -> "听到说话"; State.THINK -> "思考中"; State.ERROR -> "出错"
    }

    override fun render(fontPx: Int): ByteArray = draw(fontPx).done()

    fun draw(fontPx: Int): Frame {
        val f = Frame(fontPx)
        val top = f.header("Gemini" + if (env.search()) " · 可搜索" else "", when (state) {
            State.LISTEN -> "● 在听"; State.HEAR -> "● 听到了…"; State.THINK -> "思考中…"; State.ERROR -> "出错"
        })
        val maxW = f.w - 12
        val lineH = f.font.height + 4
        val last = turns.lastOrNull()
        if (last == null || state == State.ERROR) {
            val msg = when {
                error.isNotEmpty() -> error
                state == State.THINK -> "思考中…"
                missed -> "没听清，再说一遍？"
                else -> "请说话…"
            }
            var y = top + 6
            f.font.wrap(msg, maxW).take(3).forEach { f.text(it, 6, y, Frame.FULL); y += lineH }
            f.footer("左击结束 · 右双击换话题")
            return f
        }
        // 上面一行：听到的话（半亮）；下面：回答，分页
        var y = top + 2
        f.text(f.small.ellipsize("问：" + last.user, maxW), 6, y, Frame.DIM, f.small)
        y += f.small.height + 6
        val lines = f.font.wrap(last.model, maxW)
        val bottom = f.h - f.small.height - 6
        val perPage = ((bottom - y) / lineH).coerceAtLeast(1)
        val pages = (lines.size + perPage - 1) / perPage
        val p = if (pages == 0) 0 else page.mod(pages)
        lines.drop(p * perPage).take(perPage).forEach { f.text(it, 6, y, Frame.FULL); y += lineH }
        val hint = when {
            state == State.THINK -> "思考中…"
            error.isNotEmpty() -> error
            missed -> "没听清，再说一遍"
            else -> "可以接着问 · 左击结束"
        }
        val pageText = if (pages > 1) "${p + 1}/$pages 右击翻页" else ""
        if (pageText.isNotEmpty()) f.text(pageText, f.w - 6, f.h - f.small.height - 3, Frame.DIM, f.small, Frame.RIGHT)
        f.text(f.small.ellipsize(hint, maxW - (if (pageText.isEmpty()) 0 else f.small.measure(pageText) + 12)), 6, f.h - f.small.height - 3, Frame.DIM, f.small)
        return f
    }

    companion object {
        /** 开始后这么久没说话就结束。 */
        const val IDLE_FIRST_MS = 20_000L
        /** 回答后这么久没人说话（也没翻页）就结束。 */
        const val IDLE_MS = 60_000L
    }
}
