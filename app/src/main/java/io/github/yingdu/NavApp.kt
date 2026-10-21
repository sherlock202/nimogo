package io.github.yingdu

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * NimoGo 的导航（Google Navigation SDK 逐向数据）：导航时眼镜显示转向箭头、距离、路名和剩余时间。
 * 目的地从 Google Maps「共享」过来（或在萤读里输入地名），见 MainActivity。
 * 镜腿单击：在「当前一步」和「后续步骤」之间切换。长按右镜腿是眼镜自己的退出：只是眼镜不显示了，导航继续。
 */
class NavApp(private val host: AppHost, private val showOnGlasses: (NavApp) -> Unit, private val hideFromGlasses: (NavApp) -> Unit) : GlassesApp {
    enum class Phase { IDLE, ROUTING, GUIDING, ARRIVED, ERROR }

    private val prefs = host.context.getSharedPreferences("nav", Context.MODE_PRIVATE)

    override val title = "导航"

    var phase = Phase.IDLE; private set
    var dest: NavDest? = null; private set
    var mode: NavMode = NavMode.WALKING; private set
    var snap: NavSnap? = null; private set
    var error = ""; private set
    /** 眼镜上显示哪一页：0 当前一步，1 后续步骤。 */
    private var page = 0

    // ---------- 设置 ----------

    var apiKey: String
        get() = prefs.getString("apiKey", "").orEmpty()
        set(v) { prefs.edit().putString("apiKey", v.trim()).apply(); host.changed() }
    var defaultMode: NavMode
        get() = runCatching { NavMode.valueOf(prefs.getString("mode", "WALKING")!!) }.getOrDefault(NavMode.WALKING)
        set(v) { prefs.edit().putString("mode", v.name).apply(); host.changed() }
    /** 手机上的语音播报（默认关：眼镜上看就够了）。 */
    var voice: Boolean
        get() = prefs.getBoolean("voice", false)
        set(v) { prefs.edit().putBoolean("voice", v).apply(); host.changed() }
    var avoidTolls: Boolean
        get() = prefs.getBoolean("avoidTolls", false)
        set(v) { prefs.edit().putBoolean("avoidTolls", v).apply(); host.changed() }
    var avoidHighways: Boolean
        get() = prefs.getBoolean("avoidHighways", false)
        set(v) { prefs.edit().putBoolean("avoidHighways", v).apply(); host.changed() }
    /** 眼镜上画小地图（路线形状 + 当前位置，前进方向朝上）。 */
    var showMap: Boolean
        get() = prefs.getBoolean("showMap", true)
        set(v) { prefs.edit().putBoolean("showMap", v).apply(); lastKey = ""; show() }
    /** 每月最多导航几个目的地（0 = 不限）。默认 1000：Navigation SDK 每月前 1000 个目的地免费。 */
    var monthlyLimit: Int
        get() = prefs.getInt("limit", 1000)
        set(v) { prefs.edit().putInt("limit", v.coerceAtLeast(0)).apply(); host.changed() }

    fun usedThisMonth(): Int = NavBudget.used(prefs.getString("month", "").orEmpty(), prefs.getInt("count", 0),
        NavBudget.monthKey(System.currentTimeMillis()))

    private fun countOne() {
        val now = NavBudget.monthKey(System.currentTimeMillis())
        prefs.edit().putString("month", now).putInt("count", usedThisMonth() + 1).apply()
    }

    fun canStart() = NavBudget.allowed(usedThisMonth(), monthlyLimit)

    // ---------- 开始 / 停止 ----------

    /** 第几次请求：用户停止或又开始了新的导航后，旧请求迟到的回调就不再处理。 */
    private var reqId = 0

    init {
        NavGuide.sink = { onSnap(it) }
        NavGuide.onArrived = { arrived() }
        NavGuide.log = { host.log(it) }
        NavGuide.onMapChanged = { onMapChanged() }
        // 上一个服务实例留下的引导（萤读被关掉后重新打开）：这里接不上它的状态，直接停掉，免得在后台空跑
        NavGuide.useKey(apiKey)
        if (NavGuide.guiding()) NavGuide.stop()
    }

    /** 开始导航（已经同意了服务条款、有定位权限、填了 API Key）。 */
    fun start(dest: NavDest, mode: NavMode) {
        if (!canStart()) { fail("本月已经导航了 ${usedThisMonth()} 个目的地，到了你设的上限（${monthlyLimit}）"); return }
        host.main.removeCallbacks(autoClose)
        val id = ++reqId
        this.dest = dest; this.mode = mode
        phase = Phase.ROUTING; snap = null; error = ""; page = 0; noDataHint = ""; junctions = emptyList()
        host.log("导航：规划到「${dest.title}」的路线（${mode.zh}）")
        showOnGlasses(this)
        show()
        val app = host.context.applicationContext as Application
        NavGuide.withNavigator(app, apiKey) { nav, err ->
            if (id != reqId || phase != Phase.ROUTING) return@withNavigator   // 已经停止或换了目的地
            if (nav == null) { fail(err ?: "导航初始化失败"); return@withNavigator }
            NavGuide.start(host.context, nav, dest, NavGuide.Options(mode, voice, avoidTolls, avoidHighways)) routed@{ e, billed ->
                if (billed) countOne()
                if (id != reqId || phase != Phase.ROUTING) return@routed         // 迟到的结果（例如被新请求取消）
                if (e != null) fail(e)
                else {
                    phase = Phase.GUIDING; host.log("导航开始：到「${dest.title}」"); show()
                    // 15 秒还没收到逐向数据：记下来，眼镜上也提示（方便排查）
                    host.main.removeCallbacks(noData); host.main.postDelayed(noData, 15_000)
                }
            }
        }
    }

    /** 正在规划或引导（包括 SDK 里还有没停的引导）。 */
    fun active() = phase == Phase.ROUTING || phase == Phase.GUIDING || NavGuide.guiding()

    fun stop() {
        reqId++
        NavGuide.stop()
        host.main.removeCallbacks(autoClose)
        if (phase != Phase.IDLE) host.log("导航结束")
        phase = Phase.IDLE; snap = null; page = 0; junctions = emptyList()
        hideFromGlasses(this)
        host.changed()
    }

    /** 服务销毁时：停止引导，不再接收 SDK 的回调。 */
    fun shutdown() {
        reqId++
        NavGuide.stop()
        NavGuide.sink = null; NavGuide.onArrived = null
        host.main.removeCallbacks(autoClose); host.main.removeCallbacks(drawNow)
    }

    private fun fail(msg: String) {
        reqId++
        NavGuide.stop()
        phase = Phase.ERROR; error = msg
        host.log("导航：$msg")
        show()
        host.main.removeCallbacks(autoClose); host.main.postDelayed(autoClose, 30_000)
    }

    private fun arrived() {
        if (phase != Phase.GUIDING) return
        NavGuide.stop()
        phase = Phase.ARRIVED
        host.log("导航：已到达「${dest?.title ?: ""}」")
        show()
        // 到达画面留 20 秒，然后眼镜回到阅读或看板
        host.main.removeCallbacks(autoClose); host.main.postDelayed(autoClose, 20_000)
    }

    /** 开始引导后一直没收到 SDK 的逐向数据。 */
    var noDataHint = ""; private set
    private val noData = Runnable {
        if (phase == Phase.GUIDING && NavGuide.infoCount == 0) {
            noDataHint = "没收到导航数据（逐向数据服务：" + when (NavGuide.lastRegisterOk) { true -> "已注册"; false -> "注册失败"; null -> "未注册" } + "）"
            host.log("导航：开始引导 15 秒了，还没收到 SDK 的逐向数据（" + (if (NavGuide.guiding()) "SDK 引导中" else "SDK 引导没有运行") + "）")
            show()
        }
    }

    private val autoClose = Runnable { if (phase == Phase.ARRIVED || phase == Phase.ERROR) { phase = Phase.IDLE; hideFromGlasses(this); host.changed() } }

    // ---------- 逐向数据 ----------

    private var lastKey = ""

    private fun onSnap(s: NavSnap) {
        // 规划中收到的可能是上一条路线的数据：等算路结果出来（phase 变成 GUIDING）再显示
        if (phase != Phase.GUIDING) return
        val end = NavGuide.here?.let { h -> NavGuide.route.lastOrNull()?.let { NavMap.dist(h, it) } }
        if (NavArrive.reached(s, end, mode)) {
            host.log("导航：离终点很近了（剩 ${s.remainMeters ?: "?"} 米，离路线终点 ${end?.toInt() ?: "?"} 米，状态 ${s.state}），结束导航")
            arrived(); return
        }
        if (s.state == NavSnap.STOPPED) return
        snap = s
        updateJunctions(s)
        val key = phase.name + page + s.displayKey()
        if (key == lastKey) return
        lastKey = key
        show()
    }

    /** 小地图上的路口（淡线）：前方几步的按转向估出来，走过的留着。 */
    private var junctions: List<NavJunction> = emptyList()

    private fun updateJunctions(s: NavSnap) {
        if (!showMap) return
        val here = NavGuide.here ?: return
        val route = NavGuide.route
        val steps = listOfNotNull(s.current) + s.next
        junctions = runCatching { NavJunctions.merge(junctions, NavJunctions.upcoming(route, here, NavGuide.course, s.toStepMeters, steps), route) }
            .getOrDefault(junctions)
    }

    /** 位置移动了约 5 米或路线变了，才重画小地图。 */
    private var lastMapKey = ""
    private fun onMapChanged() {
        if (phase != Phase.GUIDING || !showMap) return
        val h = NavGuide.here ?: return
        // 移动约 2 米、走路方向变了 15 度以上、面朝的方向变了（NavGuide 里已经过滤成 10 度一变）、或路线变了，才重画（眼镜收图慢，太频繁反而卡）
        val key = "${Math.round(h.lat * 50000)},${Math.round(h.lng * 50000)},${NavGuide.route.size}," +
            (NavGuide.course?.let { Math.round(it / 15) } ?: "-") + "," + (NavGuide.facing?.let { Math.round(it) } ?: "-")
        if (key == lastMapKey) return
        lastMapKey = key
        show()
    }

    // ---------- 眼镜画面 ----------

    private var lastDraw = 0L

    /** 画面变了才发；眼镜收图慢时合并成一张（只发最新的）。 */
    private fun show() {
        host.changed()
        if (!host.isShowing(this)) return
        host.main.removeCallbacks(drawNow)
        val gap = maxOf(800L, (host.frameIntervalMs() ?: 0) + 50)
        val wait = (lastDraw + gap - System.currentTimeMillis()).coerceAtLeast(0)
        host.main.postDelayed(drawNow, wait)
    }

    private val drawNow = Runnable { lastDraw = System.currentTimeMillis(); host.redraw(this) }

    override fun onOpen() { lastKey = ""; show() }
    override fun onClose() { host.main.removeCallbacks(drawNow) }
    override fun onResume() { lastKey = ""; show() }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_RIGHT, GlassesInput.CLICK_LEFT -> if (phase == Phase.GUIDING) { page = 1 - page; lastKey = ""; show() }
            else -> {}
        }
    }

    override fun status(): String = when (phase) {
        Phase.IDLE -> "导航 · 没有在导航"
        Phase.ROUTING -> "导航 · 正在规划路线"
        Phase.ARRIVED -> "导航 · 已到达 ${dest?.title ?: ""}"
        Phase.ERROR -> "导航 · $error"
        Phase.GUIDING -> {
            val s = snap; val c = s?.current
            if (s == null || c == null) "导航 · ${mode.zh}到 ${dest?.title ?: ""}"
            else "导航 · " + listOfNotNull(s.toStepMeters?.let { NavText.distance(it) + "后" }, NavIcon.zh(NavIcon.of(c.maneuver)).ifEmpty { null },
                c.road.ifEmpty { null }).joinToString(" ")
        }
    }

    override fun render(fontPx: Int): ByteArray = Frame(fontPx).also { draw(it) }.done()

    /** 手机上的预览图。 */
    fun preview(fontPx: Int): Bitmap = Frame(fontPx).also { draw(it) }.bitmap()

    private fun draw(f: Frame) {
        val d = dest
        val big = GlassesFonts.text(32)
        when (phase) {
            Phase.IDLE -> {
                f.header("导航")
                f.text("没有在导航", 12, 44, Frame.FULL)
                f.small.wrap("在 Google Maps 里选好地点，点「共享」→ NimoGo；或在 NimoGo 里输入地点。", f.w - 24).take(3)
                    .forEachIndexed { i, l -> f.text(l, 12, 44 + f.font.height + 12 + i * (f.small.height + 4), Frame.DIM, f.small) }
            }
            Phase.ROUTING -> {
                f.header("${mode.zh} · 到 ${d?.title ?: ""}")
                NavDraw.icon(f.canvas, NavIcon.DEPART, 62f, 92f, 92f, Frame.DIM)
                f.text("正在规划路线…", 128, 60, Frame.FULL, f.font)
                d?.title?.let { f.text(f.small.ellipsize(it, f.w - 134), 128, 60 + f.font.height + 10, Frame.DIM, f.small) }
            }
            Phase.ERROR -> {
                f.header("导航没有开始")
                f.font.wrap(error, f.w - 24).take(4).forEachIndexed { i, l -> f.text(l, 12, 40 + i * (f.font.height + 6), Frame.FULL) }
            }
            Phase.ARRIVED -> {
                f.header("导航")
                NavDraw.icon(f.canvas, NavIcon.DESTINATION, 62f, 92f, 96f, Frame.FULL)
                f.text("已到达", 128, 48, Frame.FULL, big)
                d?.title?.let { t -> f.font.wrap(t, f.w - 134).take(2).forEachIndexed { i, l -> f.text(l, 128, 48 + big.height + 12 + i * (f.font.height + 4), Frame.DIM) } }
            }
            Phase.GUIDING -> if (page == 1) drawSteps(f) else drawCurrent(f, big)
        }
    }

    private fun remainText(s: NavSnap?): String = listOfNotNull(s?.remainMeters?.let { NavText.distance(it) },
        s?.remainSeconds?.let { NavText.duration(it) }).joinToString(" · ")

    private fun drawCurrent(f: Frame, big: PixelText) {
        val sn = snap
        val rerouting = sn?.state == NavSnap.REROUTING
        f.header("${mode.zh} · 到 ${dest?.title ?: ""}", if (rerouting) "重新规划路线…" else remainText(sn).let { if (it.isEmpty()) "" else "剩 $it" })
        val c = sn?.current
        if (sn == null || c == null) {
            f.text(if (rerouting) "重新规划路线…" else "等待定位…", 12, 60, Frame.FULL)
            if (noDataHint.isNotEmpty()) f.small.wrap(noDataHint, f.w - 24).take(2).forEachIndexed { i, l -> f.text(l, 12, 60 + f.font.height + 10 + i * (f.small.height + 4), Frame.DIM, f.small) }
            return
        }
        val s: NavSnap = sn
        val icon = NavIcon.of(c.maneuver)
        val here = NavGuide.here
        if (showMap && here != null && NavGuide.route.size >= 2) { drawWithMap(f, big, s, c, icon, here, rerouting); return }
        NavDraw.icon(f.canvas, icon, 62f, 92f, 100f, if (rerouting) Frame.DIM else Frame.FULL)
        val x = 128
        val maxW = f.w - x - 6
        var y = 36
        s.toStepMeters?.let { f.text(NavText.distance(it), x, y, if (rerouting) Frame.DIM else Frame.FULL, big); y += big.height + 8 }
        // 说明文字（SDK 按手机语言给，例如「丸の内南口を右折」）；没有就用方向 + 路名
        val line = c.instruction.ifEmpty { listOf(NavIcon.zh(icon), c.road).filter { it.isNotEmpty() }.joinToString(" ") }
        val lines = f.font.wrap(line, maxW).take(2)
        lines.forEach { l -> f.text(l, x, y, Frame.FULL); y += f.font.height + 4 }
        if (c.road.isNotEmpty() && !line.contains(c.road) && y + f.small.height < 148) f.text(f.small.ellipsize(c.road, maxW), x, y + 2, Frame.DIM, f.small)
        // 底部：下一步预告 + 预计到达时间
        val eta = s.remainSeconds?.let { NavText.eta(System.currentTimeMillis(), it) + " 到" } ?: ""
        val nx = s.next.firstOrNull()
        val tip = nx?.let { n -> "然后 " + listOfNotNull(n.meters?.let { NavText.distance(it) + "后" }, NavIcon.zh(NavIcon.of(n.maneuver)).ifEmpty { null },
            n.road.ifEmpty { null }).joinToString(" ") } ?: ""
        val by = f.h - f.small.height - 3
        if (eta.isNotEmpty()) f.text(eta, f.w - 6, by, Frame.DIM, f.small, Frame.RIGHT)
        if (tip.isNotEmpty()) f.text(f.small.ellipsize(tip, f.w - 12 - f.small.measure(eta) - 12), 6, by, Frame.DIM, f.small)
    }

    /** 左边小地图（前进方向朝上），右边箭头、距离、说明。 */
    private fun drawWithMap(f: Frame, big: PixelText, s: NavSnap, c: NavStep, icon: NavIcon, here: NavPoint, rerouting: Boolean) {
        val mapW = 200; val top = 28
        NavDraw.miniMap(f.canvas, NavGuide.route, junctions, here, NavGuide.course, NavGuide.facing, s.toStepMeters, 0f, top.toFloat(), mapW.toFloat(), (f.h - top).toFloat(), rerouting)
        // 分隔线
        var yy = top + 2; while (yy < f.h) { f.rect(mapW + 2, yy, 1, 2, Frame.FAINT); yy += 5 }
        val x = mapW + 10
        val maxW = f.w - x - 6
        val color = if (rerouting) Frame.DIM else Frame.FULL
        NavDraw.icon(f.canvas, icon, x + 22f, 58f, 44f, color)
        s.toStepMeters?.let { f.text(NavText.distance(it), x + 52, 38, color, big) }
        var y = 38 + big.height + 10
        val line = c.instruction.ifEmpty { listOf(NavIcon.zh(icon), c.road).filter { it.isNotEmpty() }.joinToString(" ") }
        f.font.wrap(line, maxW).take(2).forEach { l -> f.text(l, x, y, Frame.FULL); y += f.font.height + 4 }
        // 底部：预计到达 + 下一步
        val eta = s.remainSeconds?.let { NavText.eta(System.currentTimeMillis(), it) + " 到" } ?: ""
        val nx = s.next.firstOrNull()
        val tip = nx?.let { n -> "然后 " + listOfNotNull(n.meters?.let { NavText.distance(it) + "后" }, NavIcon.zh(NavIcon.of(n.maneuver)).ifEmpty { null }).joinToString(" ") } ?: ""
        val by = f.h - f.small.height - 3
        if (eta.isNotEmpty()) f.text(eta, f.w - 6, by, Frame.DIM, f.small, Frame.RIGHT)
        if (tip.isNotEmpty()) f.text(f.small.ellipsize(tip, maxW - f.small.measure(eta) - 8), x, by, Frame.DIM, f.small)
    }

    private fun drawSteps(f: Frame) {
        val s = snap
        f.header("后续步骤", remainText(s))
        val steps = listOfNotNull(s?.current?.let { it.copy(meters = s.toStepMeters) }) + (s?.next ?: emptyList())
        if (steps.isEmpty()) { f.text("等待定位…", 12, 60, Frame.FULL); return }
        val rowH = 33
        steps.take(4).forEachIndexed { i, st ->
            val top = 34 + i * rowH
            val color = if (i == 0) Frame.FULL else Frame.DIM
            NavDraw.icon(f.canvas, NavIcon.of(st.maneuver), 20f, top + 14f, 26f, color)
            val dist = st.meters?.let { NavText.distance(it) } ?: ""
            f.text(dist, 40, top + 6, color, f.small)
            val text = st.instruction.ifEmpty { listOf(NavIcon.zh(NavIcon.of(st.maneuver)), st.road).filter { it.isNotEmpty() }.joinToString(" ") }
            f.text(f.small.ellipsize(text, f.w - 120), 112, top + 6, color, f.small)
        }
    }
}

/** 眼镜上的转向箭头（Canvas 矢量画，粗线条，2bpp 也看得清）。 */
object NavDraw {
    /** 在 (cx, cy) 为中心、边长 size 的方框里画一个图标。 */
    fun icon(c: Canvas, icon: NavIcon, cx: Float, cy: Float, size: Float, color: Int) {
        val sw = (size * 0.13f).coerceAtLeast(3f)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val faint = Paint(line).apply { this.color = (color and 0x00FFFFFF) or (0x60 shl 24) }
        val h = size / 2
        val bottom = cy + h * 0.95f
        val mid = cy + h * 0.1f
        fun turn(deg: Float, p: Paint = line, headPaint: Paint = fill) {
            // 竖直的一段从底部到拐点，再朝 deg 方向（0 = 正前，负 = 左，正 = 右）；急转弯的拐点高一些，免得折回来压住竖线
            val sharp = kotlin.math.abs(deg) > 100f
            val mid = if (sharp) cy - h * 0.65f else mid
            val len = if (sharp) h * 1.05f else h * 0.8f
            val rad = Math.toRadians(deg.toDouble())
            val ex = cx + (sin(rad) * len).toFloat()
            val ey = mid - (cos(rad) * len).toFloat()
            // 线画到箭头里面一点就停，免得从箭尖两边露出来
            val back = sw * 1.1f
            val path = Path().apply { moveTo(cx, bottom); lineTo(cx, mid); lineTo(ex - (sin(rad) * back).toFloat(), ey + (cos(rad) * back).toFloat()) }
            c.drawPath(path, p)
            head(c, cx, mid, ex, ey, sw, headPaint)
        }
        val tipY = cy - h * 0.8f
        when (icon) {
            NavIcon.STRAIGHT, NavIcon.UNKNOWN -> {
                c.drawLine(cx, bottom, cx, tipY + sw * 1.3f, line)
                head(c, cx, bottom, cx, tipY, sw, fill)
            }
            NavIcon.DEPART -> {
                c.drawLine(cx, bottom - sw, cx, tipY + sw * 1.3f, line)
                head(c, cx, bottom, cx, tipY, sw, fill)
                c.drawCircle(cx, bottom - sw * 0.5f, sw * 0.9f, fill)
            }
            NavIcon.LEFT -> turn(-90f)
            NavIcon.RIGHT -> turn(90f)
            NavIcon.SLIGHT_LEFT -> turn(-45f)
            NavIcon.SLIGHT_RIGHT -> turn(45f)
            NavIcon.SHARP_LEFT -> turn(-135f)
            NavIcon.SHARP_RIGHT -> turn(135f)
            NavIcon.KEEP_LEFT, NavIcon.KEEP_RIGHT -> {
                // 分岔：要走的一边亮，另一边暗
                val left = icon == NavIcon.KEEP_LEFT
                val other = Path().apply { moveTo(cx, mid); lineTo(cx + (if (left) 1 else -1) * h * 0.5f, cy - h * 0.7f) }
                c.drawPath(other, faint)
                turn(if (left) -30f else 30f)
            }
            NavIcon.UTURN_LEFT, NavIcon.UTURN_RIGHT -> {
                val dir = if (icon == NavIcon.UTURN_LEFT) -1 else 1
                val r = h * 0.38f
                val x2 = cx + dir * 2 * r
                val top = cy - h * 0.35f
                val tip = cy + h * 0.6f
                // 往右掉头：从左边的点（180°）顺时针绕到右边；往左掉头：从右边的点（0°）逆时针绕到左边
                val path = Path().apply {
                    moveTo(cx, bottom); lineTo(cx, top + r)
                    arcTo(RectF(minOf(cx, x2), top, maxOf(cx, x2), top + 2 * r), if (dir > 0) 180f else 0f, dir * 180f)
                    lineTo(x2, tip - sw * 1.3f)
                }
                c.drawPath(path, line)
                head(c, x2, top + r, x2, tip, sw, fill)
            }
            NavIcon.ROUNDABOUT_CW, NavIcon.ROUNDABOUT_CCW -> {
                val r = h * 0.3f
                val rcy = cy + h * 0.12f
                val tip = cy - h * 0.98f
                c.drawLine(cx, bottom, cx, rcy + r, line)
                c.drawCircle(cx, rcy, r, line)
                c.drawLine(cx, rcy - r, cx, tip + sw * 1.3f, line)
                head(c, cx, rcy - r, cx, tip, sw, fill)
            }
            NavIcon.DESTINATION -> {
                // 旗子
                val px = cx - h * 0.35f
                c.drawLine(px, bottom, px, cy - h * 0.85f, line)
                val flag = Path().apply {
                    moveTo(px + sw / 2, cy - h * 0.85f); lineTo(cx + h * 0.6f, cy - h * 0.6f); lineTo(px + sw / 2, cy - h * 0.3f); close()
                }
                c.drawPath(flag, fill)
            }
            NavIcon.FERRY -> {
                val hull = Path().apply {
                    moveTo(cx - h * 0.8f, cy + h * 0.2f); lineTo(cx + h * 0.8f, cy + h * 0.2f)
                    lineTo(cx + h * 0.5f, cy + h * 0.6f); lineTo(cx - h * 0.5f, cy + h * 0.6f); close()
                }
                c.drawPath(hull, fill)
                c.drawRect(cx - h * 0.35f, cy - h * 0.2f, cx + h * 0.35f, cy + h * 0.15f, fill)
                c.drawLine(cx, cy - h * 0.2f, cx, cy - h * 0.7f, line)
            }
        }
    }

    /**
     * 小地图：路线（走过的半亮、前面的全亮加粗）、路口（淡的细线）、下一个转弯点（圆圈）、当前位置（中下方的三角，前进方向朝上）。
     * 只画在 (left, top, w, h) 这个框里。
     */
    fun miniMap(c: Canvas, route: List<NavPoint>, junctions: List<NavJunction>, here: NavPoint, course: Double?, facing: Double?, toTurn: Int?, left: Float, top: Float, w: Float, h: Float, dim: Boolean) {
        val (seg, on) = NavMap.snap(route, here, course)
        val heading = NavMap.headingAt(route, seg, on)
        val cx = left + w / 2; val cy = top + h * 0.78f
        val mpp = NavMap.scaleFor(toTurn, (cy - top - 14).toInt())
        fun pt(p: NavPoint) = NavMap.toScreen(on, p, heading, mpp, cx, cy)
        c.save()
        c.clipRect(left, top, left + w, top + h)
        val past = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF606060.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val ahead = Paint(past).apply { color = if (dim) 0xFFA0A0A0.toInt() else 0xFFFFFFFF.toInt(); strokeWidth = 8f }
        fun path(points: List<NavPoint>) = Path().apply {
            points.forEachIndexed { i, p -> val (x, y) = pt(p); if (i == 0) moveTo(x, y) else lineTo(x, y) }
        }
        // 路口：路线以外的路，用淡的细线
        val side = Paint(past).apply { strokeWidth = 3f }
        for (j in junctions) {
            val (jx, jy) = pt(j.at)
            for (b in j.stubs) { val (ex, ey) = pt(NavMap.offset(j.at, b, maxOf(NavJunctions.STUB, 14 * mpp))); c.drawLine(jx, jy, ex, ey, side) }
        }
        c.drawPath(path(route.subList(0, seg + 1) + on), past)
        c.drawPath(path(listOf(on) + route.subList(seg + 1, route.size)), ahead)
        // 下一个转弯点
        if (toTurn != null && toTurn > 0) {
            val (tx, ty) = pt(NavMap.pointAlong(route, seg, on, toTurn.toDouble()))
            c.drawCircle(tx, ty, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
            c.drawCircle(tx, ty, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f })
        }
        // 当前位置：黑底上的白色三角，指向人面朝的方向（指南针），没有时用走路的方向（GPS）；和路线方向不一样时会歪
        c.drawCircle(cx, cy, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        val tilt = (facing ?: course)?.let { ((it - heading) % 360 + 360) % 360 }?.toFloat() ?: 0f
        c.save()
        c.rotate(tilt, cx, cy)
        c.drawPath(Path().apply { moveTo(cx, cy - 12); lineTo(cx + 9, cy + 9); lineTo(cx, cy + 4); lineTo(cx - 9, cy + 9); close() },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        c.restore()
        c.restore()
    }

    /** 从 (x0,y0) 指向 (x1,y1) 的实心箭头，尖在 (x1,y1)。 */
    private fun head(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, sw: Float, p: Paint) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: return
        val ux = dx / len; val uy = dy / len
        val hl = sw * 1.9f; val hw = sw * 1.55f
        val bx = x1 - ux * hl; val by = y1 - uy * hl
        val path = Path().apply {
            moveTo(x1 + ux * sw * 0.2f, y1 + uy * sw * 0.2f)
            lineTo(bx - uy * hw, by + ux * hw)
            lineTo(bx + uy * hw, by - ux * hw)
            close()
        }
        c.drawPath(path, p)
    }
}
