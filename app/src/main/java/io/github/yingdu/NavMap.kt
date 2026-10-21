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

    /** 当前位置落在路线的哪一段上：(段号 i，表示 i 到 i+1 之间；段上最近的点)。 */
    fun snap(route: List<NavPoint>, p: NavPoint): Pair<Int, NavPoint> {
        if (route.size < 2) return 0 to (route.firstOrNull() ?: p)
        var best = 0; var bestPt = route[0]; var bestD = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val a = route[i]; val b = route[i + 1]
            val (bx, by) = local(a, b); val (px, py) = local(a, p)
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val q = NavPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            val d = dist(q, p)
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
