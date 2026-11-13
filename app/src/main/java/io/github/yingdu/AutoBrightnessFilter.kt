package io.github.yingdu

/**
 * 自动亮度的防抖。眼镜报的环境光档位在同一个环境里也会上下跳一两档，直接跟着设就会一直在调。
 *
 * 做法：
 *  1. 先按时间平滑（时间常数 [tauMs]），短暂的跳动抹平；
 *  2. 平滑后的目标离上次设定的亮度不到 [bandPct] 个百分点，就不动（一档约 5 个百分点，所以差不到两档不动）；
 *  3. 两次调整之间至少隔 [minGapMs]。
 * 第一次（还没设过）直接按差距判断，不用等。
 * 不依赖 Android，方便单元测试。
 */
class AutoBrightnessFilter(
    private val tauMs: Long = 6_000L,
    private val bandPct: Double = 8.0,
    private val minGapMs: Long = 6_000L,
) {
    private var smooth = Double.NaN
    private var lastAt = 0L
    private var applied = Double.NaN
    private var appliedAt = -1L

    /** 重新开始（开关自动亮度、手动调了亮度、断线重连之后）：下次按当时的亮度重新比较。 */
    fun reset() {
        smooth = Double.NaN
        applied = Double.NaN
        appliedAt = -1L
    }

    /**
     * @param targetPct 眼镜这次报的环境光对应的目标亮度（百分比）
     * @param curPct 眼镜现在的亮度（百分比，只在第一次用来当比较基准）
     * @return 需要渐变到的新亮度；null = 不用动
     */
    fun feed(now: Long, targetPct: Double, curPct: Double): Double? {
        if (smooth.isNaN()) {
            smooth = targetPct
        } else {
            val dt = (now - lastAt).coerceAtLeast(0L)
            val a = 1 - Math.exp(-dt / tauMs.toDouble())
            smooth += (targetPct - smooth) * a
        }
        lastAt = now
        if (applied.isNaN()) applied = curPct
        if (Math.abs(smooth - applied) < bandPct) return null
        if (appliedAt >= 0 && now - appliedAt < minGapMs) return null
        applied = smooth
        appliedAt = now
        return smooth
    }
}
