package io.github.shaonialife.carptocar.media

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/** 授权由系统 API 核实，绑定仅由服务回调确认；两者都不能证明车机已显示歌名。 */
object NotificationAccess {
    @Volatile
    private var serviceConnected = false

    val isConnected: Boolean get() = serviceConnected

    internal fun markConnected() {
        serviceConnected = true
    }

    internal fun markDisconnected() {
        serviceConnected = false
    }

    private const val REBIND_MIN_INTERVAL_MS = 10_000L
    private const val DIAGNOSTIC_INTERVAL_MS = 300_000L
    private var lastRebindRequestAt = -REBIND_MIN_INTERVAL_MS
    private var lastDiagnosticAt = -DIAGNOSTIC_INTERVAL_MS
    private var lastDiagnostic: String? = null

    /**
     * 已授权且尚未收到连接回调时请求重绑；不修改授权，不依赖 shell 权限。
     * 返回 true 仅代表 requestRebind 调用未抛异常，不代表已绑定或媒体信息恢复。
     */
    @Synchronized
    fun requestSystemRebind(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        if (serviceConnected) return false
        val component = MediaListenerService.componentName(context)
        val approved = try {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component)
        } catch (error: Exception) {
            diagnostic("MEDIA: 无法核实通知使用权（${error.javaClass.simpleName}），跳过重绑", log)
            return false
        }
        if (!approved) {
            diagnostic("MEDIA: 系统报告未授予通知使用权，跳过监听器重绑", log)
            return false
        }
        if (serviceConnected) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastRebindRequestAt < REBIND_MIN_INTERVAL_MS) return false
        lastRebindRequestAt = now
        return try {
            NotificationListenerService.requestRebind(component)
            diagnostic("MEDIA: 通知使用权已确认；已调用 requestRebind，等待监听器连接回调", log)
            true
        } catch (error: Exception) {
            diagnostic("MEDIA: requestRebind 调用失败（${error.javaClass.simpleName}）", log)
            false
        }
    }

    /** 同一事实最多五分钟记录一次；状态变化立即记录，不断言歌名或元数据异常。 */
    private fun diagnostic(message: String, log: (String) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (message == lastDiagnostic && now - lastDiagnosticAt < DIAGNOSTIC_INTERVAL_MS) return
        lastDiagnostic = message
        lastDiagnosticAt = now
        log(message)
    }
}
