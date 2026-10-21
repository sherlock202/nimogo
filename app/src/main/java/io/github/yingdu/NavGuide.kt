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
    fun fromShare(ctx: Context, text: String, subject: String = "", near: NavPoint? = null): NavDest? {
        val title = NavLinks.titleOf(text).ifEmpty { subject.trim() }
        val url = NavLinks.findUrl(text)
        val dest = when {
            text.trim().startsWith("geo:", true) -> NavLinks.parseGeo(text.trim())
            url != null -> NavLinks.parseUrl(if (NavLinks.isShortLink(url)) expand(url) else url, title)
            title.isNotEmpty() -> NavDest(title, query = title)
            else -> null
        } ?: return if (title.isNotEmpty()) geocode(ctx, NavDest(title, query = title), near) else null
        return if (dest.ready) dest else geocode(ctx, dest, near)
    }

    /**
     * 只有地名 / 地址：交给系统地理编码。同名的地方可能有好几个（「上野」在别的国家也有），
     * 知道当前位置时先在附近（约 50 公里内）找，找不到再全世界找，取离当前位置最近的。
     */
    @Suppress("DEPRECATION")
    fun geocode(ctx: Context, d: NavDest, near: NavPoint? = null): NavDest? {
        if (d.ready) return d
        val q = d.query?.trim().orEmpty().ifEmpty { d.title }
        if (q.isEmpty() || !Geocoder.isPresent()) return null
        val g = Geocoder(ctx)
        var list = near?.let { n ->
            runCatching { g.getFromLocationName(q, 5, n.lat - 0.45, n.lng - 0.55, n.lat + 0.45, n.lng + 0.55) }.getOrNull()
        }.orEmpty()
        if (list.isEmpty()) list = runCatching { g.getFromLocationName(q, 5) }.getOrNull().orEmpty()
        val a = list.getOrNull(NavPick.best(list.map { NavPoint(it.latitude, it.longitude) }, near)) ?: return null
        return d.copy(lat = a.latitude, lng = a.longitude, address = runCatching { a.getAddressLine(0) }.getOrNull())
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
                cur = URL(URL(cur), loc).toString()
            } finally { c.disconnect() }
        }
        return cur
    }
}
