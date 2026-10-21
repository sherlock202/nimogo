package io.github.yingdu

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 地图上的一个点（纬度、经度）。 */
data class NavPoint(val lat: Double, val lng: Double)

/**
 * 眼镜小地图的几何计算（不依赖 Android，方便单元测试）：
 * 以当前位置为中心、路线前进方向朝上，把路线投影到屏幕上。几百米范围内按平面近似就够准。
 */
object NavMap {
    private const val M_PER_DEG_LAT = 110_574.0
    private const val M_PER_DEG_LNG_EQ = 111_320.0

    /** 以 o 为原点的平面坐标（米）：x 向东、y 向北。 */
    fun local(o: NavPoint, p: NavPoint): Pair<Double, Double> =
        (p.lng - o.lng) * cos(o.lat * PI / 180) * M_PER_DEG_LNG_EQ to (p.lat - o.lat) * M_PER_DEG_LAT

    fun dist(a: NavPoint, b: NavPoint): Double = local(a, b).let { (x, y) -> hypot(x, y) }

    /** 从 a 到 b 的方向（度，正北 0、顺时针）。 */
    fun bearing(a: NavPoint, b: NavPoint): Double = local(a, b).let { (x, y) -> (atan2(x, y) * 180 / PI + 360) % 360 }

    /** 两个方向相差多少度（0..180）。 */
    fun angleDiff(a: Double, b: Double): Double { val d = ((a - b) % 360 + 360) % 360; return if (d > 180) 360 - d else d }

    /**
     * 当前位置落在路线的哪一段上：(段号 i，表示 i 到 i+1 之间；段上最近的点)。
     * 知道走路的方向（course，度）时，方向对得上的段优先：刚拐过路口、位置还没离开旧路几米时，也能马上认到新的那条路上。
     */
    fun snap(route: List<NavPoint>, p: NavPoint, course: Double? = null): Pair<Int, NavPoint> {
        if (route.size < 2) return 0 to (route.firstOrNull() ?: p)
        var best = 0; var bestPt = route[0]; var bestD = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val a = route[i]; val b = route[i + 1]
            val (bx, by) = local(a, b); val (px, py) = local(a, p)
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val q = NavPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            var d = dist(q, p)
            // 方向每差 90 度当作远了 10 米（只在 25 米以内的段之间比较方向）
            if (course != null && len2 > 1.0 && d < 25) d += angleDiff(course, bearing(a, b)) / 90.0 * 10.0
            if (d < bestD) { bestD = d; best = i; bestPt = q }
        }
        return best to bestPt
    }

    /** 沿路线从 (段 seg 上的点 from) 往前走 meters 米到的点。 */
    fun pointAlong(route: List<NavPoint>, seg: Int, from: NavPoint, meters: Double): NavPoint {
        var left = meters; var cur = from; var i = seg
        while (i < route.size - 1) {
            val next = route[i + 1]
            val d = dist(cur, next)
            if (d >= left && d > 0) {
                val t = left / d
                return NavPoint(cur.lat + (next.lat - cur.lat) * t, cur.lng + (next.lng - cur.lng) * t)
            }
            left -= d; cur = next; i++
        }
        return route.lastOrNull() ?: from
    }

    /**
     * 当前所在这条路的前进方向：用所在这一段的方向（不越过下一个拐角，免得快到路口时地图提前歪过去）；
     * 这一段只剩 3 米以内时用下一段（跳过长度为 0 的段）。
     */
    fun headingAt(route: List<NavPoint>, seg: Int, from: NavPoint): Double {
        if (seg + 1 < route.size && dist(from, route[seg + 1]) > 3.0) return bearing(from, route[seg + 1])
        for (i in seg until route.size - 1) if (dist(route[i], route[i + 1]) > 1.0) return bearing(route[i], route[i + 1])
        return 0.0
    }

    /** 从 p 往 bearingDeg 方向走 m 米到的点。 */
    fun offset(p: NavPoint, bearingDeg: Double, m: Double): NavPoint {
        val r = bearingDeg * PI / 180
        return NavPoint(p.lat + m * cos(r) / M_PER_DEG_LAT, p.lng + m * sin(r) / (M_PER_DEG_LNG_EQ * cos(p.lat * PI / 180)))
    }

    /** 路线上每个点离起点的累计距离（米）。 */
    fun cumulative(route: List<NavPoint>): DoubleArray {
        val c = DoubleArray(route.size)
        for (i in 1 until route.size) c[i] = c[i - 1] + dist(route[i - 1], route[i])
        return c
    }

    /** 离起点 s 米处的点（超出范围就取两头）。 */
    fun at(route: List<NavPoint>, cum: DoubleArray, s: Double): NavPoint {
        if (route.isEmpty()) return NavPoint(0.0, 0.0)
        if (s <= 0) return route.first()
        for (i in 1 until route.size) if (cum[i] >= s) {
            val len = cum[i] - cum[i - 1]
            val t = if (len <= 0) 0.0 else (s - cum[i - 1]) / len
            val a = route[i - 1]; val b = route[i]
            return NavPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
        }
        return route.last()
    }

    /** 投影到屏幕：中心点 c 在 (cx, cy)，heading 方向朝上，每像素 mpp 米。 */
    fun toScreen(c: NavPoint, p: NavPoint, headingDeg: Double, mpp: Double, cx: Float, cy: Float): Pair<Float, Float> {
        val (dx, dy) = local(c, p)
        val h = headingDeg * PI / 180
        val x = dx * cos(h) - dy * sin(h)
        val y = dx * sin(h) + dy * cos(h)
        return (cx + x / mpp).toFloat() to (cy - y / mpp).toFloat()
    }

    private val SCALES = doubleArrayOf(0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0)

    /** 比例尺（米/像素）：让下一个转弯点落在当前位置上方 view 像素以内；取固定的几档，免得每秒缩放抖动。 */
    fun scaleFor(metersToTurn: Int?, view: Int): Double {
        val want = ((metersToTurn ?: 200).coerceAtLeast(20) * 1.3) / view
        return SCALES.firstOrNull { it >= want } ?: SCALES.last()
    }
}

/**
 * 手机指南针 → 人面朝的方向。手机竖着放在胸前口袋时，屏幕垂直于地面，背面（或屏幕）朝着人的正前方；
 * 手机朝向和人的朝向差多少度（0 度左右或 180 度左右，看手机怎么放），走路时用 GPS 方向自动学出来。
 * 学到的差值前后一致才用；手机换了放法，几步之内就会发现对不上，先不用，重新学。
 */
class NavFacing(offset: Double? = null) {
    private var sx = 0.0
    private var sy = 0.0
    var samples = 0; private set

    init {
        // 上次学到的差值：先当作有把握，但只要对不上就先不用
        if (offset != null) { val r = offset * PI / 180; sx = cos(r) * 0.9; sy = sin(r) * 0.9; samples = 5 }
    }

    /** 连续对不上几次了（手机可能换了放法）。 */
    private var misses = 0

    /** 走路时：GPS 方向 course 和手机方向 phone 同时有，学两者的差。 */
    fun learn(course: Double, phone: Double) {
        val diff = ((course - phone) % 360 + 360) % 360
        // 已经学会了、却差了 60 度以上：偶尔一次当作噪声跳过；连续 3 次就是手机换了放法，从头学
        if (samples >= 5 && hypot(sx, sy) > 0.5 && NavMap.angleDiff(diff, offset) > 60) {
            if (++misses < 3) return
            sx = 0.0; sy = 0.0; samples = 0
        }
        misses = 0
        val d = diff * PI / 180
        val a = if (samples < 10) 1.0 / (samples + 1) else 0.1
        sx += (cos(d) - sx) * a; sy += (sin(d) - sy) * a; samples++
    }

    /** 学到的差值够一致（样本里方向差的离散度小）。 */
    val confident get() = samples >= 5 && misses == 0 && hypot(sx, sy) > 0.8
    val offset: Double get() = (atan2(sy, sx) * 180 / PI + 360) % 360

    /** 人面朝的方向（度，正北 0）；还没把握时为 null。 */
    fun facing(phone: Double): Double? = if (confident) (phone + offset) % 360 else null

    companion object {
        /** 旋转矩阵（手机坐标 → 东/北/上，行优先 3×3）中，手机屏幕法线（z 轴）的水平方向；手机平放时没法用，返回 null。 */
        fun phoneHeading(r: FloatArray): Double? {
            val e = r[2].toDouble(); val n = r[5].toDouble()
            if (hypot(e, n) < 0.5) return null
            return (atan2(e, n) * 180 / PI + 360) % 360
        }
    }
}

/** 小地图上的一个路口：在 at 这个点，朝 stubs 这几个方向（度）各画一小段淡线，表示路线以外的路。 */
data class NavJunction(val at: NavPoint, val stubs: List<Double>)

/**
 * 路口的位置和形状。Google 导航只给这条路线的形状和每一步的转向，不给周围的路，
 * 所以这里按转向估一个大概的样子：转弯处画成十字（T 字路口也会画成十字），直行穿过的路口画一条横线，岔路画出另一条岔。
 */
object NavJunctions {
    /** 淡线画多长（米）。 */
    const val STUB = 22.0

    /** 带符号的转角（度）：正数往右，负数往左。 */
    fun turnAngle(inB: Double, outB: Double): Double = ((outB - inB) % 360 + 540) % 360 - 180

    /** 这个转向在路口画哪几条淡线（方向，度）。inB 进路口的方向，outB 出路口的方向。 */
    fun stubs(icon: NavIcon, inB: Double, outB: Double): List<Double> {
        val t = turnAngle(inB, outB)
        val list = when (icon) {
            NavIcon.LEFT, NavIcon.RIGHT, NavIcon.SHARP_LEFT, NavIcon.SHARP_RIGHT, NavIcon.SLIGHT_LEFT, NavIcon.SLIGHT_RIGHT ->
                listOf(inB, outB + 180)                       // 不转弯的话继续往前的路，和横路的另一半
            NavIcon.STRAIGHT -> listOf(inB + 90, inB - 90)     // 直行穿过的路口（过马路）：横着的路
            NavIcon.KEEP_LEFT, NavIcon.KEEP_RIGHT ->           // 岔路：另一条岔
                listOf(if (kotlin.math.abs(t) >= 10) inB - t else if (icon == NavIcon.KEEP_LEFT) inB + 30 else inB - 30)
            else -> emptyList()
        }.map { (it % 360 + 360) % 360 }
        // 和来路、去路重叠的就不画
        return list.filter { NavMap.angleDiff(it, (inB + 180) % 360) >= 30 && NavMap.angleDiff(it, outB) >= 30 }
    }

    /**
     * 接下来几步的路口：第一步在前方 toStep 米，后面每步再加上它的距离。只算前方 maxAhead 米以内的。
     * 转弯的位置对齐到附近拐得最厉害的路线点（路线的拐点就是路口，比逐向数据的距离准）。
     */
    fun upcoming(route: List<NavPoint>, here: NavPoint, course: Double?, toStep: Int?, steps: List<NavStep>, maxAhead: Double = 1500.0): List<NavJunction> {
        if (route.size < 2 || toStep == null || steps.isEmpty()) return emptyList()
        val cum = NavMap.cumulative(route)
        val (seg, on) = NavMap.snap(route, here, course)
        val start = cum[seg] + NavMap.dist(route[seg], on)
        val total = cum.last()
        val out = ArrayList<NavJunction>()
        var s = start + toStep
        steps.forEachIndexed { i, step ->
            if (i > 0) s += step.meters ?: return out
            if (s - start > maxAhead || s >= total - 1) return out
            val icon = NavIcon.of(step.maneuver)
            var at = s
            if (icon != NavIcon.STRAIGHT) {
                var best = 25.0
                for (k in 1 until route.size - 1) {
                    if (kotlin.math.abs(cum[k] - s) > 20 || cum[k] - cum[k - 1] < 1 || cum[k + 1] - cum[k] < 1) continue
                    val bend = NavMap.angleDiff(NavMap.bearing(route[k - 1], route[k]), NavMap.bearing(route[k], route[k + 1]))
                    if (bend > best) { best = bend; at = cum[k] }
                }
                s = at                                    // 后面几步的距离从对齐后的位置算
            }
            val p = NavMap.at(route, cum, at)
            val before = NavMap.at(route, cum, at - 12); val after = NavMap.at(route, cum, at + 12)
            val outB = NavMap.bearing(p, after)
            val inB = if (NavMap.dist(before, p) > 1) NavMap.bearing(before, p) else outB
            val st = stubs(icon, inB, outB)
            if (st.isNotEmpty()) out.add(NavJunction(p, st))
        }
        return out
    }

    /** 新算出来的路口替换掉 12 米以内的旧的；走过的路口留着（还在路线上的），最多留 40 个。 */
    fun merge(old: List<NavJunction>, new: List<NavJunction>, route: List<NavPoint>): List<NavJunction> {
        val keep = old.filter { o ->
            new.none { NavMap.dist(it.at, o.at) < 12 } &&
                route.size >= 2 && NavMap.dist(NavMap.snap(route, o.at).second, o.at) < 10
        }
        return (keep + new).takeLast(40)
    }
}

