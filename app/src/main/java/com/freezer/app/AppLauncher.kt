package com.freezer.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 打开目标应用：解冻 + 启动在同一条后台流水线里完成，全程不阻塞 UI 线程。
 *
 * 解决的连贯性问题：
 * 1. 旧版解冻（Shizuku pm enable，含 waitFor 阻塞）在 UI 线程执行，点击后卡顿；
 * 2. 旧版解冻成功就立刻弹"已解冻并启动"，但真正启动还在后台轮询，反馈割裂；
 * 3. 解冻命令返回后 PackageManager 状态异步刷新，立即查 Intent 拿到 null 的竞态。
 *
 * 现在：一次点击 = 后台"按需解冻 → 轮询启动 → 自愈补解冻"一条龙，
 * 界面侧乐观更新（点下即视为解冻成功、图标变亮），只有真正失败才回滚提示。
 */
object AppLauncher {

    private val ui = Handler(Looper.getMainLooper())
    private const val TIMEOUT_MS = 3000L
    private const val POLL_MS = 100L

    /**
     * @param needUnfreeze 是否需要先解冻（缓存里是冻结态就传 true）
     * @param onUnfreezeResult 解冻阶段结束回调（UI 线程）：true=成功/false=失败，用于界面状态同步
     * @param onLaunched      启动 Intent 已发出回调（UI 线程）
     * @param onFail          最终启动失败回调（UI 线程）
     */
    fun launch(
        activity: Activity,
        pkg: String,
        mode: Int,
        needUnfreeze: Boolean = false,
        onUnfreezeResult: ((Boolean) -> Unit)? = null,
        onLaunched: (() -> Unit)? = null,
        onFail: (() -> Unit)? = null
    ) {
        thread {
            // 阶段 1：按需解冻（后台线程，不卡 UI）
            if (needUnfreeze) {
                if (mode == FreezerEngine.MODE_NONE) {
                    ui.post {
                        onUnfreezeResult?.invoke(false)
                        onFail?.invoke()
                    }
                    return@thread
                }
                val r = FreezerEngine.unfreeze(activity, pkg, mode)
                ui.post { onUnfreezeResult?.invoke(r.isSuccess) }
                if (r.isFailure) {
                    ui.post { onFail?.invoke() }
                    return@thread
                }
            }

            // 阶段 2：轮询等系统就绪后启动（最多 3 秒，每 100ms 一次）
            val pm = activity.packageManager
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    ui.post {
                        try {
                            activity.startActivity(intent)
                            onLaunched?.invoke()
                        } catch (e: Exception) {
                            Toast.makeText(activity, "启动失败：${e.message}", Toast.LENGTH_SHORT).show()
                            onFail?.invoke()
                        }
                    }
                    return@thread
                }
                // 查不到启动入口：反查真实冻结状态，仍冻结就补一次解冻（幂等）再继续等
                if (mode != FreezerEngine.MODE_NONE && FreezerEngine.isFrozen(activity, pkg, mode)) {
                    FreezerEngine.unfreeze(activity, pkg, mode)
                }
                Thread.sleep(POLL_MS)
            }
            ui.post {
                Toast.makeText(activity, "暂时无法启动该应用，请再点一次", Toast.LENGTH_SHORT).show()
                onFail?.invoke()
            }
        }
    }
}
