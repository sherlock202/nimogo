package io.github.yingdu

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 闪退记录：app 崩溃时把出错的调用栈写到手机上（只存在本机，不上传），下次打开时弹出来，可以复制给开发者看。
 * 记完后交给系统原来的处理（app 照常退出）。
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: ""
                val head = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                    "  NimoGo $version  Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})  线程 ${thread.name}\n"
                File(app.filesDir, FILE).writeText(head + sw.toString().take(20_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** 上次闪退的记录（没有就是 null）；读完不删，用户点「知道了」才删。 */
    fun last(ctx: Context): String? = runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: Context) { runCatching { File(ctx.filesDir, FILE).delete() } }
}
