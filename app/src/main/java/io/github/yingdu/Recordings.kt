package io.github.yingdu

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import org.json.JSONObject
import android.os.Handler
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 一条存好的录音。声音是 Ogg Opus：手机这边压到 8 kb/s（大约每小时 4MB），
 * 眼镜那边沿用眼镜自己发来的 Opus 包（大约每小时 7MB）。转文字是录完再做的。
 */
class RecNote(
    val file: File,
    val source: String,
    val started: Long,
    val durationMs: Long,
    val text: String,
) {
    val id get() = file.nameWithoutExtension
    val bytes get() = file.length()
}

/**
 * 录音：手机麦克风或眼镜麦克风，二选一。录完把文件留在手机上，需要时再转文字。
 */
class Tape(
    private val dir: File,
    private val main: Handler,
    private val log: (String) -> Unit,
    private val changed: () -> Unit,
    private val glassesReady: () -> Boolean,
    private val setGlassesMic: (Boolean) -> Unit,
    private val phoneForeground: (Boolean) -> Unit,
    /** 把一段 16 kHz 单声道 PCM 转成文字（在后台线程里调用，会堵住直到转完）。 */
    private val transcribePcm: (ShortArray) -> String,
) {
    var recording = false; private set
    var source = "phone"
    var error = ""
    /** 正在转文字的那条（文件名，不含扩展名）。 */
    var busyId = ""
    var startedAt = 0L; private set

    private val work = Executors.newSingleThreadExecutor()
    private var writer: OggOpusWriter? = null
    private var phone: AudioRecord? = null
    @Volatile private var phoneStop = false
    private var phoneThread: Thread? = null
    private var glassesPackets = 0
    private var phoneSamples = 0
    private var active: File? = null

    val elapsedMs: Long get() = if (!recording) 0 else System.currentTimeMillis() - startedAt

    fun list(): List<RecNote> {
        dir.mkdirs()
        val live = active?.name
        return (dir.listFiles { f -> f.name.endsWith(".ogg") } ?: emptyArray())
            .filter { it.name != live }
            .map { noteOf(it) }
            .sortedByDescending { it.started }
    }

    fun start(which: String): String? {
        if (recording) return "已经在录"
        source = which
        error = ""
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "$stamp.ogg")
        return if (which == "glasses") startGlasses(file) else startPhone(file)
    }

    fun stop() {
        if (!recording) return
        val file = active
        val src = source
        val started = startedAt
        val ms = if (src == "glasses") glassesPackets * 10L else phoneSamples * 1000L / NimoMic.SAMPLE_RATE
        if (src == "glasses") {
            setGlassesMic(false)
            runCatching { writer?.close() }
            writer = null
        } else {
            phoneStop = true
            runCatching { phone?.stop() }
            runCatching { phoneThread?.join(2_000) }
            phoneThread = null
            phoneForeground(false)
        }
        recording = false
        active = null
        if (file != null && file.isFile && file.length() > 80 && ms >= 400) {
            writeMeta(file, src, started, ms, "")
            log("录音：存好 ${file.name}（${if (src == "glasses") "眼镜" else "手机"}，${ms / 1000} 秒，${file.length() / 1024} KB）")
        } else {
            file?.delete()
            log("录音：太短，没有保存")
        }
        changed()
    }

    fun delete(id: String) {
        File(dir, "$id.ogg").delete()
        File(dir, "$id.json").delete()
        changed()
    }

    /** 录完再转。长的按 40 秒一段，转完接在一起。 */
    fun transcribe(id: String) {
        if (busyId.isNotEmpty()) return
        val file = File(dir, "$id.ogg")
        if (!file.isFile) return
        busyId = id
        error = ""
        changed()
        work.execute {
            val r = runCatching {
                val pcm = OggOpus.toPcm(file.readBytes())
                if (pcm.isEmpty()) throw IllegalStateException("录音是空的")
                val step = NimoMic.SAMPLE_RATE * 40
                val parts = ArrayList<String>()
                var i = 0
                while (i < pcm.size) {
                    val n = minOf(step, pcm.size - i)
                    val piece = if (i == 0 && n == pcm.size) pcm else pcm.copyOfRange(i, i + n)
                    val t = transcribePcm(piece).trim()
                    if (t.isNotEmpty()) parts.add(t)
                    i += n
                }
                parts.joinToString("\n")
            }
            val text = r.getOrElse {
                val msg = it.message ?: it.javaClass.simpleName
                main.post { log("录音：转文字失败：$msg") }
                "转文字出错：$msg"
            }
            val note = noteOf(file)
            if (r.isSuccess) writeMeta(file, note.source, note.started, note.durationMs, text)
            main.post {
                busyId = ""
                if (r.isFailure) error = text
                log(if (r.isSuccess) "录音：转好「${file.nameWithoutExtension}」，${text.length} 字" else "录音：没转成")
                changed()
            }
        }
    }

    fun onGlasses(frames: List<NimoMic.Frame>) {
        val w = writer ?: return
        for (f in frames) {
            w.add(f.packet)
            glassesPackets++
        }
    }

    private fun startGlasses(file: File): String? {
        if (!glassesReady()) return "先连上眼镜"
        glassesPackets = 0
        writer = OggOpusWriter(FileOutputStream(file))
        active = file
        startedAt = System.currentTimeMillis()
        recording = true
        setGlassesMic(true)
        log("录音：开始（眼镜）")
        changed()
        return null
    }

    private fun startPhone(file: File): String? {
        val min = AudioRecord.getMinBufferSize(NimoMic.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return "这台手机开不了麦克风"
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, NimoMic.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, NimoMic.SAMPLE_RATE))
        } catch (e: SecurityException) {
            return "没有麦克风权限"
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return "麦克风没打开"
        }
        phoneForeground(true)
        try { ar.startRecording() } catch (e: Exception) {
            ar.release()
            phoneForeground(false)
            return "麦克风没打开：${e.message}"
        }
        phone = ar
        phoneStop = false
        phoneSamples = 0
        val w = OggOpusWriter(FileOutputStream(file))
        writer = w
        active = file
        startedAt = System.currentTimeMillis()
        recording = true
        val thread = Thread({ phoneLoop(ar, w) }, "nimogo-rec")
        phoneThread = thread
        thread.start()
        log("录音：开始（手机，8 kb/s）")
        changed()
        return null
    }

    /** 16 kHz、60 ms 一帧，压成 8 kb/s 的语音 Opus。 */
    private fun phoneLoop(ar: AudioRecord, w: OggOpusWriter) {
        val frame = 960
        val pending = ShortArray(frame * 2)
        var have = 0
        val buf = ShortArray(frame)
        val packet = ByteArray(256)
        val enc = OpusEncoder(NimoMic.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        enc.bitrate = 8_000
        enc.complexity = 2
        enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        try {
            while (!phoneStop) {
                val n = ar.read(buf, 0, frame)
                if (n <= 0) break
                System.arraycopy(buf, 0, pending, have, n)
                have += n
                while (have >= frame) {
                    val len = enc.encode(pending, 0, frame, packet, 0, packet.size)
                    if (len > 0) w.add(packet.copyOf(len), frame * 3)
                    phoneSamples += frame
                    have -= frame
                    if (have > 0) System.arraycopy(pending, frame, pending, 0, have)
                }
            }
        } catch (e: Exception) {
            main.post { log("录音：手机收音中断 ${e.message}") }
        } finally {
            runCatching { w.close() }
            if (writer === w) writer = null
            runCatching { ar.release() }
            if (phone === ar) phone = null
        }
    }

    private fun noteOf(file: File): RecNote {
        val j = runCatching { JSONObject(File(dir, file.nameWithoutExtension + ".json").readText(Charsets.UTF_8)) }.getOrNull()
        val dur = j?.optLong("durationMs") ?: 0L
        val fixed = if (dur > 0) dur else OggOpus.packets(file.readBytes()).size * 10L
        return RecNote(
            file,
            j?.optString("source").orEmpty().ifEmpty { "phone" },
            j?.optLong("started")?.takeIf { it > 0 } ?: file.lastModified(),
            fixed,
            j?.optString("text").orEmpty(),
        )
    }

    private fun writeMeta(file: File, source: String, started: Long, durationMs: Long, text: String) {
        File(dir, file.nameWithoutExtension + ".json").writeText(
            JSONObject().put("source", source).put("started", started).put("durationMs", durationMs).put("text", text).toString(),
            Charsets.UTF_8,
        )
    }
}
