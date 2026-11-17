package io.github.yingdu

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.OutcomeReceiver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日步数。两个来源：
 *  1. Health Connect（安卓 14 起系统自带）：手环、健康 app（Fitbit 等）同步进来的步数，最准；
 *  2. 手机自带的计步传感器：只统计手机本身走的步数，而且只能从 app 开始运行后算起（第一天会偏少）。
 * 优先用 1，拿不到再用 2。
 */
class StepsProvider(private val ctx: Context, private val onChange: () -> Unit) : SensorEventListener {
    companion object {
        const val PERM_HEALTH = "android.permission.health.READ_STEPS"
        const val PERM_SENSOR = "android.permission.ACTIVITY_RECOGNITION"
    }

    var steps: Long? = null; private set
    var source = ""; private set
    /** 没用上手环步数时，给设置页看的原因。 */
    var detail = ""; private set

    private val prefs = ctx.getSharedPreferences("steps", Context.MODE_PRIVATE)
    private var sensorOn = false

    fun healthConnectAvailable() = Build.VERSION.SDK_INT >= 34

    fun hasHealthPermission() = healthConnectAvailable() &&
        ctx.checkSelfPermission(PERM_HEALTH) == PackageManager.PERMISSION_GRANTED

    fun hasSensorPermission() = Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(PERM_SENSOR) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        when {
            !healthConnectAvailable() -> usePhone("这台手机没有 Health Connect（需要安卓 14），只能用手机计步")
            !hasHealthPermission() -> usePhone("还没有允许读取 Health Connect。点这一行去授权，手环也要先同步进去")
            else -> readHealthConnect()
        }
    }

    /**
     * 打开系统的 Health Connect 授权页（授予或关闭「读取步数」）。
     * 这台手机没有这个页面时返回 false。
     */
    fun openHealthPermissions(activity: android.app.Activity): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val intent = android.content.Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
            .putExtra(android.content.Intent.EXTRA_PACKAGE_NAME, activity.packageName)
        return runCatching { activity.startActivity(intent) }.isSuccess
    }

    fun stop() {
        if (sensorOn) (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        sensorOn = false
    }

    // ---------- Health Connect ----------

    @SuppressLint("NewApi")
    private fun readHealthConnect() {
        try {
            val hc = ctx.getSystemService(android.health.connect.HealthConnectManager::class.java)
                ?: return usePhone("这台手机没有 Health Connect，只能用手机计步")
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val filter = android.health.connect.TimeInstantRangeFilter.Builder()
                .setStartTime(start).setEndTime(Instant.now()).build()
            // 用"聚合"而不是逐条相加：多个来源（手机 + 手环）同时记录时，系统会自动去重
            val req = android.health.connect.AggregateRecordsRequest.Builder<Long>(filter)
                .addAggregationType(android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL)
                .build()
            hc.aggregate(req, ctx.mainExecutor,
                object : OutcomeReceiver<android.health.connect.AggregateRecordsResponse<Long>, android.health.connect.HealthConnectException> {
                    override fun onResult(r: android.health.connect.AggregateRecordsResponse<Long>) {
                        val v = r.get(android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL)
                        if (v == null) {
                            usePhone("Health Connect 里没有今天的步数。请在手环的 app 里打开同步")
                            return
                        }
                        steps = v
                        source = "Health Connect"
                        detail = ""
                        onChange()
                    }
                    override fun onError(e: android.health.connect.HealthConnectException) {
                        usePhone("读 Health Connect 失败，暂时用手机计步")
                    }
                })
        } catch (e: Exception) {
            usePhone("读 Health Connect 失败，暂时用手机计步")
        }
    }

    /** 退回手机计步，并记下原因。已经在用手机计步、原因也没变时，不额外刷新界面。 */
    private fun usePhone(why: String) {
        val leaveHealth = source == "Health Connect"
        if (leaveHealth) { source = ""; steps = null }
        val detailChanged = detail != why
        detail = why
        startSensor()
        if ((leaveHealth || detailChanged) && sensorValue == null) onChange()
    }

    // ---------- 手机计步传感器 ----------

    private fun startSensor() {
        if (!hasSensorPermission()) return
        if (sensorOn) { publishSensor(); return }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        sensorOn = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
    }

    override fun onSensorChanged(e: SensorEvent) {
        // 传感器给的是开机以来的累计步数；按天记一个起点，相减得到今天的步数
        val total = e.values[0].toLong()
        val today = LocalDate.now().toString()
        var base = prefs.getLong("base", -1)
        val day = prefs.getString("day", "")
        val last = prefs.getLong("last", -1)
        when {
            day != today -> base = if (last in 0..total) last else total   // 新的一天：以昨天最后一次读数为起点
            total < base -> base = 0                                         // 重启过手机，累计数清零了
        }
        prefs.edit().putLong("base", base).putString("day", today).putLong("last", total).apply()
        sensorValue = total - base
        publishSensor()
    }

    private var sensorValue: Long? = null

    private fun publishSensor() {
        val v = sensorValue ?: return
        // 手环步数已经在用时，手机传感器不再覆盖
        if (source == "Health Connect" && detail.isEmpty()) return
        steps = v
        source = "手机计步"
        onChange()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
