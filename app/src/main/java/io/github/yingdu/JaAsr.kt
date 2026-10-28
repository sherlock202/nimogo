package io.github.yingdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * 日语离线转文字（NimoGo，2026-10-28）：ReazonSpeech 的 Zipformer（k2，约 3.5 万小时日语电视节目训练，只认日语），
 * 用 sherpa-onnx 在手机上跑。SenseVoice 的日语很差（训练数据以中英为主），日语场景走离线时改用这个。
 * 只下载要用的 4 个文件（int8，约 162 MB）：先从 Hugging Face（连不上换 hf-mirror.com），
 * 都不行再从 sherpa-onnx 的 GitHub 发布页下完整的压缩包（约 700 MB，只解出要用的文件）。
 * 输出没有标点；对方日语里夹的英文词会写成片假名。
 */
object JaAsr {
    const val NAME = "ReazonSpeech（日语）"
    const val MB = 162
    val FILES = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")
    private const val HF_PATH = "reazon-research/reazonspeech-k2-v2/resolve/main"
    private val HOSTS = listOf("https://huggingface.co", "https://hf-mirror.com")
    private const val TARBALL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2"

    lateinit var appCtx: Context
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()
    private var rec: com.k2fsa.sherpa.onnx.OfflineRecognizer? = null
    @Volatile var downloading = false; private set
    @Volatile var progress = ""; private set
    @Volatile var lastError = ""; private set
    var onChanged: (() -> Unit)? = null
    var log: ((String) -> Unit)? = null

    fun folder(ctx: Context) = File(ctx.filesDir, "asr/JA_REAZON")
    fun ready() = ::appCtx.isInitialized && File(folder(appCtx), ".ok").isFile && FILES.all { File(folder(appCtx), it).isFile }
    fun sizeMb(): Long = if (!::appCtx.isInitialized) 0 else (folder(appCtx).listFiles()?.sumOf { it.length() } ?: 0) / 1_000_000

    fun startDownload() {
        if (downloading || ready() || !::appCtx.isInitialized) return
        downloading = true; lastError = ""; progress = "开始下载…"
        main.post { onChanged?.invoke() }
        Thread {
            val r = runCatching { download(folder(appCtx)) }
            r.onFailure { lastError = "下载失败：${it.message ?: it.javaClass.simpleName}"; say(lastError) }
            downloading = false
            main.post { onChanged?.invoke() }
        }.apply { name = "ja-asr-download" }.start()
    }

    /** 删掉模型（腾出空间）。 */
    fun delete() {
        synchronized(lock) { runCatching { rec?.release() }; rec = null }
        if (::appCtx.isInitialized) folder(appCtx).deleteRecursively()
        lastError = ""
        main.post { onChanged?.invoke() }
    }

    private fun say(m: String) = main.post { log?.invoke("日语离线模型：$m") }

    private fun download(dir: File) {
        dir.mkdirs()
        File(dir, ".ok").delete()
        val t0 = System.currentTimeMillis()
        val fromHf = runCatching {
            FILES.forEachIndexed { i, f ->
                val out = File(dir, f)
                if (out.isFile && out.length() > 0) return@forEachIndexed       // 上次下好的不再下
                var err: Throwable? = null
                for (h in HOSTS) {
                    val r = runCatching { fetch("$h/$HF_PATH/$f", out) { d, t -> progress = "${i + 1}/${FILES.size} · ${d / 1_000_000} / ${if (t > 0) t / 1_000_000 else "?"} MB" } }
                    if (r.isSuccess) { err = null; break }
                    err = r.exceptionOrNull(); say("$h 下载 $f 失败：${err?.message}")
                }
                err?.let { throw it }
            }
        }
        if (fromHf.isFailure) {
            say("Hugging Face 下不了，改从 GitHub 下完整的压缩包（约 700 MB）")
            fromTarball(dir)
        }
        check()
        File(dir, ".ok").writeText("reazonspeech-k2-v2\n")
        say("下载完成（约 ${sizeMb()} MB），用了 ${(System.currentTimeMillis() - t0) / 1000} 秒")
    }

    /** 下好的文件大小对不对（防止下到错误页）。 */
    private fun check() {
        val d = folder(appCtx)
        val enc = File(d, FILES[0]).length()
        val tok = File(d, "tokens.txt").length()
        if (enc < 50_000_000 || tok < 1_000) {
            FILES.forEach { File(d, it).delete() }
            throw IOException("下载的文件不完整（encoder ${enc / 1_000_000} MB），请重试")
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "NimoGo")
        val code = c.responseCode
        if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code") }
        return c
    }

    private fun fetch(url: String, out: File, report: (Long, Long) -> Unit) {
        val c = open(url)
        try {
            val total = c.contentLengthLong
            val tmp = File(out.parentFile, out.name + ".part")
            var done = 0L; var last = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 300) { last = now; report(done, total); main.post { onChanged?.invoke() } }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("没下完（$done / $total）")
            if (!tmp.renameTo(out)) { out.delete(); if (!tmp.renameTo(out)) throw IOException("存不了 ${out.name}") }
        } finally { c.disconnect() }
    }

    private fun fromTarball(dir: File) {
        val c = open(TARBALL)
        try {
            val total = c.contentLengthLong
            var done = 0L; var last = 0L
            val counting = object : java.io.FilterInputStream(c.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }
                override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                private fun count(n: Int) {
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - last > 300) { last = now; progress = "完整包 · ${done / 1_000_000} / ${if (total > 0) total / 1_000_000 else "?"} MB"; main.post { onChanged?.invoke() } }
                }
            }
            val tar = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(counting.buffered(1 shl 16)))
            val want = FILES.toSet(); val got = HashSet<String>()
            while (got != want) {
                val e = tar.nextEntry ?: break
                val base = e.name.substringAfterLast('/')
                if (e.isDirectory || base !in want) continue
                val tmp = File(dir, "$base.part")
                tmp.outputStream().use { tar.copyTo(it, 1 shl 16) }
                File(dir, base).delete(); tmp.renameTo(File(dir, base))
                got.add(base)
            }
            tar.close()
            if (got != want) throw IOException("压缩包里缺少：${(want - got).joinToString()}")
        } finally { c.disconnect() }
    }

    private val releaseLater = Runnable { synchronized(lock) { runCatching { rec?.release() }; rec = null } }

    /** 16 kHz 单声道 PCM → 日语文字。阻塞，后台线程调用。 */
    fun transcribe(pcm: ShortArray): String {
        if (!ready()) throw IOException("日语离线模型还没下载")
        synchronized(lock) {
            main.removeCallbacks(releaseLater)
            var r = rec
            if (r == null) {
                val t0 = System.currentTimeMillis()
                val d = folder(appCtx).absolutePath
                val threads = if (Runtime.getRuntime().availableProcessors() >= 6) 2 else 1
                val cfg = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                    featConfig = FeatureConfig(16_000, 80),
                    modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                        transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                            encoder = "$d/${FILES[0]}", decoder = "$d/${FILES[1]}", joiner = "$d/${FILES[2]}"),
                        tokens = "$d/tokens.txt", numThreads = threads, modelType = "transducer"))
                r = com.k2fsa.sherpa.onnx.OfflineRecognizer(null, cfg); rec = r
                val ms = System.currentTimeMillis() - t0
                say("模型加载用了 $ms ms")
            }
            val f = FloatArray(pcm.size) { pcm[it] / 32768f }
            val s = r.createStream()
            try {
                s.acceptWaveform(f, 16_000)
                r.decode(s)
                return r.getResult(s).text.trim()
            } finally {
                s.release()
                main.postDelayed(releaseLater, 180_000)
            }
        }
    }
}
