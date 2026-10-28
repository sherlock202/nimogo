package io.github.yingdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 离线识别模型（sherpa-onnx，Apache-2.0）。第一次用时从 sherpa-onnx 的 GitHub 发布页下载 .tar.bz2，
 * 边下边解压，只留需要的文件，放在 files/asr/<名字>/。识别全在手机上做，不联网。
 */
enum class FollowModel(val zh: String, val mb: Int, val archive: String, val files: List<String>) {
    /** 非流式（一段声音整段转）：语音跟读、全天记忆和实时字幕的「手机离线」共用。中、英、日、韩、粤语。 */
    SENSE_VOICE("SenseVoice（中日英，约 166 MB）", 166, "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
        listOf("model.int8.onnx", "tokens.txt"));

    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive.tar.bz2"
    fun folder(ctx: Context) = File(ctx.filesDir, "asr/$name")
    fun ready(ctx: Context) = File(folder(ctx), ".ok").isFile && files.all { File(folder(ctx), it).isFile }

    companion object {
        /** 测试版用过的流式模型（2026-10-21 实测后统一成 SenseVoice）：删掉，腾出空间。 */
        fun cleanupOld(ctx: Context) {
            for (old in listOf("ZH_SMALL", "ZH_EN")) File(ctx.filesDir, "asr/$old").takeIf { it.exists() }?.deleteRecursively()
        }
    }

    /** 下载并解压（阻塞，后台线程调用）。progress(已下载字节, 总字节)；cancel 返回 true 时中止。 */
    fun download(ctx: Context, log: (String) -> Unit, progress: (Long, Long) -> Unit, cancel: () -> Boolean) {
        val dir = folder(ctx)
        dir.mkdirs()
        File(dir, ".ok").delete()
        val t0 = System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("下载失败：HTTP $code")
            val total = c.contentLengthLong
            log("下载模型 $name：$url（${if (total > 0) total / 1_000_000 else "?"} MB）")
            var done = 0L
            var lastReport = 0L
            val counting = object : java.io.FilterInputStream(c.inputStream) {
                override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }
                private fun count(n: Int) {
                    done += n
                    if (cancel()) throw java.io.InterruptedIOException("取消了")
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 300) { lastReport = now; progress(done, total) }
                }
            }
            val bz = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(counting.buffered(1 shl 16))
            val tar = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bz)
            val want = files.toSet()
            val got = HashSet<String>()
            while (true) {
                val e = tar.nextEntry ?: break
                val base = e.name.substringAfterLast('/')
                if (e.isDirectory || base !in want) continue
                val tmp = File(dir, "$base.part")
                tmp.outputStream().use { out -> tar.copyTo(out, 1 shl 16) }
                if (!tmp.renameTo(File(dir, base))) { File(dir, base).delete(); tmp.renameTo(File(dir, base)) }
                got.add(base)
                log("解压 $base（${File(dir, base).length() / 1000} KB）")
            }
            tar.close()
            if (got != want) throw java.io.IOException("压缩包里缺少：${(want - got).joinToString()}")
            File(dir, ".ok").writeText("$url\n")
            progress(total.coerceAtLeast(done), total)
            log("模型 $name 下载完成，用了 ${(System.currentTimeMillis() - t0) / 1000} 秒")
        } finally { c.disconnect() }
    }
}

/**
 * 跟读的详细日志：每次开始跟读新建一个文件（files/follow/），时间精确到毫秒；同时在内存里留最近的几千行。
 * 导出时把最新的文件存到「下载」里，方便发给电脑分析。
 */
class FollowLog(private val dir: File) {
    private var writer: java.io.BufferedWriter? = null
    var file: File? = null; private set
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    private var lines = 0

    @Synchronized fun start(header: String) {
        stop()
        dir.mkdirs()
        // 只留最近 20 个
        dir.listFiles()?.sortedBy { it.name }?.dropLast(19)?.forEach { it.delete() }
        val name = "follow-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
        file = File(dir, name)
        writer = file!!.bufferedWriter()
        lines = 0
        d("开始", header)
    }

    @Synchronized fun d(tag: String, msg: String) {
        val w = writer ?: return
        runCatching {
            w.write(fmt.format(java.util.Date())); w.write("  ["); w.write(tag); w.write("] "); w.write(msg); w.newLine()
            if (++lines % 20 == 0) w.flush()
        }
    }

    @Synchronized fun flush() { runCatching { writer?.flush() } }

    @Synchronized fun stop() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
    }

    fun latest(): File? = file?.takeIf { it.isFile } ?: dir.listFiles()?.filter { it.name.startsWith("follow-") }?.maxByOrNull { it.name }
}

/** 跟读用的识别器。 */
interface FollowRecognizer {
    val loaded: Boolean
    val totalAudioMs: Long
    val totalDecodeMs: Long
    fun start()
    fun feed(samples: FloatArray, receivedAt: Long)
    /** 丢掉留着的声音（手动翻页后：之前念的话不要再把位置拉回去）。 */
    fun reset()
    fun stop()
}

/**
 * 用 SenseVoice（非流式，和「手机离线」转文字是同一个模型）跟读：一直留着最近 3 秒的声音，
 * 有人在说话时每 0.3 秒把这 3 秒重新识别一遍（滑动窗口），结果交给对齐。
 * 每次都要重算整个窗口，比流式模型费电；识别跟不上时自动跳过几次；识别多快全写进日志。
 */
class FollowSenseAsr(
    private val log: FollowLog,
    private val onText: (text: String, final: Boolean, audioAt: Long, decodeMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) : FollowRecognizer {
    companion object {
        // 2026-10-21 实测 4 秒窗口识别一次约 200 ms：改成 3 秒窗口、每 0.3 秒一次，新念的字更快出来
        const val WINDOW = 16_000 * 3      // 3 秒
        const val STEP = 16_000 * 3 / 10   // 每 0.3 秒识别一次
        /** 最近这么久没人说话就不识别（省电）。 */
        const val QUIET_MS = 1_200L
    }
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "follow-sense").apply { priority = Thread.MAX_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val ring = ShortArray(WINDOW)
    private var filled = 0
    private var pos = 0
    private var sinceDecode = 0
    private val vad = SpeechDetector()
    private var lastSpeechAt = 0L
    @Volatile private var queued = 0
    @Volatile override var loaded = false; private set
    override var totalAudioMs = 0L; private set
    override var totalDecodeMs = 0L; private set
    private var lastText = ""
    private var statAt = System.currentTimeMillis()
    private var statDecodes = 0
    private var statMs = 0L
    private var statMax = 0L
    private var statSkipped = 0

    override fun start() = exec.execute {
        val t0 = System.currentTimeMillis()
        try {
            LocalAsr.transcribe(ShortArray(8_000), "")      // 先加载模型（第一次要一两秒）
            loaded = true
            log.d("识别", "SenseVoice 加载完成（含一次空识别），用了 ${System.currentTimeMillis() - t0} ms，CPU 核数 ${Runtime.getRuntime().availableProcessors()}；" +
                "窗口 ${WINDOW / 16} ms，每 ${STEP / 16} ms 识别一次")
        } catch (e: Throwable) {
            log.d("错误", "SenseVoice 加载失败：$e")
            main.post { onError("识别模型加载失败：${e.message ?: e.javaClass.simpleName}") }
        }
    }

    override fun feed(samples: FloatArray, receivedAt: Long) {
        queued++
        exec.execute {
            queued--
            val pcm = ShortArray(samples.size) { (samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
            vad.feed(pcm)
            if (vad.speaking) lastSpeechAt = receivedAt
            for (x in pcm) { ring[pos] = x; pos = (pos + 1) % WINDOW }
            filled = minOf(WINDOW, filled + pcm.size)
            sinceDecode += pcm.size
            totalAudioMs += pcm.size * 1000L / 16_000
            if (!loaded || sinceDecode < STEP) return@execute
            sinceDecode = 0
            if (receivedAt - lastSpeechAt > QUIET_MS) return@execute
            if (queued > 1) { statSkipped++; return@execute }      // 识别跟不上：跳过这次，先把积压的声音收进来
            val win = ShortArray(filled)
            val start = (pos - filled + WINDOW) % WINDOW
            for (k in 0 until filled) win[k] = ring[(start + k) % WINDOW]
            val t0 = System.nanoTime()
            val text = try { LocalAsr.transcribe(win, "") } catch (e: Throwable) { log.d("错误", "识别出错：$e"); return@execute }
            val ms = (System.nanoTime() - t0) / 1_000_000
            totalDecodeMs += ms; statDecodes++; statMs += ms; if (ms > statMax) statMax = ms
            if (text != lastText) { lastText = text; main.post { onText(text, false, receivedAt, ms) } }
            val now = System.currentTimeMillis()
            if (now - statAt >= 5_000) {
                log.d("识别", "最近 ${(now - statAt) / 1000} 秒：识别 $statDecodes 次（每次 ${filled / 16} ms 声音），平均 ${if (statDecodes > 0) statMs / statDecodes else 0} ms，最慢 $statMax ms，因为跟不上跳过 $statSkipped 次")
                statAt = now; statDecodes = 0; statMs = 0; statMax = 0; statSkipped = 0
            }
        }
    }

    override fun reset() = exec.execute { filled = 0; sinceDecode = 0; lastText = "" }

    override fun stop() { exec.shutdown() }
}


/**
 * 手机离线转文字（SenseVoice，非流式，一句一句转）：全天记忆、实时字幕的转文字服务选「手机离线」时用。
 * 不联网、不要 Key；模型第一次用时下载（约 166 MB，解压后约 240 MB）。识别器用完 3 分钟后释放，省内存。
 */
object LocalAsr {
    /** 转文字服务的「接口地址」写成这个，就走手机离线。 */
    const val BASE = "local://sensevoice"
    private val model = FollowModel.SENSE_VOICE
    lateinit var appCtx: Context
    // 懒加载：只用 isLocal 判断时不碰 Android 的东西（单元测试里也能用）
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()
    private var rec: com.k2fsa.sherpa.onnx.OfflineRecognizer? = null
    private var recLang = ""
    @Volatile var downloading = false; private set
    @Volatile var progress = ""; private set
    @Volatile var lastError = ""; private set
    var onChanged: (() -> Unit)? = null
    var log: ((String) -> Unit)? = null

    fun isLocal(base: String) = base.startsWith("local://")
    fun ready() = ::appCtx.isInitialized && model.ready(appCtx)
    fun sizeMb(): Long = if (!::appCtx.isInitialized) 0 else (model.folder(appCtx).listFiles()?.sumOf { it.length() } ?: 0) / 1_000_000

    fun startDownload() {
        if (downloading || ready() || !::appCtx.isInitialized) return
        downloading = true; lastError = ""; progress = "开始下载…"
        main.post { onChanged?.invoke() }
        Thread {
            val r = runCatching {
                model.download(appCtx, { m -> main.post { log?.invoke("离线转文字：$m") } },
                    { d, t -> progress = "${d / 1_000_000} / ${if (t > 0) t / 1_000_000 else model.mb} MB"; main.post { onChanged?.invoke() } }, { false })
            }
            r.onFailure { lastError = "下载失败：${it.message ?: it.javaClass.simpleName}"; main.post { log?.invoke("离线转文字：$lastError") } }
            downloading = false
            main.post { onChanged?.invoke() }
        }.apply { name = "local-asr-download" }.start()
    }

    private val releaseLater = Runnable { synchronized(lock) { runCatching { rec?.release() }; rec = null } }

    private fun config(language: String): com.k2fsa.sherpa.onnx.OfflineRecognizerConfig {
        val d = model.folder(appCtx).absolutePath
        val threads = if (Runtime.getRuntime().availableProcessors() >= 6) 2 else 1
        return com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            featConfig = FeatureConfig(16_000, 80),
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                senseVoice = com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig("$d/model.int8.onnx", language, true),
                tokens = "$d/tokens.txt", numThreads = threads))
    }

    /** 16 kHz 单声道 PCM → 文字。language：zh、ja、en、""（自动）。阻塞，后台线程调用；同一时间只转一段。 */
    fun transcribe(pcm: ShortArray, language: String): String = recognize(pcm, language).text

    /** 认出来的文字，和 SenseVoice 给这段声音打的标签（Speech 说话、Laughter 笑、Cry 哭、BGM 音乐……，可能为空）。 */
    class Heard(val text: String, val event: String)

    fun recognize(pcm: ShortArray, language: String): Heard {
        if (!ready()) {
            startDownload()
            throw java.io.IOException(if (downloading) "手机离线转文字的模型正在下载（$progress），下好后自动接着转" else "手机离线转文字的模型还没下载")
        }
        val lang = when (language) { "zh", "ja", "en", "ko", "yue" -> language; else -> "auto" }
        synchronized(lock) {
            main.removeCallbacks(releaseLater)
            val t0 = System.currentTimeMillis()
            var r = rec
            if (r == null) {
                r = com.k2fsa.sherpa.onnx.OfflineRecognizer(null, config(lang)); rec = r; recLang = lang
                val ms = System.currentTimeMillis() - t0
                main.post { log?.invoke("离线转文字：模型加载用了 $ms ms") }
            } else if (recLang != lang) { r.setConfig(config(lang)); recLang = lang }
            val f = FloatArray(pcm.size) { pcm[it] / 32768f }
            val s = r.createStream()
            try {
                s.acceptWaveform(f, 16_000)
                r.decode(s)
                val res = r.getResult(s)
                return Heard(res.text.replace(Regex("<\\|[^|>]*\\|>"), "").trim(), res.event.replace(Regex("[<|>]"), "").trim())
            } finally {
                s.release()
                main.postDelayed(releaseLater, 180_000)
            }
        }
    }
}
