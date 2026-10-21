package io.github.yingdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 语音跟读用的离线识别模型（sherpa-onnx，Apache-2.0）。第一次用时从 sherpa-onnx 的 GitHub 发布页下载 .tar.bz2，
 * 边下边解压，只留需要的文件，放在 files/asr/<名字>/。识别全在手机上做，不联网。
 */
enum class FollowModel(val zh: String, val mb: Int, val archive: String, val files: List<String>, val streaming: Boolean = true) {
    ZH_SMALL("中文（小，约 21 MB）", 21, "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
        listOf("model.int8.onnx", "tokens.txt")),
    ZH_EN("中英（大，约 134 MB）", 134, "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05",
        listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")),
    /** 非流式（一句说完再转）：全天记忆、实时字幕的「手机离线」用。中、英、日、韩、粤语。 */
    SENSE_VOICE("中日英 SenseVoice（约 166 MB，和「手机离线」转文字共用）", 166, "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
        listOf("model.int8.onnx", "tokens.txt"), streaming = false);

    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive.tar.bz2"
    fun folder(ctx: Context) = File(ctx.filesDir, "asr/$name")
    fun ready(ctx: Context) = File(folder(ctx), ".ok").isFile && files.all { File(folder(ctx), it).isFile }

    fun config(ctx: Context, threads: Int): OnlineRecognizerConfig {
        val d = folder(ctx).absolutePath
        val model = when (this) {
            ZH_SMALL -> OnlineModelConfig(zipformer2Ctc = OnlineZipformer2CtcModelConfig("$d/model.int8.onnx"),
                tokens = "$d/tokens.txt", numThreads = threads)
            ZH_EN -> OnlineModelConfig(transducer = OnlineTransducerModelConfig("$d/encoder.int8.onnx", "$d/decoder.onnx", "$d/joiner.int8.onnx"),
                tokens = "$d/tokens.txt", numThreads = threads)
            SENSE_VOICE -> throw IllegalStateException("SenseVoice 不是流式模型")
        }
        return OnlineRecognizerConfig(featConfig = FeatureConfig(16_000, 80), modelConfig = model, enableEndpoint = true)
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

/** 跟读用的识别器（流式模型，或者 SenseVoice 滑动窗口）。 */
interface FollowRecognizer {
    val loaded: Boolean
    val totalAudioMs: Long
    val totalDecodeMs: Long
    fun start()
    fun feed(samples: FloatArray, receivedAt: Long)
    fun stop()
}

/**
 * 用 SenseVoice（非流式，和「手机离线」转文字是同一个模型）跟读：一直留着最近 4 秒的声音，
 * 有人在说话时每 0.5 秒把这 4 秒重新识别一遍（滑动窗口），结果交给对齐。
 * 每次都要重算 4 秒，比流式模型费电；识别多快全写进日志。
 */
class FollowSenseAsr(
    private val log: FollowLog,
    private val onText: (text: String, final: Boolean, audioAt: Long, decodeMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) : FollowRecognizer {
    companion object {
        const val WINDOW = 16_000 * 4      // 4 秒
        const val STEP = 16_000 / 2        // 每 0.5 秒识别一次
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

    override fun stop() { exec.shutdown() }
}

/**
 * 离线流式识别：眼镜麦克风的声音（16 kHz）一段一段送进来，后台线程解码，文字有变化就回调（主线程）。
 * 记下每段声音收到的时间，回调时带上「这段文字对应的声音是什么时候收到的」，用来算识别延迟。
 */
class FollowAsr(
    private val ctx: Context,
    private val model: FollowModel,
    private val log: FollowLog,
    private val onText: (text: String, final: Boolean, audioAt: Long, decodeMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) : FollowRecognizer {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "follow-asr").apply { priority = Thread.MAX_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private var rec: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    @Volatile private var queued = 0
    @Volatile override var loaded = false; private set
    private var lastText = ""
    private var segments = 0

    // 统计（每 5 秒写一次日志）
    private var statAudioMs = 0L
    private var statDecodeMs = 0L
    private var statMaxQueue = 0
    private var statAt = System.currentTimeMillis()
    override var totalAudioMs = 0L; private set
    override var totalDecodeMs = 0L; private set

    override fun start() = exec.execute {
        val t0 = System.currentTimeMillis()
        try {
            val threads = if (Runtime.getRuntime().availableProcessors() >= 6) 2 else 1
            rec = OnlineRecognizer(null, model.config(ctx, threads))
            stream = rec!!.createStream()
            loaded = true
            log.d("识别", "模型 ${model.name} 加载完成，用了 ${System.currentTimeMillis() - t0} ms，线程 $threads，CPU 核数 ${Runtime.getRuntime().availableProcessors()}")
        } catch (e: Throwable) {
            log.d("错误", "模型加载失败：$e")
            main.post { onError("识别模型加载失败：${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /** 送一段声音（-1..1 的浮点，16 kHz）。receivedAt：这段声音最后一帧从蓝牙收到的时间。 */
    override fun feed(samples: FloatArray, receivedAt: Long) {
        queued++
        if (queued > statMaxQueue) statMaxQueue = queued
        exec.execute {
            queued--
            val r = rec ?: return@execute
            val s = stream ?: return@execute
            val t0 = System.nanoTime()
            try {
                s.acceptWaveform(samples, 16_000)
                while (r.isReady(s)) r.decode(s)
                val text = r.getResult(s).text.trim()
                val endpoint = r.isEndpoint(s)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val audioMs = samples.size * 1000L / 16_000
                statAudioMs += audioMs; statDecodeMs += ms; totalAudioMs += audioMs; totalDecodeMs += ms
                if (text != lastText) {
                    lastText = text
                    main.post { onText(text, false, receivedAt, ms) }
                }
                if (endpoint) {
                    if (text.isNotEmpty()) {
                        segments++
                        val t = text
                        main.post { onText(t, true, receivedAt, ms) }
                    }
                    r.reset(s); lastText = ""
                }
                val now = System.currentTimeMillis()
                if (now - statAt >= 5_000) {
                    log.d("识别", "最近 ${(now - statAt) / 1000} 秒：声音 ${statAudioMs} ms，解码 ${statDecodeMs} ms（实时率 %.2f），最多排队 $statMaxQueue 段，已识别 $segments 句".format(
                        if (statAudioMs > 0) statDecodeMs.toDouble() / statAudioMs else 0.0))
                    statAt = now; statAudioMs = 0; statDecodeMs = 0; statMaxQueue = queued
                }
            } catch (e: Throwable) {
                log.d("错误", "识别出错：$e")
            }
        }
    }

    override fun stop() {
        exec.execute {
            runCatching { stream?.release(); rec?.release() }
            stream = null; rec = null; loaded = false
        }
        exec.shutdown()
    }
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
    fun transcribe(pcm: ShortArray, language: String): String {
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
                return r.getResult(s).text.replace(Regex("<\\|[^|>]*\\|>"), "").trim()
            } finally {
                s.release()
                main.postDelayed(releaseLater, 180_000)
            }
        }
    }
}
