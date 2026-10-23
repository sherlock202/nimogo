package io.github.yingdu

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import com.google.android.libraries.navigation.ArrivalEvent
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Navigation SDK 的薄封装（NimoGo）：
 *  - 第一次用时让用户同意 Google 导航服务条款（SDK 要求）；
 *  - 拿到 Navigator，算路线、开始 / 停止引导；
 *  - 打开「逐向导航数据流」：SDK 大约每秒把当前一步、距离、剩余时间发给 [NavInfoService]，再交给眼镜上的 [NavApp]。
 * 只在主线程调用。
 *
 * 计费：按「目的地」算，每次 setDestination 算一个；偏航、堵车后重算路线不算，开始引导和逐向数据也不另外收费。
 */
object NavGuide {
    private val main = Handler(Looper.getMainLooper())
    private var navigator: Navigator? = null
    private val waiting = ArrayList<(Navigator?, String?) -> Unit>()
    /** 收到逐向数据时交给谁（ReaderService 里的 NavApp）。 */
    var sink: ((NavSnap) -> Unit)? = null
    var onArrived: (() -> Unit)? = null
    /** 写到「我的 › 日志」（ReaderService 设置）。 */
    var log: ((String) -> Unit)? = null
    private fun note(msg: String) { main.post { log?.invoke("导航：$msg") } }

    // ---------- 诊断（导航页显示，方便排查「没有显示也不报错」） ----------
    /** 收到逐向数据的次数、最近一次的时间、最近的状态（0 未知 / 1 导航中 / 2 重新规划 / 3 停止）。 */
    var infoCount = 0; private set
    var lastInfoAt = 0L; private set
    var lastState = -1; private set
    var lastRegisterOk: Boolean? = null; private set

    // ---------- 小地图：路线形状和当前位置（都在主线程读写） ----------
    /** 当前这段路线的形状点（从起点到目的地）。 */
    var route: List<NavPoint> = emptyList(); private set
    /** 吸附到道路上的当前位置。 */
    var here: NavPoint? = null; private set
    /** 路线或位置变了（NavApp 用来重画小地图）。 */
    var onMapChanged: (() -> Unit)? = null
    private var locProvider: RoadSnappedLocationProvider? = null

    /** 走路的方向（度，正北 0，来自 GPS），只在走动时才有；站着不动时为 null。认路口用这个。 */
    var course: Double? = null; private set
    private var courseAt = 0L

    /**
     * 人面朝的方向（度，正北 0，来自手机指南针），站着原地转身也会变；手机放在胸前口袋时才准。
     * 走路时先用 GPS 方向学会「手机朝向 → 人的朝向」，学会之前为 null。小地图上的三角用这个。
     */
    var facing: Double? = null; private set

    private fun takeCourse(l: android.location.Location) {
        if (l.hasBearing() && l.hasSpeed() && l.speed >= 0.6f) {
            course = l.bearing.toDouble(); courseAt = System.currentTimeMillis()
            learnFacing(l)
        } else if (System.currentTimeMillis() - courseAt > 6_000) course = null
    }

    // ---------- 指南针 ----------

    private var sensors: SensorManager? = null
    private var prefs: android.content.SharedPreferences? = null
    private var facingModel = NavFacing()
    private var wasConfident = false
    private var phone: Double? = null
    private var phoneAt = 0L
    private var px = 0.0; private var py = 0.0; private var hasPhone = false
    private val rot = FloatArray(9)

    private val compass = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            runCatching { SensorManager.getRotationMatrixFromVector(rot, e.values) }.onFailure { return }
            val h = NavFacing.phoneHeading(rot)
            if (h == null) { phone = null; hasPhone = false; updateFacing(); return }
            val r = Math.toRadians(h)
            if (!hasPhone) { px = Math.cos(r); py = Math.sin(r); hasPhone = true }
            else { px += (Math.cos(r) - px) * 0.25; py += (Math.sin(r) - py) * 0.25 }   // 平滑掉走路时的晃动
            phone = (Math.toDegrees(Math.atan2(py, px)) + 360) % 360; phoneAt = System.currentTimeMillis()
            updateFacing()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** 走路、GPS 方向可靠时，学手机朝向和人的朝向差多少。 */
    private fun learnFacing(l: android.location.Location) {
        val p = phone ?: return
        if (System.currentTimeMillis() - phoneAt > 1_000 || l.speed < 0.8f) return
        if (l.hasBearingAccuracy() && l.bearingAccuracyDegrees > 30f) return
        facingModel.learn(l.bearing.toDouble(), p)
        if (facingModel.confident != wasConfident) {
            wasConfident = facingModel.confident
            note(if (wasConfident) "指南针：已对准（手机朝向差 ${facingModel.offset.toInt()} 度）" else "指南针：和走路方向对不上，重新学")
        }
        if (facingModel.samples % 10 == 0) saveFacing()
        updateFacing()
    }

    /** 方向变了 10 度以上才更新（免得指南针抖动让眼镜一直重画）。 */
    private fun updateFacing() {
        val f = phone?.let { facingModel.facing(it) }
        val old = facing
        if (f == null) { if (old != null) { facing = null; onMapChanged?.invoke() }; return }
        if (old == null || NavMap.angleDiff(old, f) >= 10.0) { facing = f; onMapChanged?.invoke() }
    }

    private fun saveFacing() {
        if (facingModel.confident) prefs?.edit()?.putFloat("facing_offset", facingModel.offset.toFloat())?.apply()
    }

    private fun startCompass(app: Application) {
        if (sensors != null) return
        prefs = app.getSharedPreferences("nav_compass", Context.MODE_PRIVATE)
        facingModel = NavFacing(prefs?.takeIf { it.contains("facing_offset") }?.getFloat("facing_offset", 0f)?.toDouble())
        wasConfident = facingModel.confident
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val s = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s == null) { note("这台手机没有指南针，只能走动时判断方向"); return }
        sensors = sm
        sm.registerListener(compass, s, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopCompass() {
        saveFacing()
        runCatching { sensors?.unregisterListener(compass) }
        sensors = null; phone = null; hasPhone = false; facing = null
    }

    private val locListener = object : RoadSnappedLocationProvider.LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            val p = NavPoint(location.latitude, location.longitude)
            main.post { here = p; takeCourse(location); onMapChanged?.invoke() }
        }
        // 原始 GPS 更新：只用来更新走路方向（位置仍用吸附到道路上的）
        override fun onRawLocationUpdate(location: android.location.Location) {
            main.post { val before = course; takeCourse(location); if (before != course) onMapChanged?.invoke() }
        }
    }

    private val routeChanged = object : Navigator.RouteChangedListener {
        override fun onRouteChanged() { main.post { navigator?.let { loadRoute(it) } } }
    }

    private fun loadRoute(n: Navigator) {
        val pts = runCatching { n.currentRouteSegment?.latLngs?.map { NavPoint(it.latitude, it.longitude) } }
            .onFailure { note("读路线形状出错：$it") }.getOrNull().orEmpty()
        if (pts.size != route.size) note("路线形状：${pts.size} 个点")
        route = pts
        onMapChanged?.invoke()
    }

    private fun startLocation(app: Application) {
        if (locProvider != null) return
        locProvider = runCatching { NavigationApi.getRoadSnappedLocationProvider(app) }.onFailure { note("拿不到位置：$it") }.getOrNull()
        runCatching { locProvider?.addLocationListener(locListener) }.onFailure { note("位置监听出错：$it") }
        runCatching { startCompass(app) }.onFailure { note("指南针出错：$it") }
    }

    private fun stopLocation() {
        runCatching { locProvider?.removeLocationListener(locListener) }
        locProvider = null
        runCatching { stopCompass() }
        here = null; route = emptyList(); course = null
    }
    val ready get() = navigator != null

    private var keyApplied: String? = null

    /**
     * 先把 API Key 交给 SDK，再调用 SDK 的任何功能：SDK 第一次被调用时就会在后台找 Key，
     * 那时还没有 Key 的话会直接让 app 崩溃（Key 不写在清单里，免得公开的安装包带着 Key）。
     * 没填 Key 时返回 false，调用方就不要碰 SDK。
     */
    fun useKey(key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        if (keyApplied != k) {
            runCatching { NavigationApi.setApiKey(k) }.onFailure { note("设置 API Key 出错：${it.message}") }
            keyApplied = k
        }
        return true
    }

    fun termsAccepted(app: Application, key: String): Boolean =
        useKey(key) && runCatching { NavigationApi.areTermsAccepted(app) }.getOrDefault(false)

    /** 弹出 Google 导航服务条款（SDK 自带的对话框）。 */
    fun askTerms(activity: Activity, key: String, done: (Boolean) -> Unit) {
        if (!useKey(key)) { done(false); return }
        note("显示 Google 导航服务条款")
        try {
            NavigationApi.showTermsAndConditionsDialog(activity, "NimoGo", "Google 导航服务条款",
                object : NavigationApi.OnTermsResponseListener {
                    override fun onTermsResponse(accepted: Boolean) { note(if (accepted) "已同意服务条款" else "没有同意服务条款"); done(accepted) }
                })
        } catch (e: Exception) {
            note("服务条款对话框出错：${e.javaClass.simpleName} ${e.message ?: ""}")
            done(false)
        }
    }

    /** 拿 Navigator（第一次要几秒）；失败时第二个参数是原因。API Key 只在第一次初始化前生效。 */
    fun withNavigator(app: Application, apiKey: String, cb: (Navigator?, String?) -> Unit) {
        navigator?.let { cb(it, null); return }
        waiting.add(cb)
        if (waiting.size > 1) return
        if (!useKey(apiKey)) { flush(null, "还没填 Google Maps Platform 的 API Key"); return }
        note("初始化 Navigator…")
        try { NavigationApi.getNavigator(app, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(n: Navigator) {
                note("Navigator 就绪（SDK ${runCatching { NavigationApi.getNavSDKVersion() }.getOrDefault("?")}）")
                navigator = n
                n.addArrivalListener(arrival)
                runCatching { n.addRouteChangedListener(routeChanged) }
                flush(n, null)
            }
            override fun onError(errorCode: Int) {
                note("Navigator 初始化失败，错误码 $errorCode")
                flush(null, when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> "API Key 没有开通 Navigation SDK（或 Key 填错了、项目没绑结算账号）"
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "还没有同意 Google 导航服务条款"
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "没有定位权限"
                    NavigationApi.ErrorCode.NETWORK_ERROR -> "网络连不上"
                    else -> "导航初始化失败（错误 $errorCode）"
                })
            }
        }) } catch (e: Exception) { note("getNavigator 出错：$e"); flush(null, "导航初始化失败（${e.message ?: e.javaClass.simpleName}）") }
    }

    private fun flush(n: Navigator?, err: String?) {
        val list = ArrayList(waiting); waiting.clear()
        main.post { list.forEach { it(n, err) } }
    }

    private val arrival = object : Navigator.ArrivalListener {
        override fun onArrival(e: ArrivalEvent) {
            note("SDK：到达事件（${if (e.isFinalDestination) "终点" else "途经点"}）")
            if (e.isFinalDestination) main.post { onArrived?.invoke() }
        }
    }

    fun travelMode(m: NavMode) = when (m) {
        NavMode.WALKING -> RoutingOptions.TravelMode.WALKING
        NavMode.DRIVING -> RoutingOptions.TravelMode.DRIVING
        NavMode.CYCLING -> RoutingOptions.TravelMode.CYCLING
    }

    class Options(val mode: NavMode, val voice: Boolean, val avoidTolls: Boolean, val avoidHighways: Boolean)

    /**
     * 算路线并开始引导。done(结果, 这次是否计费)：结果为 null 表示成功，否则是给人看的原因。
     * 计不计费照官方说明：OK、找不到路线、被新请求取消的算；网络、定位、配额、地点无效这类错误不算。
     */
    fun start(ctx: Context, nav: Navigator, dest: NavDest, opt: Options, done: (String?, Boolean) -> Unit) {
        val wp = runCatching {
            val b = Waypoint.builder().setTitle(dest.title.ifEmpty { "目的地" })
            when {
                dest.placeId != null -> try { b.setPlaceIdString(dest.placeId) }
                    catch (e: Waypoint.UnsupportedPlaceIdException) {
                        if (dest.hasPoint) b.setLatLng(dest.lat!!, dest.lng!!) else throw e
                    }
                dest.hasPoint -> b.setLatLng(dest.lat!!, dest.lng!!)
                else -> throw IllegalArgumentException("没有坐标")
            }
            b.build()
        }.getOrElse { done("这个地点没法导航（${it.message ?: it.javaClass.simpleName}）", false); return }
        val routing = RoutingOptions().travelMode(travelMode(opt.mode)).avoidTolls(opt.avoidTolls).avoidHighways(opt.avoidHighways)
        runCatching {
            nav.setAudioGuidance(if (opt.voice) Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE or Navigator.AudioGuidance.BLUETOOTH_AUDIO
                else Navigator.AudioGuidance.SILENT)
        }
        val reg = runCatching { nav.registerServiceForNavUpdates(ctx.packageName, NavInfoService::class.java.name, 3) }
        lastRegisterOk = reg.getOrNull()
        note("注册逐向数据服务：" + reg.fold({ if (it) "成功" else "失败（返回 false）" }, { "出错 $it" }))
        infoCount = 0; lastInfoAt = 0L; lastState = -1
        note("算路线：${dest.title}（" + (dest.placeId?.let { "Place ID $it" } ?: "%.5f, %.5f".format(dest.lat, dest.lng)) + "，${opt.mode.zh}）")
        nav.setDestination(wp, routing).setOnResultListener { status ->
            main.post {
                note("算路线结果：$status")
                val billed = status == Navigator.RouteStatus.OK || status == Navigator.RouteStatus.NO_ROUTE_FOUND ||
                    status == Navigator.RouteStatus.ROUTE_CANCELED
                if (status == Navigator.RouteStatus.OK) {
                    runCatching { nav.startGuidance() }.onFailure { note("开始引导出错：$it") }
                    note("开始引导：" + (if (runCatching { nav.isGuidanceRunning }.getOrDefault(false)) "引导中" else "引导没有运行"))
                    loadRoute(nav)
                    (ctx.applicationContext as? Application)?.let { startLocation(it) }
                    done(null, true); return@post
                }
                done(when (status) {
                    Navigator.RouteStatus.NO_ROUTE_FOUND -> "找不到路线（换个出行方式试试）"
                    Navigator.RouteStatus.NETWORK_ERROR -> "网络连不上"
                    Navigator.RouteStatus.QUOTA_CHECK_FAILED -> "配额不够（到 Google Cloud 控制台看 Navigation SDK 的配额）"
                    Navigator.RouteStatus.ROUTE_CANCELED -> "被新的导航请求取消了"
                    Navigator.RouteStatus.LOCATION_DISABLED -> "手机定位（GPS）关着"
                    Navigator.RouteStatus.LOCATION_UNKNOWN -> "还没定到位，到空旷的地方再试"
                    Navigator.RouteStatus.WAYPOINT_ERROR -> "目的地无效"
                    else -> "算路线失败（$status）"
                }, billed)
            }
        }
    }

    /** 停止引导、清掉目的地、不再接收逐向数据。 */
    fun stop() {
        stopLocation()
        val n = navigator ?: return
        runCatching { n.stopGuidance() }
        runCatching { n.clearDestinations() }
        runCatching { n.unregisterServiceForNavUpdates() }
    }

    fun guiding() = runCatching { navigator?.isGuidanceRunning == true }.getOrDefault(false)

    // ---------- 逐向数据 → NavSnap ----------

    private fun stepOf(s: StepInfo) = NavStep(s.maneuver, (s.fullRoadName ?: s.simpleRoadName).orEmpty(),
        s.fullInstructionText.orEmpty(), s.distanceFromPrevStepMeters)

    fun snapOf(i: NavInfo): NavSnap = NavSnap(
        i.navState, i.currentStep?.let { stepOf(it) }, i.distanceToCurrentStepMeters, i.timeToCurrentStepSeconds,
        i.distanceToFinalDestinationMeters, i.timeToFinalDestinationSeconds,
        i.remainingSteps?.map { stepOf(it) } ?: emptyList())

    fun deliver(raw: NavSnap) {
        main.post {
            infoCount++; lastInfoAt = System.currentTimeMillis()
            // 剩余距离和时间以 Navigator 自己算的为准：实测逐向数据里「到终点」的两个值明显不对
            // （显示成 18183 公里、4096 小时），到下一步的距离是对的；第一条数据时两边的原始值都写进日志
            val td = runCatching { navigator?.currentTimeAndDistance }.getOrNull()
            val snap = NavLogicFix.remaining(raw, td?.meters, td?.seconds)
            if (infoCount == 1) {
                note("收到第一条逐向数据（状态 ${raw.state}，" + (raw.current?.let { "下一步 ${it.instruction.ifEmpty { it.road }}" } ?: "没有当前步骤") + "）")
                note("剩余：逐向数据 ${raw.remainMeters} 米 / ${raw.remainSeconds} 秒；Navigator ${td?.meters} 米 / ${td?.seconds} 秒")
            }
            if (snap.state != lastState) { if (lastState >= 0) note("导航状态 $lastState → ${snap.state}"); lastState = snap.state }
            sink?.invoke(snap)
        }
    }

    fun readError(e: Throwable) = note("读逐向数据出错：$e")
}

/** 接收 Navigation SDK 发来的逐向数据（Message.what = MSG_NAV_INFO，大约每秒一次）。 */
class NavInfoService : Service() {
    private val tbt: TurnByTurnManager by lazy { TurnByTurnManager.createInstance() }

    private val messenger by lazy {
        Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != TurnByTurnManager.MSG_NAV_INFO) return
                val info = runCatching { tbt.readNavInfoFromBundle(msg.data) }.onFailure { NavGuide.readError(it) }.getOrNull() ?: return
                NavGuide.deliver(NavGuide.snapOf(info))
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}

/**
 * 把分享过来的东西变成目的地（后台线程，可能要联网）：
 * 短链接先跟着跳转拿到完整的 Google Maps 地址，再解析；只有地名时用系统的地理编码（免费，不走 Places API）。
 */
object NavResolve {
    /** 最近一次共享的链接展开后的地址（找不到地点时显示出来，方便排查）。 */
    @Volatile var lastExpanded: String? = null

    fun fromShare(ctx: Context, text: String, subject: String = "", near: NavPoint? = null, apiKey: String = ""): NavDest? {
        lastExpanded = null
        val title = NavLinks.titleOf(text).ifEmpty { subject.trim() }
        val url = NavLinks.findUrl(text)
        if (text.trim().startsWith("geo:", true)) return NavLinks.parseGeo(text.trim())?.let { geocode(ctx, it, near, apiKey) }
        if (url == null) return if (title.isNotEmpty()) geocode(ctx, NavDest(title, query = title), near, apiKey) else null
        // 短链接（maps.app.goo.gl）先跟着跳转；跳转失败也不放弃，后面还能读网页
        var full = if (NavLinks.isShortLink(url)) runCatching { expand(url) }.getOrDefault(url) else url
        var page: String? = null
        // 跳到的是一个用 <meta refresh> 再跳转的网页
        if (NavLinks.isShortLink(full) || NavLinks.parseUrl(full, title) == null) {
            page = runCatching { fetchPage(full) }.getOrNull()
            page?.let { NavLinks.refreshUrl(it) }?.let { next ->
                full = runCatching { java.net.URL(java.net.URL(full), next).toString() }.getOrDefault(next)
                page = null
            }
        }
        lastExpanded = full
        val fromUrl = NavLinks.parseUrl(full, title)
        if (fromUrl?.ready == true) return fromUrl
        // 链接里只有地名或 ftid / cid：读 Google Maps 网页，预览图里有地点坐标
        val html = page ?: runCatching { fetchPage(full) }.getOrNull()
        val fromHtml = html?.let { NavLinks.fromPage(it, fromUrl?.title?.takeUnless { t -> t == "地图上的地点" } ?: title) }
        if (fromHtml?.ready == true) return fromHtml
        val q = fromUrl ?: fromHtml ?: (if (title.isNotEmpty()) NavDest(title, query = title) else null) ?: return null
        return geocode(ctx, q, near, apiKey)
            ?: fromHtml?.takeIf { it !== q }?.let { geocode(ctx, it, near, apiKey) }
    }

    /**
     * 只有地名 / 地址：查一下取最合适的一个（共享过来的链接用这个；手动输入的用 [search] 让用户选）。
     * 有 API Key 时和手动搜索一样先用 Google 的地点搜索（店名、景点名查得到），没有才用手机系统的地理编码。
     */
    fun geocode(ctx: Context, d: NavDest, near: NavPoint? = null, apiKey: String = ""): NavDest? {
        if (d.ready) return d
        val q = d.query?.trim().orEmpty().ifEmpty { d.title }
        if (q.isEmpty()) return null
        return search(ctx, q, apiKey).choices.firstOrNull()?.dest?.let { c -> if (d.title.isNotEmpty() && d.title != "地图上的地点") c.copy(title = d.title) else c }
    }

    /** 读一个 Google Maps 网页（最多 2 MB），找不到坐标时从里面找。 */
    fun fetchPage(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.5")
        }
        try {
            c.inputStream.use { ins ->
                val buf = java.io.ByteArrayOutputStream()
                val b = ByteArray(16_384)
                while (buf.size() < 2_000_000) { val n = ins.read(b); if (n < 0) break; buf.write(b, 0, n) }
                return String(buf.toByteArray(), Charsets.UTF_8)
            }
        } finally { c.disconnect() }
    }

    /** 当前位置所在的城市、都道府县 / 州、国家。 */
    @Suppress("DEPRECATION")
    fun areaOf(ctx: Context, here: NavPoint): NavArea? {
        if (!Geocoder.isPresent()) return null
        val a = runCatching { Geocoder(ctx).getFromLocation(here.lat, here.lng, 1)?.firstOrNull() }.getOrNull() ?: return null
        return NavArea(a.locality.orEmpty(), a.adminArea.orEmpty(), a.countryCode.orEmpty())
    }

    /** 手机连着的移动网络（或 SIM 卡）是哪个国家的：定位不到时用它判断在哪个国家。 */
    fun networkCountry(ctx: Context): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager ?: return ""
        return listOf(runCatching { tm.networkCountryIso }.getOrNull(), runCatching { tm.simCountryIso }.getOrNull())
            .firstOrNull { !it.isNullOrBlank() }.orEmpty().uppercase(java.util.Locale.ROOT)
    }

    /** 这个国家的语言（日本 → 日语）：用当地语言查地名，查到的才是当地的地方。 */
    fun localeOf(country: String): java.util.Locale? =
        if (country.isEmpty()) null
        else java.util.Locale.getAvailableLocales().firstOrNull { it.country.equals(country, true) && it.language.isNotEmpty() && it.variant.isEmpty() && it.script.isEmpty() }

    /**
     * 现在的位置：先看系统记着的最近位置（10 分钟内的），没有就现在定位一次（最多等 timeoutMs）。
     * 会阻塞，只在后台线程调用。没有定位权限时为 null。
     */
    @Suppress("DEPRECATION", "MissingPermission")
    fun currentLocation(ctx: Context, timeoutMs: Long = 8_000): NavPoint? {
        val fine = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = ctx.getSystemService(android.location.LocationManager::class.java) ?: return null
        val enabled = runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
        val last = enabled.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000L) return NavPoint(last.latitude, last.longitude)
        val provider = listOf("fused", android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER).firstOrNull { it in enabled }
        val fresh = provider?.let { p ->
            val latch = java.util.concurrent.CountDownLatch(1)
            var got: android.location.Location? = null
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(p, null, ctx.mainExecutor) { l -> got = l; latch.countDown() }
                } else {
                    lm.requestSingleUpdate(p, object : android.location.LocationListener {
                        override fun onLocationChanged(l: android.location.Location) { got = l; latch.countDown() }
                        override fun onProviderDisabled(provider: String) { latch.countDown() }
                        override fun onProviderEnabled(provider: String) {}
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    }, Looper.getMainLooper())
                }
            }.onFailure { latch.countDown() }
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            got
        }
        val l = fresh ?: last ?: return null
        return NavPoint(l.latitude, l.longitude)
    }

    /** 查地名的结果：在哪个范围找到的、找到的地点（按远近排）、当前位置和所在地区（不知道时为 null）；note 是 Google 搜索用不了的原因。 */
    class Found(val scope: NavPick.Scope, val choices: List<NavChoice>, val here: NavPoint?, val area: NavArea?, val note: String? = null)

    /**
     * 查地名：先确定自己在哪（定位，反查城市和国家；定位不到就看手机网络是哪个国家的）。
     * 有 API Key 时用 Google 的地点搜索（Places API (New) Autocomplete，和 Google Maps 一样中文也能搜），先搜本国、没有再搜全世界；
     * Google 搜索用不了（没开通等）时，改用手机系统的地理编码：先在所在城市找，再在本国找，最后全世界（规则见 [NavPick.choose]）。
     * 会阻塞，只在后台线程调用。
     */
    fun search(ctx: Context, q: String, apiKey: String = ""): Found {
        val here = currentLocation(ctx)
        var area = here?.let { areaOf(ctx, it) }
        if (area == null || area.country.isEmpty()) {
            val cc = networkCountry(ctx)
            if (cc.isNotEmpty()) area = NavArea(area?.locality.orEmpty(), area?.admin.orEmpty(), cc)
        }
        if (q.isEmpty()) return Found(NavPick.Scope.WORLD, emptyList(), here, area)
        var note: String? = null
        if (apiKey.isNotBlank()) {
            val cc = area?.country?.takeIf { it.isNotEmpty() }
            val lang = java.util.Locale.getDefault().toLanguageTag()
            try {
                val inCountry = if (cc != null) autocomplete(ctx, apiKey, NavPlacesApi.body(q, here, cc, lang), q) else emptyList()
                if (inCountry.isNotEmpty()) return Found(NavPick.Scope.COUNTRY, inCountry, here, area)
                val world = autocomplete(ctx, apiKey, NavPlacesApi.body(q, here, null, lang), q)
                if (world.isNotEmpty()) return Found(NavPick.Scope.WORLD, world, here, area)
            } catch (e: Exception) {
                note = e.message ?: e.javaClass.simpleName
            }
        }
        val geo = geocoderSearch(ctx, q, here, area)
        return Found(geo.first, geo.second.map { p ->
            NavChoice(p.name, p.address, here?.let { NavMap.greatCircle(it, p.point).toInt() },
                NavDest(p.name.ifBlank { q }, p.point.lat, p.point.lng, query = q, address = p.address))
        }, here, area, note)
    }

    /** Places API (New) Autocomplete 请求。Key 限制成 Android 应用时，要带上包名和签名证书的 SHA-1。 */
    private fun autocomplete(ctx: Context, key: String, body: String, q: String): List<NavChoice> {
        val c = (URL(NavPlacesApi.URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Goog-Api-Key", key)
            setRequestProperty("X-Android-Package", ctx.packageName)
            certSha1(ctx)?.let { setRequestProperty("X-Android-Cert", it) }
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw java.io.IOException(NavPlacesApi.errorText(code, text))
            return NavPlacesApi.parse(text, q)
        } finally { c.disconnect() }
    }

    /** 本 app 签名证书的 SHA-1（大写十六进制，不带冒号）。 */
    @Suppress("DEPRECATION")
    private fun certSha1(ctx: Context): String? = runCatching {
        val pm = ctx.packageManager
        val sig = if (android.os.Build.VERSION.SDK_INT >= 28)
            pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        else pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        sig?.toByteArray()?.let { b -> java.security.MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02X".format(it) } }
    }.getOrNull()

    /** 手机系统的地理编码（Google 搜索用不了时）：用当地语言查，先城市、再本国、最后全世界。 */
    @Suppress("DEPRECATION")
    private fun geocoderSearch(ctx: Context, q: String, here: NavPoint?, area: NavArea?): Pair<NavPick.Scope, List<NavPlace>> {
        if (!Geocoder.isPresent()) return NavPick.Scope.WORLD to emptyList()
        val local = localeOf(area?.country.orEmpty())
        val lg = if (local != null) Geocoder(ctx, local) else Geocoder(ctx)
        val found = ArrayList<android.location.Address>()
        if (here != null) runCatching { lg.getFromLocationName(q, 10, here.lat - 0.5, here.lng - 0.6, here.lat + 0.5, here.lng + 0.6) }.getOrNull()?.let { found += it }
        if (local != null) {
            runCatching { lg.getFromLocationName(q, 10) }.getOrNull()?.let { found += it }
            val countryName = java.util.Locale("", area!!.country).getDisplayCountry(local)
            if (countryName.isNotEmpty()) runCatching { lg.getFromLocationName("$countryName $q", 10) }.getOrNull()?.let { found += it }
        }
        runCatching { Geocoder(ctx).getFromLocationName(q, 10) }.getOrNull()?.let { found += it }
        val places = found.map { a ->
            val addr = runCatching { a.getAddressLine(0) }.getOrNull().orEmpty()
            NavPlace(NavPoint(a.latitude, a.longitude), a.featureName?.takeIf { it.isNotBlank() && it != a.subThoroughfare } ?: q, addr,
                a.locality.orEmpty(), a.adminArea.orEmpty(), a.countryCode.orEmpty())
        }
        return NavPick.choose(places, here, area)
    }

    /** 跟着 maps.app.goo.gl 这类短链接跳转（最多 6 次），返回最后的地址。 */
    fun expand(url: String): String {
        var cur = url
        repeat(6) {
            val c = (URL(cur).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NimoGo")
            }
            try {
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (code !in 300..399 || loc.isNullOrEmpty()) return cur
                // Location 里的地名有时没编码（日文、空格）：转成 %XX 再拼，不然 URL 解析会失败
                cur = URL(URL(cur), NavLinks.safeUrl(loc)).toString()
            } finally { c.disconnect() }
        }
        return cur
    }
}
