package com.freezer.app

import android.content.Context
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.system.exitProcess

/** 全局异常兜底：把崩溃写到文件并提示，避免无声闪退，方便排查。 */
class CrashHandler(private val ctx: Context) : Thread.UncaughtExceptionHandler {
    private val default = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val dir = File(ctx.filesDir, "crash").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val f = File(dir, "crash_$ts.txt")
            PrintWriter(f).use { e.printStackTrace(it) }
            Toast.makeText(ctx, "霜雪遇到问题，日志已保存（crash 目录）", Toast.LENGTH_LONG).show()
            Thread.sleep(1500)
        } catch (_: Exception) {}
        default?.uncaughtException(t, e) ?: exitProcess(1)
    }

    companion object {
        fun install(ctx: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(ctx.applicationContext))
        }
    }
}
