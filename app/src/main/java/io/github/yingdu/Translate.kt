package io.github.yingdu

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/*
 * 即时翻译（NimoGo，2026-10-26）：场景助手里对方说的话，在手机上离线翻成中文（或设置里选的语言），眼镜上原文下面显示译文。
 * 翻译用 Google ML Kit 的离线翻译：第一次联网下载语言包（每种约 30 MB），之后不联网，一句几十毫秒。
 * 这个文件里：猜一句话是什么语言、要不要翻（纯 Kotlin，有单元测试），以及 ML Kit 的封装。
 */

/** 按文字猜语言：有假名就是日语，英文单词比汉字多就是英语，只有汉字时看提示（场景语言是日语就当日语），否则当中文。 */
object LangGuess {
    fun of(text: String, hint: String = ""): String {
        var kana = 0; var han = 0; var hangul = 0; var words = 0
        var inWord = false
        for (c in text) {
            val letter = c in 'a'..'z' || c in 'A'..'Z'
            if (letter && !inWord) words++
            inWord = letter
            when {
                // 平假名、片假名（不算「・」「ー」：中文里偶尔也会出现）、半角片假名
                (c in 'ぁ'..'ヺ' && c != '・') || c in 'ｦ'..'ﾝ' -> kana++
                c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' -> han++
                c in '가'..'힯' || c in 'ᄀ'..'ᇿ' -> hangul++
            }
        }
        return when {
            kana > 0 -> "ja"
            hangul > 0 && hangul >= han -> "ko"
            han == 0 && words == 0 -> ""
            han == 0 -> "en"
            words > han -> "en"
            hint == "ja" -> "ja"
            else -> "zh"
        }
    }
}

/** 要不要翻：分不出语言、本来就是目标语言、语言包里没有、只有一两个字（嗯、はい 之外的有意义的内容才翻）时不翻。 */
object TranslateRules {
    fun wanted(text: String, from: String, to: String, supported: Collection<String> = OfflineTranslate.CODES): Boolean {
        if (from.isEmpty() || from == to || from !in supported || to !in supported) return false
        val core = text.count { it.isLetterOrDigit() }
        return core >= 2
    }
}

/**
 * Google ML Kit 离线翻译。回调都在主线程（ML Kit 的 Task 默认在主线程回调）。
 * 语言包第一次要联网下载（不限 Wi-Fi）；下载好后翻译完全离线。
 */
object OfflineTranslate {
    /** 支持的语言（ML Kit 的语言代码 → 名称）。要加语言包：在这里加一行（ko 韩语、fr 法语……ML Kit 支持 50 多种）。 */
    val LANGS = listOf("zh" to "中文", "ja" to "日语", "en" to "英语")
    val CODES = LANGS.map { it.first }
    fun name(code: String) = LANGS.firstOrNull { it.first == code }?.second ?: code

    /** 已经下载好的语言包（refresh 后更新）。英语包 ML Kit 自带，总在里面。 */
    @Volatile var downloaded: Set<String> = emptySet(); private set
    @Volatile var downloading = ""; private set
    @Volatile var lastError = ""; private set
    var onChanged: (() -> Unit)? = null
    var log: ((String) -> Unit)? = null

    private val translators = HashMap<String, Translator>()
    private val manager by lazy { RemoteModelManager.getInstance() }

    fun ready(codes: Collection<String>) = codes.all { it in downloaded }

    /** 读一下手机上已经有哪些语言包。 */
    fun refresh() {
        runCatching {
            manager.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { set -> downloaded = set.map { it.language }.toSet(); onChanged?.invoke() }
                .addOnFailureListener { e -> lastError = "读不到语言包：${e.message ?: e}"; onChanged?.invoke() }
        }.onFailure { lastError = "离线翻译用不了：${it.message ?: it}"; onChanged?.invoke() }
    }

    /** 一个一个下载缺的语言包。 */
    fun download(codes: Collection<String>) {
        if (downloading.isNotEmpty()) return
        val next = codes.firstOrNull { it !in downloaded } ?: run { refresh(); return }
        downloading = next; lastError = ""
        onChanged?.invoke()
        log?.invoke("离线翻译：开始下载${name(next)}语言包")
        runCatching {
            manager.download(TranslateRemoteModel.Builder(next).build(), DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    downloading = ""; downloaded = downloaded + next
                    log?.invoke("离线翻译：${name(next)}语言包下载好了")
                    onChanged?.invoke()
                    download(codes)
                }
                .addOnFailureListener { e ->
                    downloading = ""; lastError = "下载${name(next)}语言包失败：${e.message ?: e}"
                    log?.invoke("离线翻译：$lastError")
                    onChanged?.invoke()
                }
        }.onFailure { downloading = ""; lastError = "离线翻译用不了：${it.message ?: it}"; onChanged?.invoke() }
    }

    /** 删掉语言包（英语包删不掉，ML Kit 自带）。 */
    fun delete(codes: Collection<String>) {
        closeAll()
        for (c in codes) runCatching {
            manager.deleteDownloadedModel(TranslateRemoteModel.Builder(c).build())
                .addOnSuccessListener { refresh() }
                .addOnFailureListener { refresh() }
        }
    }

    /** 翻一句（主线程回调）。语言包没下载时先下载（第一次会慢）。 */
    fun translate(text: String, from: String, to: String, done: (Result<String>) -> Unit) {
        val t = runCatching {
            translators.getOrPut("$from>$to") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
            }
        }.getOrElse { done(Result.failure(it)); return }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                t.translate(text)
                    .addOnSuccessListener { r -> done(Result.success(r)) }
                    .addOnFailureListener { e -> done(Result.failure(e)) }
            }
            .addOnFailureListener { e -> done(Result.failure(java.io.IOException("${name(from)}→${name(to)}的语言包下载不了（第一次要联网）：${e.message ?: e}"))) }
    }

    /** 不用了就关掉（释放内存）。 */
    fun closeAll() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}
