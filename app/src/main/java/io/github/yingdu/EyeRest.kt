package io.github.yingdu

import android.content.Context
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 眼睛休息：训练是一组视线移动，催眠是五分钟的单调摆动。不改变近视度数。 */
enum class EyeMode { TRAIN, SLEEP }

enum class EyePhase { MIZI_V, MIZI_H, MIZI_D1, MIZI_D2, CW, CCW, DEPTH, REST, SWING, DONE }

/** 某一帧点该在哪。x、y 是 -1..1（上为负），scale 是点的大小，0 表示不画点。 */
data class EyeSpot(val phase: EyePhase, val x: Float, val y: Float, val scale: Float, val dim: Boolean)

object EyeLogic {
    /** 米字：竖、横、两条斜线，每条来回 10 次，一来一回 1.6 秒。 */
    const val STROKE_MS = 800L
    const val REPS = 10
    const val LINE_MS = STROKE_MS * 2 * REPS
    const val MIZI_MS = LINE_MS * 4
    private val LINES = listOf(EyePhase.MIZI_V, EyePhase.MIZI_H, EyePhase.MIZI_D1, EyePhase.MIZI_D2)
    /** 顺时针、逆时针各转 10 圈，一圈 2 秒。 */
    const val LAP_MS = 2_000L
    const val ORBIT_MS = LAP_MS * REPS
    /** 放大再缩小算一次，10 次。 */
    const val DEPTH_CYCLE_MS = STROKE_MS * 2
    const val DEPTH_MS = DEPTH_CYCLE_MS * REPS
    const val REST_MS = 20_000L
    const val SLEEP_MS = 5 * 60_000L
    const val SWING_MS = 6_000L

    private const val SWING_R = 0.62f

    fun total(mode: EyeMode) = if (mode == EyeMode.TRAIN) MIZI_MS + ORBIT_MS * 2 + DEPTH_MS + REST_MS else SLEEP_MS

    /** 各段结束的时间，最后一段是整段结束。左键跳到下一个。 */
    fun marks(mode: EyeMode): LongArray = if (mode == EyeMode.TRAIN)
        longArrayOf(LINE_MS, LINE_MS * 2, LINE_MS * 3, MIZI_MS, MIZI_MS + ORBIT_MS, MIZI_MS + ORBIT_MS * 2,
            MIZI_MS + ORBIT_MS * 2 + DEPTH_MS, total(mode))
    else longArrayOf(total(mode))

    fun nextMark(mode: EyeMode, elapsed: Long) = marks(mode).firstOrNull { it > elapsed + 200 } ?: total(mode)

    fun phase(mode: EyeMode, elapsed: Long): EyePhase {
        if (elapsed >= total(mode)) return EyePhase.DONE
        if (mode == EyeMode.SLEEP) return EyePhase.SWING
        var t = elapsed
        if (t < MIZI_MS) return LINES[(t / LINE_MS).toInt()]
        t -= MIZI_MS
        if (t < ORBIT_MS) return EyePhase.CW
        t -= ORBIT_MS
        if (t < ORBIT_MS) return EyePhase.CCW
        t -= ORBIT_MS
        if (t < DEPTH_MS) return EyePhase.DEPTH
        return EyePhase.REST
    }

    fun spot(mode: EyeMode, elapsed: Long): EyeSpot {
        val phase = phase(mode, elapsed)
        return when (phase) {
            EyePhase.DONE -> EyeSpot(phase, 0f, 0f, 0f, mode == EyeMode.SLEEP)
            EyePhase.REST -> EyeSpot(phase, 0f, 0f, 0f, false)
            EyePhase.SWING -> {
                val a = (2 * PI * (elapsed % SWING_MS) / SWING_MS).toFloat()
                EyeSpot(phase, (sin(a) * SWING_R).toFloat(), 0f, 0.28f, true)
            }
            EyePhase.MIZI_V, EyePhase.MIZI_H, EyePhase.MIZI_D1, EyePhase.MIZI_D2 -> {
                val local = elapsed - LINES.indexOf(phase) * LINE_MS
                val p = sweep(local)
                val (x, y) = when (phase) {
                    EyePhase.MIZI_V -> 0f to p
                    EyePhase.MIZI_H -> p to 0f
                    EyePhase.MIZI_D1 -> p to p
                    else -> p to -p
                }
                EyeSpot(phase, x, y, 0.22f, false)
            }
            EyePhase.CW, EyePhase.CCW -> {
                val local = if (phase == EyePhase.CW) elapsed - MIZI_MS else elapsed - MIZI_MS - ORBIT_MS
                val turns = local / LAP_MS.toFloat()
                val theta = (if (phase == EyePhase.CW) 1 else -1) * 2 * PI * turns
                EyeSpot(phase, sin(theta).toFloat(), (-cos(theta)).toFloat(), 0.22f, false)
            }
            EyePhase.DEPTH -> {
                val local = elapsed - MIZI_MS - ORBIT_MS * 2
                val wave = cos(2 * PI * local / DEPTH_CYCLE_MS).toFloat()
                EyeSpot(phase, 0f, 0f, 0.5f - 0.5f * wave, false)
            }
        }
    }

    /** 一条线上来回一次：-1 是起点那一头，+1 是另一头，回到起点时又是 -1。 */
    private fun sweep(local: Long): Float {
        val frac = (local % (STROKE_MS * 2)).toFloat() / (STROKE_MS * 2)
        val tri = if (frac < 0.5f) frac * 2f else (1f - frac) * 2f
        return -1f + 2f * tri
    }

    fun label(phase: EyePhase) = when (phase) {
        EyePhase.MIZI_V -> "米字·竖"
        EyePhase.MIZI_H -> "米字·横"
        EyePhase.MIZI_D1 -> "米字·斜一"
        EyePhase.MIZI_D2 -> "米字·斜二"
        EyePhase.CW -> "顺时针"
        EyePhase.CCW -> "逆时针"
        EyePhase.DEPTH -> "远近"
        EyePhase.REST -> "闭眼休息"
        EyePhase.SWING -> "催眠"
        EyePhase.DONE -> "完成"
    }

    fun clock(ms: Long): String {
        val s = ((ms.coerceAtLeast(0) + 999) / 1000)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * 眼镜上的眼睛休息。训练：米字四条线各来回 10 次，顺时针、逆时针各 10 圈，放大缩小 10 次（只是点变大变小，镜片焦距不变），然后闭眼。
 * 催眠：点左右慢慢摆五分钟，结束时画面暗下去，不弹提醒。
 * 右单击开始或暂停，左单击下一段。点返回就暂停；切到首页或锁屏时，眼镜上还开着就继续。
 */
class EyeRestApp(private val host: AppHost) : GlassesApp {
    private val prefs = host.context.getSharedPreferences("eyes", Context.MODE_PRIVATE)

    override val title = "视力训练"

    var mode: EyeMode
        get() = runCatching { EyeMode.valueOf(prefs.getString("mode", EyeMode.TRAIN.name)!!) }.getOrDefault(EyeMode.TRAIN)
        private set(v) { prefs.edit().putString("mode", v.name).apply() }

    private var running = false
    private var baseMs = 0L
    private var startedAt = 0L
    private var open = false

    fun elapsed(now: Long = System.currentTimeMillis()) =
        (baseMs + if (running) now - startedAt else 0L).coerceIn(0L, EyeLogic.total(mode))

    fun isRunning() = running

    /** 已经开始过（含暂停、做完）。还停在起点时是 false。 */
    fun hasProgress() = running || baseMs > 0L

    fun phase(now: Long = System.currentTimeMillis()) = EyeLogic.phase(mode, elapsed(now))

    fun remaining(now: Long = System.currentTimeMillis()) = EyeLogic.total(mode) - elapsed(now)

    /** 换成另一种。正在进行的这一次停掉，从头等开始。 */
    fun choose(next: EyeMode) {
        if (mode == next && baseMs == 0L && !running) return
        mode = next
        baseMs = 0L
        running = false
        show()
    }

    /** 从头开始这一模式（语音菜单、再来一次）。 */
    fun begin(next: EyeMode) {
        mode = next
        baseMs = 0L
        running = true
        startedAt = System.currentTimeMillis()
        host.log(if (next == EyeMode.SLEEP) "视力训练：催眠 5 分钟" else "视力训练：开始")
        show()
    }

    fun startPause() {
        val now = System.currentTimeMillis()
        if (phase(now) == EyePhase.DONE) { begin(mode); return }
        if (running) {
            baseMs = elapsed(now)
            running = false
        } else {
            running = true
            startedAt = now
        }
        show()
    }

    fun skip() {
        val now = System.currentTimeMillis()
        baseMs = EyeLogic.nextMark(mode, elapsed(now))
        if (running) startedAt = now
        if (baseMs >= EyeLogic.total(mode)) running = false
        show()
    }

    private fun show() {
        host.changed()
        if (!open) return
        host.main.removeCallbacks(loop)
        host.main.post(loop)
    }

    private val loop = Runnable { tick() }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (running && elapsed(now) >= EyeLogic.total(mode)) {
            baseMs = EyeLogic.total(mode)
            running = false
        }
        host.redraw(this)
        if (!running || !open) return
        val gap = maxOf(200L, (host.frameIntervalMs() ?: 200L) + 40)
        host.main.postDelayed(loop, gap)
    }

    override fun onOpen() { open = true; show() }
    override fun onClose() {
        if (running) { baseMs = elapsed(); running = false }
        open = false
        host.main.removeCallbacks(loop)
    }
    override fun onResume() = show()

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_RIGHT -> startPause()
            GlassesInput.CLICK_LEFT -> skip()
            else -> {}
        }
    }

    override fun status(): String {
        val p = phase()
        val state = if (running) "进行中" else if (p == EyePhase.DONE) "结束" else if (baseMs > 0) "已暂停" else "未开始"
        return "$title · ${EyeLogic.label(p)} · $state " + EyeLogic.clock(remaining())
    }

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx.coerceAtMost(16))
        val now = System.currentTimeMillis()
        val e = elapsed(now)
        val spot = EyeLogic.spot(mode, e)
        val sleep = mode == EyeMode.SLEEP
        val header = if (sleep) "催眠" else "视力训练 · ${EyeLogic.label(spot.phase)}"
        val right = when {
            spot.phase == EyePhase.DONE && sleep -> ""
            spot.phase == EyePhase.DONE -> "完成"
            !running && baseMs == 0L -> "右键开始"
            !running -> "暂停"
            else -> EyeLogic.clock(EyeLogic.total(mode) - e)
        }
        if (!(sleep && spot.phase == EyePhase.DONE)) f.header(header, right)
        if (sleep && spot.phase == EyePhase.DONE) {
            f.text("结束", f.w / 2, 78, Frame.FAINT, f.small, Frame.CENTER)
        } else if (spot.phase == EyePhase.REST) {
            f.text("闭眼休息", f.w / 2, 62, Frame.DIM, f.font, Frame.CENTER)
            f.text(EyeLogic.clock(EyeLogic.total(mode) - e), f.w / 2, 96, Frame.FAINT, f.font, Frame.CENTER)
        } else if (spot.phase != EyePhase.DONE && spot.scale > 0f) {
            val cx = f.w / 2
            val cy = 90
            if (!sleep) {
                f.rect(cx - 3, cy, 7, 1, Frame.FAINT)
                f.rect(cx, cy - 3, 1, 7, Frame.FAINT)
            }
            // 训练尽量铺满：左右留一点边，上下停在标题和底部提示之间。放大时点本身占满这块。催眠仍是小幅度
            val px = cx + (spot.x * (if (sleep) 150f else 206f)).toInt()
            val py = cy + (spot.y * (if (sleep) 36f else 50f)).toInt()
            val color = if (spot.dim) Frame.DIM else Frame.FULL
            val radius = if (spot.phase == EyePhase.DEPTH) (4f + spot.scale * 48f).toInt() else (3f + spot.scale * 8f).toInt()
            dot(f, px, py, radius.coerceAtLeast(2), color)
        }
        if (!sleep) {
            f.footer(when {
                spot.phase == EyePhase.DONE -> "右键 再来一次"
                spot.phase == EyePhase.REST -> "闭眼，数完再睁开"
                else -> "右键 开始/暂停 · 左键 下一段"
            })
        }
        return f.done()
    }

    private val dotPaint = android.graphics.Paint()

    private fun dot(f: Frame, cx: Int, cy: Int, r: Int, color: Int) {
        dotPaint.color = color
        f.canvas.drawCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), dotPaint)
    }
}
