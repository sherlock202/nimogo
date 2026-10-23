package io.github.yingdu

import java.net.URI
import java.net.URLDecoder
import java.util.Calendar

/*
 * NimoGo 导航里不依赖 Android / Navigation SDK 的部分（方便单元测试）：
 *  - 从 Google Maps「共有 / 分享」出来的文字和链接里找出目的地；
 *  - 距离、时间的写法；
 *  - SDK 的转向类型（Maneuver）对应眼镜上画哪种箭头；
 *  - 每月目的地次数（Navigation SDK 按目的地计费，每月前 1000 个免费）。
 */

/** 出行方式（和 Navigation SDK 的 RoutingOptions.TravelMode 对应，见 NavGuide）。 */
enum class NavMode(val zh: String) { WALKING("步行"), DRIVING("驾车"), CYCLING("骑行") }

/**
 * 目的地：有坐标就用坐标，其次 Place ID，都没有就是一段要查的文字（交给系统的地理编码）。
 * [title] 只用来显示。
 */
data class NavDest(val title: String, val lat: Double? = null, val lng: Double? = null,
                   val placeId: String? = null, val query: String? = null, val address: String? = null,
                   /** 只有 Place ID、没有坐标时，搜索结果给的距离（米），确认画面上显示用。 */
                   val distanceHint: Int? = null) {
    val hasPoint get() = lat != null && lng != null
    /** 能直接交给 Navigation SDK（不用再查）。 */
    val ready get() = hasPoint || placeId != null
}

object NavLinks {
    private val URL_RE = Regex("""https?://[^\s<>"'）)]+""")
    private val PLACE_3D4D = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val AT_LATLNG = Regex("""/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val LATLNG = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

    /** 分享文字里的第一个链接。 */
    fun findUrl(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', '。', '、')

    /** 需要先跟一下跳转才知道地点的短链接（maps.app.goo.gl 等）。 */
    fun isShortLink(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "goo.gl" || host.endsWith(".goo.gl") || host == "g.co"
    }

    /** 分享文字里除了链接以外的第一行，当作地点名（Google Maps 有时写成「名称 · 地址」，只取名称）。 */
    fun titleOf(text: String): String {
        val line = text.lines().map { URL_RE.replace(it, "").trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return line.substringBefore(" · ").substringBefore(" ⋅ ").trim().trim('"', '「', '」')
    }

    /**
     * 解析一个（已经展开的）Google Maps 链接。按精确程度依次找：
     * 地点坐标（!3d…!4d…）→ Place ID → 参数里的「纬度,经度」→ 参数或路径里的地名 → 地图中心（/@纬度,经度）。
     */
    fun parseUrl(url: String, fallbackTitle: String = ""): NavDest? {
        // 跳转地址里的地名有时没编码（空格、日文…），直接解析会失败：先把这些字符转成 %XX
        val u = runCatching { URI(url) }.getOrNull() ?: runCatching { URI(safeUrl(url)) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (!host.contains("google.") && !host.startsWith("maps.") && !host.contains("goo.gl")) return null
        val q = query(u.rawQuery)
        // 欧洲等地先跳到「同意 Cookie」页面，真正的地址在 continue 参数里
        if (host.startsWith("consent.")) return q["continue"]?.let { parseUrl(it, fallbackTitle) }
        val path = runCatching { URLDecoder.decode(u.rawPath ?: "", "UTF-8") }.getOrDefault(u.path ?: "")
        val placeName = Regex("""/place/([^/]+)""").find(path)?.groupValues?.get(1)?.replace('+', ' ')?.trim().orEmpty()
        val title = fallbackTitle.ifEmpty { placeName }

        PLACE_3D4D.find(url)?.let { m ->
            return NavDest(title.ifEmpty { "地图上的地点" }, m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        val pid = q["query_place_id"] ?: q["destination_place_id"]
            ?: listOf("q", "query", "destination", "daddr").firstNotNullOfOrNull { k -> q[k]?.takeIf { it.startsWith("place_id:") }?.removePrefix("place_id:") }
        if (!pid.isNullOrBlank()) return NavDest(title.ifEmpty { q["query"] ?: q["q"]?.takeUnless { it.startsWith("place_id:") } ?: "地图上的地点" }, placeId = pid.trim())
        for (k in listOf("destination", "daddr", "q", "query", "ll")) {
            val v = q[k] ?: continue
            point(v)?.let { (lat, lng, label) -> return NavDest(title.ifEmpty { label ?: "地图上的地点" }, lat, lng) }
        }
        for (k in listOf("destination", "daddr", "q", "query")) {
            val v = q[k]?.trim().orEmpty()
            if (v.isNotEmpty()) return NavDest(title.ifEmpty { v }, query = v)
        }
        if (placeName.isNotEmpty()) return NavDest(title, query = placeName)
        // 路线链接（/maps/dir/起点/终点/@…）：@ 后面是地图中心不是终点，用路径里最后一个地点名
        if (path.contains("/dir/")) {
            val last = path.substringAfter("/dir/").split('/').map { it.replace('+', ' ').trim() }
                .lastOrNull { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("data=") }
            if (last != null) return point(last)?.let { (lat, lng, _) -> NavDest(title.ifEmpty { "地图上的地点" }, lat, lng) }
                ?: NavDest(title.ifEmpty { last }, query = last)
            return null
        }
        AT_LATLNG.find(path)?.let { m ->
            return NavDest(title.ifEmpty { "地图上的地点" }, m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        return null
    }

    /** 把 URI 里不允许直接出现的字符（空格、中日文、| 等）转成 %XX。 */
    fun safeUrl(url: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < url.length) {
            val cp = url.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x21..0x7E && cp.toChar() !in "\"<>\\^`{|}") sb.append(cp.toChar())
            else for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) sb.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val CONTENT_ATTR = Regex("""content\s*=\s*"([^"]*)"|content\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE)
    /** 静态地图里先找标记点（markers），没有再用地图中心（center）。 */
    private val MARKERS = listOf("markers", "center").map { Regex("""(?:^|[?&])$it=(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)""") }

    private fun meta(html: String, name: String): String? {
        val nameRe = Regex("""(?:property|name|itemprop|http-equiv)\s*=\s*["']${Regex.escape(name)}["']""", RegexOption.IGNORE_CASE)
        val tag = META_TAG.findAll(html).map { it.value }.firstOrNull { nameRe.containsMatchIn(it) } ?: return null
        val m = CONTENT_ATTR.find(tag) ?: return null
        return unescape(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** 页面里的「&lt;meta http-equiv=refresh content="0;url=…"&gt;」跳转地址。 */
    fun refreshUrl(html: String): String? =
        meta(html, "refresh")?.let { Regex("""url\s*=\s*['"]?([^'"\s]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }

    /**
     * 链接本身看不出地点时（只有 ftid / cid、地名对不上……），从 Google Maps 的网页里找：
     * 预览图（og:image）是以地点为中心的静态地图，带着「markers=纬度,经度」；标题（og:title）是「名称 · 地址」。
     */
    fun fromPage(html: String, fallbackTitle: String = ""): NavDest? {
        val ogTitle = meta(html, "og:title")?.substringBefore(" · ")?.substringBefore(" ⋅ ")?.trim()
            ?.takeUnless { it.isEmpty() || it.equals("Google Maps", true) || it == "Google マップ" || it == "Google 地图" }
        val title = fallbackTitle.ifEmpty { ogTitle.orEmpty() }
        meta(html, "og:image")?.let { img ->
            val d = runCatching { URLDecoder.decode(img, "UTF-8") }.getOrDefault(img)
            MARKERS.firstNotNullOfOrNull { it.find(d) }?.let { m ->
                val lat = m.groupValues[1].toDouble(); val lng = m.groupValues[2].toDouble()
                if (lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0))
                    return NavDest(title.ifEmpty { "地图上的地点" }, lat, lng)
            }
        }
        return if (title.isNotEmpty()) NavDest(title, query = title) else null
    }

    /** geo: 链接（其他 app 用「用地图打开」时发来的）：geo:纬度,经度?q=纬度,经度(名称) 或 geo:0,0?q=地址。 */
    fun parseGeo(uri: String): NavDest? {
        if (!uri.startsWith("geo:", ignoreCase = true)) return null
        val body = uri.substring(4)
        val pointPart = body.substringBefore('?').substringBefore(';')
        val q = query(body.substringAfter('?', ""))["q"]?.trim().orEmpty()
        point(q)?.let { (lat, lng, label) -> return NavDest(label ?: "地图上的地点", lat, lng) }
        if (q.isNotEmpty()) return NavDest(q, query = q)
        val p = point(pointPart) ?: return null
        if (p.first == 0.0 && p.second == 0.0) return null
        return NavDest("地图上的地点", p.first, p.second)
    }

    /** 「35.68,139.76」或「35.68,139.76(名称)」。 */
    fun point(s: String): Triple<Double, Double, String?>? {
        val m = LATLNG.find(s) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return Triple(lat, lng, m.groupValues[3].trim().ifEmpty { null })
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            val dk = runCatching { URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)
            if (dk !in out) out[dk] = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        return out
    }
}

object NavText {
    /** 眼镜上的距离：100 米内按 5 米、1 公里内按 10 米、10 公里内一位小数（数字变化少，眼镜不用频繁刷新）。 */
    fun distance(m: Int): String = when {
        m < 0 -> ""
        m < 100 -> "${(m + 2) / 5 * 5} 米"
        m < 995 -> "${(m + 5) / 10 * 10} 米"
        m < 9950 -> String.format(java.util.Locale.US, "%.1f 公里", m / 1000.0)
        else -> "${(m + 500) / 1000} 公里"
    }

    fun duration(sec: Int): String = when {
        sec < 60 -> "不到 1 分钟"
        sec < 3600 -> "${(sec + 30) / 60} 分钟"
        else -> { val min = (sec + 30) / 60; "${min / 60} 小时" + if (min % 60 > 0) " ${min % 60} 分" else "" }
    }

    /** 预计到达时刻（HH:mm）。 */
    fun eta(nowMillis: Long, sec: Int, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = nowMillis + sec * 1000L }
        return String.format(java.util.Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}

/** 眼镜上画的转向箭头。 */
enum class NavIcon { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, KEEP_LEFT, KEEP_RIGHT,
    UTURN_LEFT, UTURN_RIGHT, ROUNDABOUT_CW, ROUNDABOUT_CCW, DESTINATION, DEPART, FERRY, UNKNOWN;

    companion object {
        /**
         * Navigation SDK 的 Maneuver 值（com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver，
         * 数值照官方参考文档抄的，这里不直接引用 SDK，方便单元测试）。
         */
        fun of(maneuver: Int): NavIcon = when (maneuver) {
            1 -> DEPART
            2, 3, 4 -> DESTINATION
            5, 65 -> STRAIGHT
            6 -> LEFT; 7 -> RIGHT
            8, 17, 19 -> KEEP_LEFT; 9, 18, 20 -> KEEP_RIGHT
            10 -> SLIGHT_LEFT; 11 -> SLIGHT_RIGHT
            12 -> SHARP_LEFT; 13 -> SHARP_RIGHT
            14 -> UTURN_RIGHT; 15 -> UTURN_LEFT          // 顺时针掉头 = 往右绕（靠左行驶的日本），逆时针 = 往左绕
            16, 21, 32 -> STRAIGHT
            22, 33 -> LEFT; 23, 34 -> RIGHT
            24, 26, 35, 37 -> KEEP_LEFT; 25, 27, 36, 38 -> KEEP_RIGHT
            28, 39 -> SHARP_LEFT; 29, 40 -> SHARP_RIGHT
            30, 41 -> UTURN_RIGHT; 31, 42 -> UTURN_LEFT
            in 43..62 -> if (maneuver % 2 == 1) ROUNDABOUT_CW else ROUNDABOUT_CCW
            63, 64 -> FERRY
            else -> UNKNOWN
        }

        /** 文字说明里用的方向（导航页的「后续步骤」）。 */
        fun zh(i: NavIcon) = when (i) {
            STRAIGHT -> "直行"; LEFT -> "左转"; RIGHT -> "右转"; SLIGHT_LEFT -> "稍向左"; SLIGHT_RIGHT -> "稍向右"
            SHARP_LEFT -> "向左急转"; SHARP_RIGHT -> "向右急转"; KEEP_LEFT -> "靠左"; KEEP_RIGHT -> "靠右"
            UTURN_LEFT, UTURN_RIGHT -> "掉头"; ROUNDABOUT_CW, ROUNDABOUT_CCW -> "环岛"; DESTINATION -> "到达"
            DEPART -> "出发"; FERRY -> "轮渡"; UNKNOWN -> ""
        }
    }
}

/** 导航的一步（从 SDK 的 StepInfo 转过来）。 */
data class NavStep(val maneuver: Int, val road: String, val instruction: String, val meters: Int?)

/**
 * 某一刻的导航状态（从 SDK 的 NavInfo 转过来，大约每秒一次）。
 * @param state NavState：0 未知 / 1 导航中 / 2 重新规划路线 / 3 已停止
 */
data class NavSnap(val state: Int, val current: NavStep?, val toStepMeters: Int?, val toStepSeconds: Int?,
                   val remainMeters: Int?, val remainSeconds: Int?, val next: List<NavStep>) {
    /** 眼镜画面上会显示的内容；不变就不用重画。 */
    fun displayKey(): String = listOf(state, current?.maneuver, current?.road, current?.instruction,
        toStepMeters?.let { NavText.distance(it) }, remainMeters?.let { NavText.distance(it) },
        remainSeconds?.let { (it + 30) / 60 }, next.take(3).joinToString("|") { "${it.maneuver}${it.road}${it.meters?.let { m -> NavText.distance(m) }}" })
        .joinToString("/")

    companion object {
        const val UNKNOWN = 0
        const val ENROUTE = 1
        const val REROUTING = 2
        const val STOPPED = 3
    }
}

/** 剩余距离 / 时间的来源选择（不依赖 SDK，方便单元测试）。 */
object NavLogicFix {
    /** 超过这些就当作无效值（实测走 4 分钟的路，逐向数据报了 18183 公里、4096 小时）。 */
    const val MAX_METERS = 2_000_000
    const val MAX_SECONDS = 3 * 24 * 3600

    /** 有 Navigator 算的剩余距离和时间（大于 0）就用它；否则用逐向数据里的值，明显不合理的不显示。 */
    fun remaining(s: NavSnap, meters: Int?, seconds: Int?): NavSnap {
        if (meters != null && meters > 0 && seconds != null && seconds > 0) return s.copy(remainMeters = meters, remainSeconds = seconds)
        val m = s.remainMeters?.takeIf { it in 0..MAX_METERS }
        val sec = s.remainSeconds?.takeIf { it in 0..MAX_SECONDS }
        return if (m == s.remainMeters && sec == s.remainSeconds) s else s.copy(remainMeters = m, remainSeconds = sec)
    }
}

/** 每月的目的地次数（本机自己数的，和 Google Cloud 后台的计费可能有出入）。 */
object NavBudget {
    fun monthKey(millis: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = millis }
        return String.format(java.util.Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** 记录的月份不是本月就从 0 算起。 */
    fun used(savedMonth: String, savedCount: Int, nowMonth: String) = if (savedMonth == nowMonth) savedCount else 0

    /** 再导航 n 个目的地会不会超过上限（上限 0 = 不限制）。 */
    fun allowed(used: Int, limit: Int, n: Int = 1) = limit <= 0 || used + n <= limit
}

/**
 * 到了没有。SDK 的到达事件（ArrivalListener）实测有时不来，或者 SDK 自己停了引导、逐向数据变成「已停止」，
 * 眼镜就一直停在最后一步。这里用剩余距离补上。
 */
object NavArrive {
    /** 离终点多近算到了（米）。 */
    fun near(mode: NavMode) = when (mode) { NavMode.WALKING -> 15; NavMode.CYCLING -> 25; NavMode.DRIVING -> 40 }

    /** @param toRouteEnd 当前位置离路线终点的直线距离（米），不知道时为 null */
    fun reached(s: NavSnap, toRouteEnd: Double?, mode: NavMode): Boolean {
        val n = near(mode)
        val remain = s.remainMeters
        if (remain != null && remain in 1..n) return true
        if (toRouteEnd != null && toRouteEnd <= n) return true
        val lastStep = s.current?.let { NavIcon.of(it.maneuver) == NavIcon.DESTINATION } == true
        if (lastStep && (s.toStepMeters ?: Int.MAX_VALUE) <= n) return true
        // SDK 停了引导：已经在最后一步、或离终点不到 100 米，就当作到了
        if (s.state == NavSnap.STOPPED && (lastStep || (remain != null && remain <= 100))) return true
        return false
    }
}

/** 地名查到的一个地点（从系统地理编码的结果转过来）。 */
data class NavPlace(val point: NavPoint, val name: String, val address: String,
                    val locality: String = "", val admin: String = "", val country: String = "")

/** 当前所在的城市、都道府县 / 州、国家（ISO 代码）。 */
data class NavArea(val locality: String, val admin: String, val country: String)

/**
 * 同名地点怎么挑（「上野」在别的地方、别的国家也有）：
 * 先找所在城市里的（同一个市区町村，或同一个都道府县 / 州且在 60 公里内），没有再找本国的，再没有才用全世界的；
 * 同一范围里有好几个时，按离当前位置的远近排好，让用户自己选。
 */
object NavPick {
    enum class Scope { CITY, COUNTRY, WORLD }

    /** 200 米以内的当作同一个地点（同一个地名常常查出好几条几乎一样的结果）。 */
    fun dedupe(list: List<NavPlace>): List<NavPlace> {
        val out = ArrayList<NavPlace>()
        for (p in list) if (out.none { NavMap.greatCircle(it.point, p.point) < 200 }) out.add(p)
        return out
    }

    fun choose(found: List<NavPlace>, here: NavPoint?, area: NavArea?): Pair<Scope, List<NavPlace>> {
        val all = dedupe(found).let { l -> if (here == null) l else l.sortedBy { NavMap.greatCircle(here, it.point) } }
        if (area == null) return Scope.WORLD to all
        // 不知道精确位置（只从手机网络知道在哪个国家）时跳过城市这一级
        val city = if (here == null) emptyList() else all.filter {
            (area.locality.isNotEmpty() && it.locality == area.locality) ||
                (area.admin.isNotEmpty() && it.admin == area.admin && NavMap.greatCircle(here, it.point) <= 60_000)
        }
        if (city.isNotEmpty()) return Scope.CITY to city
        val country = all.filter { area.country.isNotEmpty() && it.country.equals(area.country, true) }
        if (country.isNotEmpty()) return Scope.COUNTRY to country
        return Scope.WORLD to all
    }

    /** 离这么远（米）就提醒「可能找错了地方」。 */
    fun suspicious(meters: Double, mode: NavMode) = meters > when (mode) { NavMode.WALKING -> 50_000.0; NavMode.CYCLING -> 150_000.0; NavMode.DRIVING -> 800_000.0 }
}

/** 「输入地点」列表里的一项。 */
data class NavChoice(val name: String, val address: String, val meters: Int?, val dest: NavDest)

/**
 * Google Places API (New) 的 Autocomplete：和 Google Maps 的搜索框一样，中文、日文都能搜（「东京站」也能找到東京駅）。
 * 只用到 Place ID、名称、地址和距离；Place ID 直接交给 Navigation SDK 算路线。
 */
object NavPlacesApi {
    const val URL = "https://places.googleapis.com/v1/places:autocomplete"

    /** 请求内容：知道国家就只搜这个国家；知道位置就优先附近（50 公里）并算出距离。 */
    fun body(q: String, here: NavPoint?, country: String?, lang: String): String {
        val o = org.json.JSONObject().put("input", q).put("languageCode", lang)
        if (!country.isNullOrEmpty()) {
            o.put("includedRegionCodes", org.json.JSONArray().put(country.lowercase()))
            o.put("regionCode", country.lowercase())
        }
        if (here != null) {
            val c = org.json.JSONObject().put("latitude", here.lat).put("longitude", here.lng)
            o.put("origin", c)
            o.put("locationBias", org.json.JSONObject().put("circle", org.json.JSONObject().put("center", c).put("radius", 50_000.0)))
        }
        return o.toString()
    }

    /** 解析返回的地点（按距离排；没有距离的放后面）。 */
    fun parse(json: String, q: String): List<NavChoice> {
        val arr = org.json.JSONObject(json).optJSONArray("suggestions") ?: return emptyList()
        val out = ArrayList<NavChoice>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i)?.optJSONObject("placePrediction") ?: continue
            val id = p.optString("placeId")
            if (id.isEmpty()) continue
            val sf = p.optJSONObject("structuredFormat")
            val name = sf?.optJSONObject("mainText")?.optString("text").orEmpty()
                .ifEmpty { p.optJSONObject("text")?.optString("text").orEmpty() }.ifEmpty { q }
            val addr = sf?.optJSONObject("secondaryText")?.optString("text").orEmpty()
            val m = if (p.has("distanceMeters")) p.optInt("distanceMeters") else null
            out.add(NavChoice(name, addr, m, NavDest(name, placeId = id, query = q, address = addr, distanceHint = m)))
        }
        return out.withIndex().sortedWith(compareBy({ it.value.meters == null }, { it.value.meters ?: 0 }, { it.index })).map { it.value }
    }

    /** 出错时给人看的原因。 */
    fun errorText(code: Int, body: String): String {
        val err = runCatching { org.json.JSONObject(body).optJSONObject("error") }.getOrNull()
        val msg = err?.optString("message").orEmpty()
        return when {
            code == 403 && (msg.contains("has not been used") || msg.contains("is disabled") || msg.contains("SERVICE_DISABLED")) ->
                "API Key 所在的项目还没启用 Places API (New)"
            code == 403 -> "API Key 不允许用 Places API (New)（Key 的「API 限制」里要勾上它）"
            code == 429 -> "Places API 超出了配额"
            else -> "Places API 出错（$code${if (msg.isNotEmpty()) "：$msg" else ""}）"
        }
    }
}

