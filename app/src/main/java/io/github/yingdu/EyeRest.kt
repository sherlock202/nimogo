package io.github.yingdu

import android.content.Context
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 眼睛休息：训练是一组视线移动，催眠是五分钟的单调摆动。不改变近视度数。 */
enum class EyeMode { TRAIN, SLEEP }

enum class EyePhase { MIZI, CW, CCW, DEPTH, REST, SWING, DONE }

/** 某一帧点该在哪。x、y 是 -1..1（上为负），scale 是点的大小，0 表示不画点。 */
data class EyeSpot(val phase: EyePhase, val x: Float, val y: Float, val scale: Float, val dim: Boolean)

object EyeLogic {
    const val RAY_MS = 3_000L
    const val RAYS = 8
    const val MIZI_MS = RAY_MS * RAYS
    const val ORBIT_MS = 10_000L
    const val DEPTH_MS = 12_000L
    const val REST_MS = 20_000L
    const val SLEEP_MS = 5 * 60_000L
    const val SWING_MS = 6_000L

    private const val ORBIT_R = 0.85f
    private const val SWING_R = 0.62f

    fun total(mode: EyeMode) = if (mode == EyeMode.TRAIN) MIZI_MS + ORBIT_MS * 2 + DEPTH_MS + REST_MS else SLEEP_MS

    /** 各段结束的时间，最后一段是整段结束。左键跳到下一个。 */
    fun marks(mode: EyeMode): LongArray = if (mode == EyeMode.TRAIN)
        longArrayOf(MIZI_MS, MIZI_MS + ORBIT_MS, MIZI_MS + ORBIT_MS * 2, MIZI_MS + ORBIT_MS * 2 + DEPTH_MS, total(mode))
    else longArrayOf(total(mode))

    fun nextMark(mode: EyeMode, elapsed: Long) = marks(mode).firstOrNull { it > elapsed + 200 } ?: total(mode)

    fun phase(mode: EyeMode, elapsed: Long): EyePhase {
        if (elapsed >= total(mode)) return EyePhase.DONE
        if (mode == EyeMode.SLEEP) return EyePhase.SWING
        var t = elapsed
        if (t < MIZI_MS) return EyePhase.MIZI
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
            EyePhase.MIZI -> {
                val ray = (elapsed / RAY_MS).toInt().coerceIn(0, RAYS - 1)
                val u = (elapsed % RAY_MS) / RAY_MS.toFloat()
                val dist = if (u < 0.5f) u / 0.5f else (1f - u) / 0.5f
                val theta = ray * PI / 4
                EyeSpot(phase, (sin(theta) * dist).toFloat(), (-cos(theta) * dist).toFloat(), 0.22f, false)
            }
            EyePhase.CW, EyePhase.CCW -> {
                val local = if (phase == EyePhase.CW) elapsed - MIZI_MS else elapsed - MIZI_MS - ORBIT_MS
                val turns = local / ORBIT_MS.toFloat()
                val theta = (if (phase == EyePhase.CW) 1 else -1) * 2 * PI * turns
                EyeSpot(phase, (sin(theta) * ORBIT_R).toFloat(), (-cos(theta) * ORBIT_R).toFloat(), 0.22f, false)
            }
            EyePhase.DEPTH -> {
                val local = elapsed - MIZI_MS - ORBIT_MS * 2
                val wave = cos(2 * PI * 3 * local / DEPTH_MS).toFloat()
                EyeSpot(phase, 0f, 0f, 0.5f - 0.5f * wave, false)
            }
        }
    }

    fun label(phase: EyePhase) = when (phase) {
        EyePhase.MIZI -> "米字"
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
 * 眼镜上的眼睛休息。训练：米字、顺时针、逆时针、远近（只是点变大变小，镜片焦距不变），然后闭眼。
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
            val cy = 88
            if (!sleep) {
                f.rect(cx - 3, cy, 7, 1, Frame.FAINT)
                f.rect(cx, cy - 3, 1, 7, Frame.FAINT)
            }
            val px = cx + (spot.x * 150f).toInt()
            val py = cy + (spot.y * 36f).toInt()
            val color = if (spot.dim) Frame.DIM else Frame.FULL
            dot(f, px, py, (3f + spot.scale * 13f).toInt().coerceAtLeast(2), color)
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

    private fun dot(f: Frame, cx: Int, cy: Int, r: Int, color: Int) {
        for (dy in -r..r) for (dx in -r..r) {
            if (dx * dx + dy * dy <= r * r) f.rect(cx + dx, cy + dy, 1, 1, color)
        }
    }
}
