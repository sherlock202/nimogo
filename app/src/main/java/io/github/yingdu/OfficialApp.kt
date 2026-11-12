package io.github.yingdu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Nimo 官方 app。眼镜同一时间只能连一个 app，所以要切换，不能同时连。
 * 导航和场景助手的即时翻译在 NimoGo 里做；切过去是为了用官方 app 自己的翻译等功能。
 */
object OfficialApp {
    private var cached: String? = null

    /** 在桌面 app 里找名字或包名带 nimo 的那个（不是萤读自己）。 */
    fun packageName(ctx: Context): String? {
        cached?.let { return it }
        val pm = ctx.packageManager
        val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .firstOrNull { (pkg, label) -> label.contains("nimo", true) || pkg.contains("nimo", true) }
            ?.first
        cached = found
        return found
    }

    fun launch(ctx: Context): Boolean {
        val pkg = packageName(ctx) ?: return false
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** 打开官方 app 的系统设置页，方便"强行停止"后把眼镜连回萤读。 */
    fun openAppSettings(ctx: Context): Boolean {
        val pkg = packageName(ctx) ?: return false
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
