package io.github.yingdu

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
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
    val ready get() = navigator != null

    fun termsAccepted(app: Application) = runCatching { NavigationApi.areTermsAccepted(app) }.getOrDefault(false)

    /** 弹出 Google 导航服务条款（SDK 自带的对话框）。 */
    fun askTerms(activity: Activity, done: (Boolean) -> Unit) {
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
        if (apiKey.isNotBlank()) runCatching { NavigationApi.setApiKey(apiKey.trim()) }.onFailure { note("设置 API Key 出错：${it.message}") }
        note("初始化 Navigator…")
        try { NavigationApi.getNavigator(app, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(n: Navigator) {
                note("Navigator 就绪（SDK ${runCatching { NavigationApi.getNavSDKVersion() }.getOrDefault("?")}）")
                navigator = n
                n.addArrivalListener(arrival)
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
        override fun onArrival(e: ArrivalEvent) { if (e.isFinalDestination) main.post { onArrived?.invoke() } }
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

    fun deliver(snap: NavSnap) {
        main.post {
            infoCount++; lastInfoAt = System.currentTimeMillis()
            if (infoCount == 1) note("收到第一条逐向数据（状态 ${snap.state}，" + (snap.current?.let { "下一步 ${it.instruction.ifEmpty { it.road }}" } ?: "没有当前步骤") + "）")
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
    fun fromShare(ctx: Context, text: String, subject: String = ""): NavDest? {
        val title = NavLinks.titleOf(text).ifEmpty { subject.trim() }
        val url = NavLinks.findUrl(text)
        val dest = when {
            text.trim().startsWith("geo:", true) -> NavLinks.parseGeo(text.trim())
            url != null -> NavLinks.parseUrl(if (NavLinks.isShortLink(url)) expand(url) else url, title)
            title.isNotEmpty() -> NavDest(title, query = title)
            else -> null
        } ?: return if (title.isNotEmpty()) geocode(ctx, NavDest(title, query = title)) else null
        return if (dest.ready) dest else geocode(ctx, dest)
    }

    /** 只有地名 / 地址：交给系统地理编码，取第一个结果。 */
    @Suppress("DEPRECATION")
    fun geocode(ctx: Context, d: NavDest): NavDest? {
        if (d.ready) return d
        val q = d.query?.trim().orEmpty().ifEmpty { d.title }
        if (q.isEmpty() || !Geocoder.isPresent()) return null
        val a = runCatching { Geocoder(ctx).getFromLocationName(q, 1)?.firstOrNull() }.getOrNull() ?: return null
        return d.copy(lat = a.latitude, lng = a.longitude)
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
