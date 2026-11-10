package io.github.shaonialife.carptocar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.shaonialife.carptocar.carlife.CarGpsOption
import io.github.shaonialife.carptocar.launcher.VirtualLauncher
import io.github.shaonialife.carptocar.util.DebugFileLog
import io.github.shaonialife.carptocar.util.SessionTrace
import io.github.shaonialife.carptocar.video.FirstFramePolicy
import io.github.shaonialife.carptocar.video.VideoCanvasMode

/**
 * 未捕获异常同步留证后交还Android默认处理器；不假装已死亡的线程仍可继续服务。
 * 可恢复的连接异常仍由各线程的局部try/catch处理。
 */
class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 会话日志起点标记：日志文件跨进程累积（2MB 上限），导出默认只取本次启动
        // 之后的部分。必须在这里写（Application 是进程最早入口），晚于任何业务日志
        // 都会让它被算进「历史」。
        DebugFileLog.markSessionStart(this)
        DebugFileLog.append(this, "CONNECT-DIAG: schema=20261108-v9 revision=${BuildConfig.SOURCE_REVISION} version=${BuildConfig.VERSION_NAME} sdk=${android.os.Build.VERSION.SDK_INT}; export includes bounded early/recent diagnostics, idle deadlines and network leases")
        // VideoConfig 画布声明模式（车机画布 / 码流尺寸）——会话建立时读一次
        VideoCanvasMode.load(this)
        // 首帧策略（缓存关键帧立即起播 / 等引擎当期 IDR）——attach 时读一次
        FirstFramePolicy.load(this)
        // 「使用车机 GPS」开关（默认开）——iAP2 收帧线程每秒会读，启动时载入一次即可
        CarGpsOption.load(this)
        // 完整会话报文录制（files/session_trace.log）：与上面的过程日志分开两份文件，
        // 原因见 SessionTrace 注释（报文量大会挤掉过程日志）。必须在业务起来之前 attach。
        SessionTrace.attach(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val evidence = io.github.shaonialife.carptocar.util.CrashEvidence(filesDir)
        Thread.setDefaultUncaughtExceptionHandler(io.github.shaonialife.carptocar.util.EvidenceExceptionHandler(
            record = { thread, throwable ->
                evidence.record(thread, throwable, "${BuildConfig.VERSION_NAME}/${BuildConfig.SOURCE_REVISION}")
                Log.e(TAG, "未捕获异常已留证（线程 ${thread.name}）", throwable)
            },
            terminate = { thread, throwable ->
                if (previous != null) previous.uncaughtException(thread, throwable)
                else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
            },
        ))
        // 应用安装/卸载/更新 → 桌面与车机设置的共享应用清单缓存失效
        val packageFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    VirtualLauncher
                        .invalidateAppsCache()
                    Log.i(TAG, "应用清单缓存已失效（包变化）")
                }
            },
            packageFilter,
        )
    }

    companion object {
        private const val TAG = "CarPlaySender"
    }
}
