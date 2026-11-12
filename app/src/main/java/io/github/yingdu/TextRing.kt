package io.github.yingdu

/**
 * 提词器文字里的进度环（锻炼、番茄钟）：左边外圈 12 格，中间放时间，右边 5 行文字。
 *
 * 只用眼镜字库里有的字（GB2312 里的 ○ ● ◎），空格用全角空格，保证每格宽度一样。
 * 剩下的时间是 ●，走过的是 ○；正在走的时候，外圈上有一个 ◎ 每秒换一格，看起来在转。
 * 不依赖 Android，方便单元测试。
 */
object TextRing {
    /** 提词器框里的行数（和 NimoClient 的正文行数一致）。 */
    const val ROWS = 5

    /** 外圈 12 格（行, 列），从正上方开始顺时针。 */
    private val CELLS = listOf(
        0 to 1, 0 to 2, 0 to 3,
        1 to 4, 2 to 4, 3 to 4,
        4 to 3, 4 to 2, 4 to 1,
        3 to 0, 2 to 0, 1 to 0,
    )
    private const val GRID_COLS = 5
    private const val CENTER_CHARS = 6      // 中间时间占 3 格，半角字符 6 个
    private const val EMPTY = "\u3000"      // 全角空格
    private const val PASSED = "○"
    private const val LEFT = "●"
    private const val MARK = "◎"

    /**
     * @param prog 已经过去的比例 0..1
     * @param center 中间的时间（半角，超过 6 个字符会截掉）
     * @param right 右边的文字，第 1 行对第 1 行（最多 5 行）
     * @param running 正在走：外圈有一个 ◎ 绕着转
     * @param nowMs 当前时间（毫秒），决定 ◎ 在哪一格
     * @param maxWidth 一行最多几个字符（提词器的 charsPerLine）
     */
    fun lines(
        prog: Double,
        center: String,
        right: List<String>,
        running: Boolean,
        nowMs: Long,
        maxWidth: Int,
    ): List<String> {
        val n = CELLS.size
        val lit = Math.ceil((1 - prog.coerceIn(0.0, 1.0)) * n - 1e-9).toInt().coerceIn(0, n)
        val mark = if (running) ((nowMs / 1000) % n).toInt() else -1

        val grid = Array(ROWS) { Array(GRID_COLS) { EMPTY } }
        CELLS.forEachIndexed { i, (r, c) ->
            grid[r][c] = when {
                i == mark -> MARK
                i >= n - lit -> LEFT
                else -> PASSED
            }
        }

        val t = center.take(CENTER_CHARS)
        val centerText = " ".repeat((CENTER_CHARS - t.length) / 2) + t
        val centerPadded = centerText.padEnd(CENTER_CHARS)

        return (0 until ROWS).map { r ->
            val left = if (r == 2) grid[r][0] + centerPadded + grid[r][GRID_COLS - 1]
                else grid[r].joinToString("")
            val text = right.getOrElse(r) { "" }
            val room = (maxWidth - left.length - 1).coerceAtLeast(0)
            val tail = if (text.length <= room) text else if (room <= 1) "" else text.take(room - 1) + "…"
            if (tail.isEmpty()) left else "$left $tail"
        }
    }
}
