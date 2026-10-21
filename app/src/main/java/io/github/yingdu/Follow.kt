package io.github.yingdu

/**
 * 语音跟读的对齐逻辑（纯 Kotlin，不依赖 Android，有单元测试）：
 * 讲稿和识别出来的文字都换成「模糊拼音」再比，所以繁体 / 简体、同音字（在 / 再）、平翘舌、前后鼻音、n / l 都对得上。
 */

/** 汉字 → 拼音（不带声调，第一个读音）。数据是 assets/follow/pinyin.txt：每行「拼音 汉字汉字…」。 */
class PinyinTable(data: String) {
    private val map = HashMap<Char, String>(30_000)

    init {
        for (line in data.lineSequence()) {
            if (line.isEmpty() || line[0] == '#') continue
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val py = line.substring(0, sp)
            for (k in sp + 1 until line.length) map[line[k]] = py
        }
        map['〇'] = "ling"
    }

    fun of(c: Char): String? = map[c]
    val size get() = map.size
}

/** 一段文字切成的「音节」：ids 是模糊拼音（或英文单词）的编号，starts / ends 是在原文里的字符位置。 */
class FollowTokens(val ids: IntArray, val starts: IntArray, val ends: IntArray) {
    val size get() = ids.size
}

object FollowText {
    private val DIGITS = arrayOf("ling", "yi", "er", "san", "si", "wu", "liu", "qi", "ba", "jiu")

    /** 模糊拼音：zh/ch/sh → z/c/s，n → l，ing/eng/ang → in/en/an（口音和识别误差常见的几种）。 */
    fun fuzzy(py: String): String {
        var s = py
        s = when {
            s.startsWith("zh") -> "z" + s.substring(2)
            s.startsWith("ch") -> "c" + s.substring(2)
            s.startsWith("sh") -> "s" + s.substring(2)
            else -> s
        }
        if (s.startsWith("n") && s.length > 1) s = "l" + s.substring(1)
        if (s.endsWith("ing") || s.endsWith("eng") || s.endsWith("ang")) s = s.dropLast(1)
        return s
    }

    private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /**
     * 把 text[from, to) 切成音节：汉字一个字一个音节，阿拉伯数字一位一个音节（按中文读法），英文一个单词一个音节；
     * 标点、空格、假名等跳过（识别不出来，留着只会打乱对齐）。
     */
    fun tokenize(text: CharSequence, from: Int, to: Int, table: PinyinTable, dict: MutableMap<String, Int>): FollowTokens {
        val ids = ArrayList<Int>(); val st = ArrayList<Int>(); val en = ArrayList<Int>()
        fun add(key: String, s: Int, e: Int) { ids.add(dict.getOrPut(key) { dict.size }); st.add(s); en.add(e) }
        var i = from.coerceAtLeast(0)
        val end = to.coerceAtMost(text.length)
        while (i < end) {
            val c = text[i]
            when {
                c in '0'..'9' -> { add(fuzzy(DIGITS[c - '0']), i, i + 1); i++ }
                c in '０'..'９' -> { add(fuzzy(DIGITS[c - '０']), i, i + 1); i++ }
                isLatin(c) -> {
                    var j = i
                    while (j < end && (isLatin(text[j]) || text[j] == '\'')) j++
                    add("#" + text.substring(i, j).lowercase(), i, j); i = j
                }
                else -> { table.of(c)?.let { add(fuzzy(it), i, i + 1) }; i++ }
            }
        }
        return FollowTokens(ids.toIntArray(), st.toIntArray(), en.toIntArray())
    }
}

/**
 * 跟读位置：识别结果的最后十来个音节，在讲稿「当前位置往前一点到往后一段」里找最像的地方（局部对齐），
 * 对上了就把位置移过去。跳过几句也能找到；离得越远要求对上的字越多，免得被偶然相同的几个字带跑。
 */
class FollowAligner {
    /** 一次对齐的结果（全部写进日志）。 */
    data class Result(
        val moved: Boolean, val from: Int, val to: Int, val score: Int, val distance: Int,
        val window: Int, val reason: String
    )

    /** 已经念到的位置（讲稿里的字符位置，不含）：下划线画到这里。 */
    var offset = 0

    companion object {
        const val MATCH = 2
        const val MISMATCH = -2
        const val GAP = -1
        /** 用识别结果的最后几个音节来找位置。 */
        const val TAIL = 12
        /** 往回最多找多少个音节、往后最多找多少个音节。 */
        const val BACK = 60
        const val AHEAD = 400
        /** 往前走几个音节以内算「正常往下念」。 */
        const val NEAR = 15
        /** 各种情况下最少要多少分才移动（对上一个字 2 分）。 */
        const val MIN_NEAR = 4
        const val MIN_FAR = 10
        const val MIN_BACK = 12
    }

    /**
     * hyp：这一句到目前为止识别出的音节（编号和讲稿用同一个 dict）；script：讲稿窗口的音节；
     * cur：script 里「下一个要念的」音节下标（offset 在窗口里的位置）。
     */
    fun update(hyp: IntArray, script: FollowTokens, cur: Int): Result {
        val from = offset
        if (hyp.isEmpty() || script.size == 0) return Result(false, from, from, 0, 0, script.size, "没有内容")
        val h = if (hyp.size > TAIL) hyp.copyOfRange(hyp.size - TAIL, hyp.size) else hyp
        val lo = (cur - BACK).coerceAtLeast(0)
        val hi = (cur + AHEAD).coerceAtMost(script.size)
        val m = hi - lo
        val n = h.size
        // Smith-Waterman 局部对齐：d[i][j] = 以 h[i-1]、script[lo+j-1] 结尾的最高分
        var prev = IntArray(m + 1)
        var row = IntArray(m + 1)
        val lastRows = Array(3) { IntArray(m + 1) }    // 最后三行（允许识别结果最后一两个字还不准）
        for (i in 1..n) {
            row[0] = 0
            for (j in 1..m) {
                val s = if (h[i - 1] == script.ids[lo + j - 1]) MATCH else MISMATCH
                row[j] = maxOf(0, prev[j - 1] + s, prev[j] + GAP, row[j - 1] + GAP)
            }
            val k = n - i
            if (k < 3) row.copyInto(lastRows[k])
            val t = prev; prev = row; row = t
        }
        var best = -1; var bestScore = 0; var bestAdj = Int.MIN_VALUE
        for (j in 1..m) {
            val sc = maxOf(lastRows[0][j], lastRows[1][j] - 1, lastRows[2][j] - 2)
            if (sc <= 0) continue
            val idx = lo + j - 1
            val d = idx - (cur - 1)          // 0 = 停在原地，1 = 往下一个字
            val adj = sc * 4 - when {
                d in 0..NEAR -> d / 4
                d > NEAR -> 8 + d / 20
                else -> 12 - d
            }
            if (adj > bestAdj) { bestAdj = adj; best = idx; bestScore = sc }
        }
        if (best < 0) return Result(false, from, from, 0, 0, m, "没对上")
        val d = best - (cur - 1)
        val need = when { d in 0..NEAR -> MIN_NEAR; d > NEAR -> MIN_FAR; else -> MIN_BACK }
        val to = script.ends[best]
        return when {
            d == 0 || (d < 0 && d >= -3) -> Result(false, from, from, bestScore, d, m, "原地")
            bestScore < need -> Result(false, from, to, bestScore, d, m, "分数不够（要 $need）")
            else -> { offset = to; Result(true, from, to, bestScore, d, m, if (d > NEAR) "跳过" else if (d < 0) "往回" else "前进") }
        }
    }
}

/** 眼镜上「正在念的那一行」放在哪：从下往上数第几行（1 = 最后一行）。 */
object FollowView {
    /** 让第 line 行出现在倒数第 fromBottom 行时，一屏的顶部行。 */
    fun topFor(line: Int, rows: Int, fromBottom: Int, maxTop: Int): Int {
        val row = (rows - fromBottom.coerceIn(1, rows)).coerceAtLeast(0)
        return (line - row).coerceIn(0, maxTop.coerceAtLeast(0))
    }
}
