package com.zglinus.playcheck.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** 未捕获异常落盘；下次启动弹出来。设备上拿不到 logcat 时，这是唯一能让失败可见的通道。 */
object Crash {
    private const val FILE = "crash.log"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText(sw.toString().take(12000))
            }
            Log.e("PlayCheck", "crash", e)
            prev?.uncaughtException(t, e)
        }
    }

    /** 上次的崩溃：读出来展示一次就删。 */
    fun lastStack(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    fun showDialog(a: Activity, text: String) {
        runCatching {
            AlertDialog.Builder(a)
                .setTitle("上次崩溃的堆栈")
                .setMessage(text.take(1800))
                .setPositiveButton("知道了", null)
                .show()
        }
    }
}
