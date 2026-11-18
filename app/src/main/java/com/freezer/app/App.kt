package com.freezer.app

import android.app.Application

/**
 * Application 入口：在进程最早期安装全局崩溃捕获。
 * 旧版在 MainActivity 才安装，导致前台服务/广播先崩溃时没有任何兜底，
 * 系统直接弹出"已停止运行"且还会 force-stop 应用（force-stop 后连锁屏广播都收不到）。
 */
class App : Application() {
    override fun onCreate() {
        CrashHandler.install(this)
        super.onCreate()
    }
}
