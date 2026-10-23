package io.github.yingdu

import io.github.jaredmdobson.concentus.OpusDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/*
 * 场景助手（NimoGo，2026-10-23）：相亲、面试等场景里，眼镜麦克风一直听着（手机离线转文字，分出「我」和「对方」），
 * 平时不说话；按右镜腿（戒指下键）给参考回答，按左镜腿（戒指上键）给可以问对方的问题，结束后在手机上复盘。
 * 这个文件里：场景、对话记录和存储、提示词和大模型调用（纯 Kotlin，有单元测试），以及眼镜上的全屏功能 AssistApp。
 */

/** 场景。kind：DATE 相亲、INTERVIEW 面试、OTHER 其他（决定复盘看什么）。lang：建议用什么语言写（zh / ja / en / auto）。 */
data class AssistScene(val id: String, val name: String, val kind: String, val lang: String, val goal: String, val aboutMe: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("lang", lang)
        .put("goal", goal).put("aboutMe", aboutMe)

    val kindName get() = KINDS.firstOrNull { it.first == kind }?.second ?: "其他"
    val langName get() = LANGS.firstOrNull { it.first == lang }?.second ?: "自动"

    companion object {
        /** 普通版能用的那一个场景（面试）；其他场景要开通私人定制。 */
        const val FREE_ID = "interview"
        val KINDS = listOf("DATE" to "相亲", "INTERVIEW" to "面试", "OTHER" to "其他")
        val LANGS = listOf("zh" to "中文", "ja" to "日语", "en" to "英语", "auto" to "自动")

        fun fromJson(o: JSONObject) = AssistScene(o.optString("id"), o.optString("name"), o.optString("kind", "OTHER"),
            o.optString("lang", "zh"), o.optString("goal"), o.optString("aboutMe"))

        val DEFAULTS = listOf(
            AssistScene("date", "相亲", "DATE", "zh",
                "第一次见面的相亲。我想聊得自然、不冷场，了解对方的性格、价值观和生活方式，也让对方了解我。", ""),
            AssistScene("interview", "面试", "INTERVIEW", "ja",
                "日本公司的面试，对方是面试官。我想回答得得体、有条理，也想弄清这个职位和公司是否真的适合我。", ""),
        )

        fun listToJson(l: List<AssistScene>) = JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(s: String?): List<AssistScene> = runCatching {
            val a = JSONArray(s ?: return DEFAULTS)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.filter { it.id.isNotEmpty() }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULTS
    }
}

/** 一句话：me = true 是我，false 是对方，null 分不出。voice 是这句的声音特征（手机上改「谁说的」时拿来教分人的模型）。 */
class AssistLine(val at: Long, var me: Boolean?, val text: String, val voice: Speakers.Voice? = null) {
    fun toJson(): JSONObject = JSONObject().put("t", at).put("m", when (me) { true -> 1; false -> 0; null -> -1 }).put("x", text)
        .apply { voice?.let { put("v", it.toJson()) } }
    companion object {
        fun fromJson(o: JSONObject) = AssistLine(o.optLong("t"), when (o.optInt("m", -1)) { 1 -> true; 0 -> false; else -> null }, o.optString("x"),
            Speakers.Voice.fromJson(o.optJSONArray("v")))
        /** 眼镜和手机上标出我说的话。 */
        const val ME_TAG = "【我】"
    }
}

/** 现场给过的建议（复盘时一起看）。kind：answer / question。 */
class AssistAsk(val at: Long, val kind: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("t", at).put("k", kind).put("x", text)
    companion object { fun fromJson(o: JSONObject) = AssistAsk(o.optLong("t"), o.optString("k"), o.optString("x")) }
}

/** 一场对话。 */
class AssistSession(val id: String, val scene: AssistScene, val start: Long) {
    var end = 0L
    val lines = ArrayList<AssistLine>()
    val asks = ArrayList<AssistAsk>()
    var review = ""
    var reviewModel = ""
    var reviewAt = 0L

    fun toJson(): JSONObject = JSONObject().put("id", id).put("scene", scene.toJson()).put("start", start).put("end", end)
        .put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
        .put("asks", JSONArray().apply { asks.forEach { put(it.toJson()) } })
        .put("review", review).put("reviewModel", reviewModel).put("reviewAt", reviewAt)

    val title: String get() = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(start)) + " · " + scene.name

    companion object {
        fun fromJson(o: JSONObject) = AssistSession(o.optString("id"), AssistScene.fromJson(o.optJSONObject("scene") ?: JSONObject()), o.optLong("start")).apply {
            end = o.optLong("end")
            o.optJSONArray("lines")?.let { a -> for (i in 0 until a.length()) lines.add(AssistLine.fromJson(a.getJSONObject(i))) }
            o.optJSONArray("asks")?.let { a -> for (i in 0 until a.length()) asks.add(AssistAsk.fromJson(a.getJSONObject(i))) }
            review = o.optString("review"); reviewModel = o.optString("reviewModel"); reviewAt = o.optLong("reviewAt")
        }
    }
}

/** 每场对话一个 JSON 文件（files/assist/），只存在手机上。 */
class AssistStore(private val dir: File) {
    fun save(s: AssistSession) = write(s.id, s.toJson().toString())

    /** 写一场的 JSON（先在主线程把对象转成文字，再交给后台线程写，免得边写边改）。 */
    @Synchronized
    fun write(id: String, json: String) {
        dir.mkdirs()
        val f = File(dir, "$id.json")
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun list(): List<AssistSession> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> runCatching { AssistSession.fromJson(JSONObject(f.readText(Charsets.UTF_8))) }.getOrNull() }
        .sortedByDescending { it.start }

    fun load(id: String): AssistSession? = File(dir, "$id.json").takeIf { it.isFile }
        ?.let { runCatching { AssistSession.fromJson(JSONObject(it.readText(Charsets.UTF_8))) }.getOrNull() }

    fun delete(id: String) { File(dir, "$id.json").delete() }
}

/** 提示词和大模型调用（OpenAI 兼容的 /chat/completions，或 Claude 的 /v1/messages）。 */
object AssistAi {
    /** live：现场出建议用的型号（要快）；review：复盘用的型号（可以慢、要细）。 */
    class Preset(val id: String, val name: String, val base: String, val live: String, val review: String, val note: String)

    // 型号名称各家常改：设置里都可以改（2026-10，没能联网核对最新的型号名）
    val PRESETS = listOf(
        Preset("groq", "Groq（有免费额度）", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "openai/gpt-oss-120b",
            "服务器在海外、出字很快，适合现场；console.groq.com 申请 Key"),
        Preset("gemini", "Google Gemini（有免费额度）", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-flash-latest", "gemini-flash-latest",
            "aistudio.google.com 申请 Key；免费版的对话可能被 Google 用来改进模型"),
        Preset("claude", "Claude", "https://api.anthropic.com", "claude-opus-5", "claude-opus-5", ""),
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "gpt-4o-mini", ""),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "deepseek-chat", "服务器在国内，从海外用会慢"),
        Preset("custom", "自定义（OpenAI 兼容）", "", "", "", "填接口地址（到 /v1 为止）、Key 和型号"),
    )

    fun preset(id: String) = PRESETS.firstOrNull { it.id == id } ?: PRESETS[0]

    /** 「我 / 对方 / ？」标好的对话文字；太长时只留最后 maxChars 个字（从整句开始）。 */
    fun transcript(lines: List<AssistLine>, maxChars: Int = Int.MAX_VALUE, withTime: Boolean = false): String {
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        val all = lines.filter { it.text.isNotBlank() }.map { l ->
            (if (withTime) "[" + hm.format(Date(l.at)) + "] " else "") + when (l.me) { true -> "我："; false -> "对方："; null -> "（分不清）：" } + l.text.trim()
        }
        val out = ArrayList<String>()
        var n = 0
        for (s in all.asReversed()) {
            if (n + s.length + 1 > maxChars && out.isNotEmpty()) break
            out.add(s); n += s.length + 1
        }
        return out.asReversed().joinToString("\n")
    }

    private fun langFor(scene: AssistScene) = when (scene.lang) {
        "zh" -> "中文"; "ja" -> "日语"; "en" -> "英语"; else -> "和对方相同的语言"
    }

    private fun background(scene: AssistScene) = buildString {
        append("场景：").append(scene.name).append("（").append(scene.kindName).append("）。")
        if (scene.goal.isNotBlank()) append("\n场景说明和我的目的：").append(scene.goal.trim())
        if (scene.aboutMe.isNotBlank()) append("\n关于我：").append(scene.aboutMe.trim())
    }

    /** 现场：系统提示。 */
    fun liveSystem(scene: AssistScene) = "你是用户的现场对话参谋。用户戴着智能眼镜，正在和别人面对面交谈，你给的内容会显示在眼镜上，用户边听边看。\n" +
        background(scene) + "\n" +
        "下面是到目前为止的对话，由语音识别自动转写（可能有错字、同音字，也可能漏字），「我」是用户本人，「对方」是交谈的人，「（分不清）」是没分出是谁。" +
        "请结合上下文理解，不要被错字带偏。\n" +
        "输出要求：只输出要显示的内容，不要寒暄、不要解释你的思路、不要用 Markdown（不要 #、**、表格），每条一段，前面用 1. 2. 3. 编号。"

    /** 现场：kind = answer 参考回答 / question 提问建议。 */
    /**
     * 要回答的那段：对方最近说的话（最后一句「我」之前或之后、连着的「对方 / 分不清」的几句，最多 3 句）。
     * 对方最近没说话（一直是我在说）时为空。
     */
    fun lastFromOther(lines: List<AssistLine>): String {
        val ls = lines.filter { it.text.isNotBlank() }
        var end = ls.size
        while (end > 0 && ls[end - 1].me == true) end--          // 跳过最后我说的几句
        var start = end
        while (start > 0 && ls[start - 1].me != true && end - start < 3) start--
        return ls.subList(start, end).joinToString(" ") { it.text.trim() }
    }

    fun liveUser(kind: String, scene: AssistScene, transcript: String, lastOther: String = ""): String {
        val lang = langFor(scene)
        val task = if (kind == "question")
            "请给我 3 个接下来可以问对方的问题，用$lang 写成我可以直接说出口的话。要贴合场景目的和刚才聊到的内容，能自然地推进对话、了解对方真实的想法和情况，" +
                "避免太突兀或太冒犯的问题；不要重复已经问过、对方已经回答过的。"
        else
            (if (lastOther.isNotBlank()) "对方最近说的是：「$lastOther」\n我接下来要回应的就是对方的这段话：如果对方问了问题，就回答这个问题。" +
                "不要去回答对话里我自己问过对方的问题，也不要替对方回答。（分人可能有错：如果这段明显是我自己说的，就按上下文判断我该怎么接话。）\n"
            else "（最近没听到对方说话，可能漏听了。请根据对话判断我接下来该怎么说。）\n") +
            "请给我 3 个可以直接说出口的参考回答，用$lang 写，自然口语，每条 1～3 句。三条的方向不同（比如：直接回答、举个自己的例子、顺势反问）。" +
                "如果对方的话里有需要小心的地方（陷阱问题、不好直接回答的、需要我确认的事实），在最后单独一行用中文写「提示：……」。"
        val body = if (transcript.isBlank()) "（还没有听到对话）" else transcript
        return "对话：\n$body\n\n$task"
    }

    /** 复盘：系统提示（按场景种类看不同的东西）。 */
    fun reviewSystem(scene: AssistScene): String {
        val focus = when (scene.kind) {
            "DATE" -> "请按下面几部分写：\n" +
                "一、对方的基本情况：对方说到的事实（工作、家庭、住处、经历、打算等），只写对话里出现过的。\n" +
                "二、性格和价值观：从说话方式和内容看出的倾向，每点附上依据（引用原话）。\n" +
                "三、聊得来和聊不来的地方：共同点、分歧点。\n" +
                "四、需要留意、值得再确认的地方：前后说法不一致、回答含糊或回避、和常识不符、让人担心的信号，逐条引用原话并说明为什么值得注意。" +
                "只凭文字无法判断一个人是否说谎，不要下「对方在说谎」的结论，写成「这里值得再确认」。没有就写没有。\n" +
                "五、我的表现：说得好的地方、可以改进的地方（具体到哪句话、可以怎么说）。\n" +
                "六、下次见面可以聊的话题和可以问的问题。\n" +
                "七、总体印象（几句话，客观，不替我做决定）。"
            "INTERVIEW" -> "请按下面几部分写：\n" +
                "一、从对话里了解到的职位和公司：实际的工作内容、团队、上司、工作方式、加班和出差、评价和晋升、待遇等，只写对话里出现过的，没提到的列为「没问到」。\n" +
                "二、面试官透露的信号：重视什么、担心什么、对我的反应。\n" +
                "三、这个职位是否适合我：对照「关于我」里的经历和期望，逐条写匹配的地方和不匹配或有风险的地方，最后给出你的判断和理由（我来做决定）。\n" +
                "四、需要再确认或追问的地方：说法含糊、前后矛盾、和招聘信息可能不一致的地方，引用原话；以及下次面试、谈 offer 时应该问的问题。\n" +
                "五、我的回答表现：答得好的、可以改进的（具体到哪个问题、更好的答法，用面试时的语言写例句）。"
            else -> "请按下面几部分写：\n" +
                "一、谈话要点：谈了什么、定了什么、各自的立场。\n" +
                "二、对方的意图和关心的事（附原话依据）。\n" +
                "三、需要留意或再确认的地方：含糊、矛盾、风险，引用原话。\n" +
                "四、我的表现：好的和可以改进的。\n" +
                "五、接下来该做什么。"
        }
        return "你是用户的谈话复盘顾问。用户戴着智能眼镜参加了一场谈话，眼镜麦克风录下的声音已自动转成文字（可能有错字、同音字），" +
            "「我」是用户本人，「对方」是交谈的人，「（分不清）」是没分出是谁，分人也可能有错，请结合内容判断。\n" +
            background(scene) + "\n" +
            "用中文写复盘（引用原话时保留原来的语言）。$focus\n" +
            "格式：纯文字，用「一、二、三」做小标题，每条用「· 」开头，不要用 Markdown 的 #、**、表格。客观、具体，有依据就引用原话，没有依据的不要编。"
    }

    fun reviewUser(s: AssistSession): String {
        val t = transcript(s.lines, withTime = true)
        val asks = s.asks.joinToString("\n") { a ->
            "[" + SimpleDateFormat("HH:mm", Locale.US).format(Date(a.at)) + "] " + (if (a.kind == "question") "提问建议" else "参考回答") + "：" +
                a.text.replace('\n', ' ').take(300)
        }
        return "对话（${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(s.start))} 开始）：\n$t" +
            (if (asks.isNotEmpty()) "\n\n现场眼镜上给过我的建议（供参考，我不一定照着说了）：\n$asks" else "")
    }

    /** 请求正文。 */
    fun body(provider: String, model: String, system: String, user: String, maxTokens: Int): JSONObject =
        if (provider == "claude") JSONObject()
            .put("model", model).put("max_tokens", maxTokens).put("fallbacks", "default").put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        else JSONObject()
            .put("model", model).put("max_tokens", maxTokens)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .apply { if (model.contains("gpt-oss")) put("reasoning_effort", "low") }   // Groq 上的 gpt-oss：少想一点，出字快

    /** 去掉模型偶尔还是会加的 Markdown 记号。 */
    fun plain(s: String): String = s.lines().joinToString("\n") { l ->
        l.replace(Regex("""^\s*#{1,6}\s*"""), "").replace("**", "").replace("__", "")
    }.replace(Regex("""\n{3,}"""), "\n\n").trim()

    /** 调用大模型（后台线程）。 */
    fun chat(provider: String, base: String, key: String, model: String, system: String, user: String, maxTokens: Int, timeoutMs: Int): String {
        if (key.isBlank()) throw IOException("还没填大模型的 API Key（「场景助手 › 设置」）")
        if (base.isBlank() || model.isBlank()) throw IOException("还没填接口地址或型号（「场景助手 › 设置」）")
        val claude = provider == "claude"
        val url = base.trimEnd('/') + if (claude) "/v1/messages" else "/chat/completions"
        val bytes = body(provider, model, system, user, maxTokens).toString().toByteArray(Charsets.UTF_8)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = timeoutMs
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (claude) {
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
        } else c.setRequestProperty("Authorization", "Bearer $key")
        c.setFixedLengthStreamingMode(bytes.size)
        try {
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                    ?: runCatching { JSONArray(text).getJSONObject(0).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IOException(when (code) {
                    401, 403 -> "Key 不对或没有权限（HTTP $code）"
                    429 -> "请求太频繁或超出免费额度（HTTP 429），等一会儿再试"
                    else -> "HTTP $code ${msg ?: text.take(200)}"
                })
            }
            return plain(MemoryAsk.parse(provider, text)).ifEmpty { throw IOException("模型没有给出内容") }
        } finally { c.disconnect() }
    }
}

/**
 * 场景助手（眼镜上的全屏功能）。打开后一直开着眼镜麦克风：听到的话按停顿切成一句一句，在手机上离线转文字，分出我和对方。
 * 平时眼镜上只显示最近几句和按键提示；要建议时把最近的对话交给大模型，结果显示在眼镜上，可以翻页。
 */
class AssistApp(private val host: AppHost, private val env: Env) : GlassesApp {
    interface Env {
        fun scene(): AssistScene
        /** (服务 id, 接口地址, Key, 现场型号)。 */
        fun llm(): List<String>
        fun setMic(on: Boolean)
        fun isMe(v: Speakers.Voice?): Boolean?
        fun save(s: AssistSession)
        /** 离线转文字模型下好了。 */
        fun asrReady(): Boolean
        /** 接管 / 放开音量键和耳机键（手机息屏也能翻页）。 */
        fun takeKeys(on: Boolean)
    }

    enum class View { LISTEN, THINKING, SHOW }

    override val title = "场景助手"

    var session: AssistSession? = null; private set
    var running = false; private set
    var view = View.LISTEN; private set
    /** 正在显示的建议：answer / question。 */
    var showKind = ""; private set
    var showText = ""; private set
    var page = 0; private set
    var error = ""; private set
    /** 正在说话（还没说完的那句）。 */
    val hearing get() = seg?.current != null
    val transcribing get() = busy || finals.isNotEmpty()

    private var seg: CaptionSegmenter? = null
    private var decoder: OpusDecoder? = null
    private val pcm = ShortArray(960)
    private val asrExec = Executors.newSingleThreadExecutor()
    private val llmExec = Executors.newSingleThreadExecutor()
    private var busy = false
    private val finals = ArrayDeque<ShortArray>()
    private var lastFrameAt = 0L
    /** 按了键、要等最后一句转完再问大模型。 */
    private var waitingKind: String? = null
    private var askSerial = 0
    private var fontPx = 16

    override fun onOpen() {
        running = true; error = ""
        if (!busy && finals.isEmpty()) ending = null
        if (session == null) session = AssistSession("a" + System.currentTimeMillis(), env.scene(), System.currentTimeMillis())
        view = View.LISTEN; showText = ""; waitingKind = null
        decoder = OpusDecoder(NimoMic.SAMPLE_RATE, 1)
        busy = false; finals.clear()
        // 对面的人离麦克风远、声音轻：说话判断放宽一些（比底噪响 4 dB、周期性 0.45、300 ms 里 30% 有声就算），免得漏句
        seg = CaptionSegmenter(object : CaptionSegmenter.Listener {
            override fun partial(id: Int, pcm: ShortArray) {}
            override fun final(id: Int, pcm: ShortArray) { finals.addLast(pcm); pump() }
            override fun dropped(id: Int) {}
        }, SpeechDetector(aboveFloor = 4.0, periodicityMin = 0.45, voteNeed = 9))
        if (!env.asrReady()) error = "离线转文字模型还没下载（「全天记忆 › 设置 › 转文字」）"
        env.setMic(true)
        env.takeKeys(true)
        lastFrameAt = System.currentTimeMillis()
        host.main.removeCallbacks(watch); host.main.postDelayed(watch, 5_000)
        host.log("场景助手：开始「${session?.scene?.name}」")
        show()
    }

    /** 眼镜切走 = 这场结束：存下来（有内容才存）。 */
    override fun onClose() {
        running = false
        seg?.flush(); seg = null
        env.setMic(false)
        env.takeKeys(false)
        host.main.removeCallbacks(watch)
        askSerial++
        val s = session
        session = null
        if (s != null) {
            s.end = System.currentTimeMillis()
            // 还在转的几句：转完再存一次（pump 里会存）
            if (s.lines.isNotEmpty() || s.asks.isNotEmpty()) env.save(s)
            host.log("场景助手：结束「${s.scene.name}」，${s.lines.size} 句")
            ending = s
        }
        host.changed()
    }

    /** 刚结束、还有几句在转的那场（转完补存）。 */
    private var ending: AssistSession? = null

    private val watch: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (System.currentTimeMillis() - lastFrameAt > 5_000) env.setMic(true)
            host.main.postDelayed(this, 5_000)
        }
    }

    override fun onResume() { show() }

    /** 眼镜麦克风的声音（主线程）。 */
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

    private fun pump() {
        if (busy) return
        val p = finals.removeFirstOrNull()
        if (p == null) { waitingKind?.let { waitingKind = null; askNow(it) }; return }
        if (!env.asrReady()) { finals.clear(); error = "离线转文字模型还没下载（「全天记忆 › 设置 › 转文字」）"; show(); return }
        busy = true
        val target = session ?: ending
        val lang = target?.scene?.lang ?: "auto"
        asrExec.execute {
            val r = runCatching {
                // 分人用原始音量（我近、对方远）；转文字前把音量拉齐，轻声的句子也认得出
                val voice = Speakers.voiceOf(Speakers.frames(p), 0, p.size / 160)
                val loud = AudioClean.process(p, AudioClean.Mode.NONE, floor = 0.2f, boost = true)
                LocalAsr.transcribe(loud, if (lang == "auto") "" else lang).trim() to voice
            }
            host.main.post {
                busy = false
                r.onSuccess { (text, voice) ->
                    if (text.isNotEmpty() && target != null) {
                        target.lines.add(AssistLine(System.currentTimeMillis(), env.isMe(voice), text, voice))
                        if (target === ending || target.lines.size % 5 == 0) env.save(target)
                    }
                    if (error.startsWith("转文字")) error = ""
                }.onFailure { error = "转文字出错：${it.message ?: it}"; host.log("场景助手：$error") }
                show()
                pump()
            }
        }
    }

    /** 要建议：kind = answer 参考回答 / question 提问建议。正在说的那句先当作说完，转完再问。 */
    fun ask(kind: String) {
        if (!running) return
        // 同一个键再按一次：收起（回到只听）
        if (view != View.LISTEN && showKind == kind) { back(); return }
        askSerial++
        showKind = kind; showText = ""; page = 0
        view = View.THINKING
        seg?.flush()
        if (busy || finals.isNotEmpty()) { waitingKind = kind; show(); return }
        askNow(kind)
    }

    private fun askNow(kind: String) {
        val s = session ?: return
        val serial = ++askSerial
        val cfg = env.llm()
        val (provider, base, key, model) = cfg
        val transcript = AssistAi.transcript(s.lines, MAX_LIVE_CHARS)
        val system = AssistAi.liveSystem(s.scene)
        val user = AssistAi.liveUser(kind, s.scene, transcript, AssistAi.lastFromOther(s.lines))
        val t0 = System.currentTimeMillis()
        show()
        llmExec.execute {
            val r = runCatching { AssistAi.chat(provider, base, key, model, system, user, LIVE_MAX_TOKENS, LIVE_TIMEOUT_MS) }
            host.main.post {
                if (serial != askSerial || !running) return@post
                val ms = System.currentTimeMillis() - t0
                r.onSuccess { text ->
                    error = ""
                    showText = text; page = 0; view = View.SHOW
                    s.asks.add(AssistAsk(System.currentTimeMillis(), kind, text))
                    env.save(s)
                    host.log("场景助手：${if (kind == "question") "提问建议" else "参考回答"}（$model，${ms} ms，${text.length} 字）")
                }.onFailure {
                    error = it.message ?: it.javaClass.simpleName
                    view = View.LISTEN
                    host.log("场景助手：要建议出错（${ms} ms）：$error")
                }
                show()
            }
        }
    }

    /** 回到只听不说（收起建议）。 */
    fun back() { askSerial++; waitingKind = null; view = View.LISTEN; show() }

    fun flip(d: Int) {
        if (view != View.SHOW) return
        val n = textPages(textCols, textRows).size
        val p = (page + d).coerceIn(0, maxOf(0, n - 1))
        if (p != page) { page = p; show() }
    }

    /** 遥控（戒指、耳机、音量键、手机上的按钮）：answer / question / next / prev / back。 */
    fun remote(action: String) {
        when (action) {
            "answer", "question" -> ask(action)
            "next" -> flip(1)
            "prev" -> flip(-1)
            "back" -> back()
        }
    }

    /** 在眼镜上：右单击参考回答，左单击提问建议；双击翻页（戒指、耳机、音量键也能翻）。 */
    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_RIGHT -> ask("answer")
            GlassesInput.CLICK_LEFT -> ask("question")
            GlassesInput.DOUBLE_RIGHT -> flip(1)
            GlassesInput.DOUBLE_LEFT -> flip(-1)
            else -> {}
        }
    }

    private fun show() { host.redraw(this); host.changed() }

    override fun status() = (session?.scene?.name?.let { "$title · $it" } ?: title) + when {
        view == View.THINKING -> " · 想建议中"
        hearing -> " · 在听"
        else -> ""
    }

    /** 提词器一屏的字数、行数（最近一次显示时用的）。 */
    private var textCols = 27
    private var textRows = 5

    /** 按提词器的字数折行：中文、全角算 1 格，ASCII 算半格（和阅读排版一样）。 */
    fun wrap(s: String, cols: Int): List<String> {
        val cap = cols.coerceIn(4, 60) * 2
        val out = ArrayList<String>()
        for (para in s.lines()) {
            val p = para.trim()
            if (p.isEmpty()) continue
            val sb = StringBuilder(); var w = 0
            for (c in p) {
                val cw = if (c.code < 0x80) 1 else 2
                if (w + cw > cap && sb.isNotEmpty() && !(c in "，。！？；：、）》」』”’…—,.!?;:)" && w + cw <= cap + 2)) {
                    out.add(sb.toString()); sb.setLength(0); w = 0
                }
                sb.append(c); w += cw
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
        }
        return out
    }

    /** 建议按提词器分页（第一行留给「还在听」的那一行，见 liveRow）。 */
    fun textPages(cols: Int, rows: Int): List<List<String>> {
        val r = wrap(showText, cols)
        return if (r.isEmpty()) listOf(emptyList()) else r.chunked(maxOf(1, rows - 1))
    }

    /**
     * 显示建议时最上面一行：一直接着显示听到的最新一句（太长只留末尾），我说的标【我】；有人正在说时前面加 ●。
     * （2026-10-23：建议一出来就看不到对方在说什么了。）
     */
    fun liveRow(cols: Int): String {
        val l = session?.lines?.lastOrNull()
        val t = (if (hearing) "● " else "") + (l?.let { (if (it.me == true) AssistLine.ME_TAG else "") + it.text.trim() } ?: "（还没听到）")
        val cap = cols.coerceIn(4, 60) * 2
        var w = 0; var i = t.length
        while (i > 0) { val cw = if (t[i - 1].code < 0x80) 1 else 2; if (w + cw > cap) break; w += cw; i-- }
        return if (i == 0) t else "…" + t.substring(i + 1)
    }

    /** 眼镜上用提词器文字显示：顶部状态行写场景和状态，正文是最近几句（我说的标【我】）或建议的一页。 */
    override fun glassesText(cols: Int, rows: Int): Pair<String, String> {
        textCols = cols; textRows = rows
        val name = session?.scene?.name ?: env.scene().name
        val ear = when {
            error.isNotEmpty() && view != View.SHOW -> "出错"
            hearing -> "● 在听"
            transcribing -> "转写中"
            else -> "在听"
        }
        return when (view) {
            View.SHOW -> {
                val ps = textPages(cols, rows)
                val p = page.coerceIn(0, ps.size - 1)
                (listOf(liveRow(cols)) + ps[p]).joinToString("\n") to ((if (showKind == "question") "提问建议" else "参考回答") +
                    (if (ps.size > 1) " ${p + 1}/${ps.size}" else "") + " · $ear")
            }
            View.THINKING -> {
                val head = if (showKind == "question") "正在想可以问的问题…" else "正在想参考回答…"
                (listOf(liveRow(cols), head)).joinToString("\n") to "$name · $ear"
            }
            View.LISTEN -> {
                val rowsOut = ArrayList<String>()
                if (error.isNotEmpty()) rowsOut.addAll(wrap(error, cols))
                val ls = session?.lines.orEmpty()
                if (ls.isEmpty() && error.isEmpty()) rowsOut.addAll(wrap("在听对话。右镜腿：参考回答　左镜腿：提问建议", cols))
                // 从最新的一句往前，放满一屏
                val recent = ArrayList<String>()
                for (l in ls.asReversed()) {
                    val w = wrap((if (l.me == true) AssistLine.ME_TAG else "") + l.text, cols)
                    recent.addAll(0, w)
                    if (recent.size + rowsOut.size >= rows) break
                }
                (rowsOut + recent).takeLast(rows).joinToString("\n") to "$name · $ear"
            }
        }
    }

    /** 建议分页（按眼镜字号排版）。 */
    fun pages(px: Int): List<List<String>> {
        val f = Frame(px)
        val top = 30
        val lineH = f.font.height + 5
        val per = maxOf(1, (f.h - top - 2) / lineH)
        val rows = showText.lines().flatMap { l -> if (l.isBlank()) emptyList() else f.font.wrap(l.trim(), f.w - 12) }
        return if (rows.isEmpty()) listOf(emptyList()) else rows.chunked(per)
    }

    override fun render(fontPx: Int): ByteArray = draw(fontPx).done()

    fun draw(px: Int): Frame {
        fontPx = px
        val f = Frame(px)
        val name = session?.scene?.name ?: env.scene().name
        val ear = when {
            error.isNotEmpty() && view != View.SHOW -> "出错"
            hearing -> "● 在听"
            transcribing -> "转写中"
            else -> "在听"
        }
        val lineH = f.font.height + 5
        when (view) {
            View.SHOW -> {
                val ps = pages(px)
                val p = page.coerceIn(0, ps.size - 1)
                val top = f.header((if (showKind == "question") "提问建议" else "参考回答") + " · $name",
                    (if (ps.size > 1) "${p + 1}/${ps.size}  " else "") + ear)
                var y = top + 2
                for (r in ps[p]) { f.text(r, 6, y); y += lineH }
            }
            View.THINKING -> {
                val top = f.header(name, ear)
                f.text(if (showKind == "question") "正在想可以问的问题…" else "正在想参考回答…", 6, top + 4)
                lastOther()?.let { t -> f.font.wrap("对方：$t", f.w - 12).takeLast(2).forEachIndexed { i, r -> f.text(r, 6, top + 4 + lineH * (i + 1), Frame.DIM) } }
            }
            View.LISTEN -> {
                val top = f.header(name, ear)
                val hint = "右：参考回答　左：提问"
                val bottom = f.h - f.small.height - 4
                f.text(hint, f.w / 2, bottom, Frame.DIM, f.small, Frame.CENTER)
                val msg = error.ifEmpty { if (session?.lines.isNullOrEmpty()) "在听对话。需要时按镜腿要建议" else "" }
                val rows = ArrayList<Pair<String, Int>>()
                if (msg.isNotEmpty()) f.font.wrap(msg, f.w - 12).asReversed().forEach { rows.add(it to Frame.DIM) }
                val ls = session?.lines.orEmpty()
                for ((k, l) in ls.withIndex().reversed()) {
                    val who = if (l.me == true) AssistLine.ME_TAG else ""
                    val c = if (k == ls.size - 1 && msg.isEmpty()) Frame.FULL else Frame.DIM
                    for (w in f.font.wrap(who + l.text, f.w - 12).asReversed()) rows.add(w to c)
                    if (rows.size * lineH > bottom - top) break
                }
                var y = bottom - 4 - lineH
                for ((t, c) in rows) { if (y < top) break; f.text(t, 6, y, c); y -= lineH }
            }
        }
        return f
    }

    private fun lastOther(): String? = session?.lines?.lastOrNull { it.me != true }?.text

    companion object {
        /**
         * 按键 → 操作：戒指下键参考回答、上键提问建议，左右键、耳机上一曲 / 下一曲、音量键翻页（音量 − 下一页），
         * 耳机的播放 / 暂停收起建议。不认识的键返回 null。
         */
        fun keyAction(code: Int): String? = when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "answer"
            android.view.KeyEvent.KEYCODE_DPAD_UP -> "question"
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, android.view.KeyEvent.KEYCODE_SPACE, android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "next"
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND, android.view.KeyEvent.KEYCODE_VOLUME_UP -> "prev"
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_HEADSETHOOK,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "back"
            else -> null
        }

        fun actionName(a: String) = when (a) {
            "answer" -> "参考回答"; "question" -> "提问建议"; "next" -> "下一页"; "prev" -> "上一页"; "back" -> "收起建议"; else -> a
        }

        /** 现场交给大模型的对话最多多少字（最近的）。 */
        const val MAX_LIVE_CHARS = 6000
        const val LIVE_MAX_TOKENS = 2000
        const val LIVE_TIMEOUT_MS = 40_000
    }
}
