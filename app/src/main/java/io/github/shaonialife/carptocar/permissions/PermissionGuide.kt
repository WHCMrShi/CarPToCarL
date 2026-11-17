package io.github.shaonialife.carptocar.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import io.github.shaonialife.carptocar.media.MediaListenerService
import io.github.shaonialife.carptocar.ui.DarkMessageDialog

/** One foreground flow; system results are rechecked, never inferred from RESULT_OK. */
internal class PermissionGuide(
    private val activity: AppCompatActivity,
    private val onFinished: () -> Unit,
    private val report: (String) -> Unit,
) {
    private val prefs by lazy { activity.getSharedPreferences("permission_guide", 0) }
    private var waiting = false
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private var manual = false
    private var completed = false
    val active: Boolean get() = !completed

    private val runtime = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        waiting = false
        report("权限引导：运行时申请结束，未授权项可在系统设置中补充")
        advance()
    }
    private val settings = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waiting = false
        advance()
    }

    fun restore(state: Bundle?) {
        waiting = state?.getBoolean("guideWaiting") ?: false
        manual = state?.getBoolean("guideManual") ?: false
        completed = prefs.getInt("step", 0) >= PermissionGuidePlan.DONE && !waiting
    }

    fun save(state: Bundle) {
        state.putBoolean("guideWaiting", waiting)
        state.putBoolean("guideManual", manual)
    }

    fun attempted(permission: String): Boolean = permission in attemptedPermissions()

    fun markAttempted(permissions: Collection<String>) {
        prefs.edit().putStringSet("attempted", attemptedPermissions() + permissions).commit()
    }

    private fun attemptedPermissions(): Set<String> = prefs.getStringSet("attempted", emptySet())!!.toSet()

    fun open() {
        if (waiting || dialog?.isShowing == true) return
        manual = true
        completed = false
        prefs.edit().putInt("step", PermissionGuidePlan.INTRO).commit()
        advance()
    }

    fun advance() {
        if (!active || waiting || dialog?.isShowing == true || activity.isFinishing || activity.isDestroyed) return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        when (val step = prefs.getInt("step", 0)) {
            PermissionGuidePlan.DONE -> finish()
            PermissionGuidePlan.INTRO -> {
                if (missingRuntime().isEmpty() && granted(PermissionGuidePlan.OVERLAY) &&
                    granted(PermissionGuidePlan.BATTERY) && granted(PermissionGuidePlan.MEDIA)) {
                    finish()
                    return
                }
                show("连接权限设置", "将集中申请附近设备、精确位置（核对车机热点，不读取坐标）和通知权限。系统可能分组确认。\n\n随后可选设置暗幕与后台打开界面、后台运行、音乐信息；每项均可跳过。已授权项自动跳过，拒绝后不再自动申请。",
                    "开始设置", { moveNext(step); advance() }, { finish() })
            }
            PermissionGuidePlan.RUNTIME -> {
                val missing = missingRuntime()
                val request = PermissionGuidePlan.runtimeRequest(missing, attemptedPermissions())
                moveNext(step)
                if (request.isNotEmpty()) {
                    markAttempted(request)
                    waiting = true
                    runtime.launch(request.toTypedArray())
                } else if (manual && missing.isNotEmpty()) {
                    launchSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
                } else advance()
            }
            else -> {
                if (granted(step)) {
                    moveNext(step)
                    advance()
                    return
                }
                val (title, text) = when (step) {
                    PermissionGuidePlan.OVERLAY -> "暗幕与后台打开界面（可选）" to "允许「显示在其他应用上层」可使用暗幕，并在靠近车机时从后台打开界面。跳过后仍可手动连接。"
                    PermissionGuidePlan.BATTERY -> "后台运行（可选）" to "允许忽略电池优化，以减少靠近车机自动启动被系统限制的情况。厂商的后台管理可能还需单独设置。"
                    else -> "音乐信息（可选）" to "开启「通知使用权」可读取当前音乐的歌名、播放状态及媒体会话。跳过不影响基础连接。"
                }
                show(title, text, "去授权", {
                    moveNext(step)
                    val intent = when (step) {
                        PermissionGuidePlan.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())
                        PermissionGuidePlan.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
                        else -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, MediaListenerService.componentName(activity).flattenToString())
                    }
                    launchSettings(intent, when (step) {
                        PermissionGuidePlan.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        PermissionGuidePlan.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        else -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    })
                }, { moveNext(step); advance() })
            }
        }
    }

    private fun launchSettings(intent: Intent, fallback: Intent? = null) {
        waiting = true
        try {
            settings.launch(intent)
        } catch (_: Exception) {
            if (fallback != null) {
                try { settings.launch(fallback); return } catch (_: Exception) { /* Continue below. */ }
            }
            waiting = false
            report("无法打开系统授权页，可稍后在系统设置中授权")
            advance()
        }
    }

    private fun packageUri() = Uri.parse("package:${activity.packageName}")
    private fun moveNext(step: Int) {
        prefs.edit().putInt("step", PermissionGuidePlan.next(step)).commit()
    }

    private fun finish() {
        completed = true
        prefs.edit().putInt("step", PermissionGuidePlan.DONE).commit()
        report("权限引导已结束；未授权项可在系统设置中补充")
        onFinished()
    }

    private fun granted(step: Int): Boolean = when (step) {
        PermissionGuidePlan.OVERLAY -> Settings.canDrawOverlays(activity)
        PermissionGuidePlan.BATTERY -> activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)
        PermissionGuidePlan.MEDIA -> activity.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(MediaListenerService.componentName(activity))
        else -> false
    }

    private fun missingRuntime(): List<String> = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }

    private fun show(title: String, message: String, action: String, accept: () -> Unit, skip: () -> Unit) {
        fun later(block: () -> Unit) {
            // DarkMessageDialog dismisses after its action; advance on the next UI turn.
            dialog = null
            activity.window.decorView.post(block)
        }
        dialog = DarkMessageDialog.show(activity, title, message, listOf(
            DarkMessageDialog.Action("跳过") { later(skip) },
            DarkMessageDialog.Action(action, DarkMessageDialog.Emphasis.PRIMARY) { later(accept) },
        ), onCancel = { later(skip) })
    }

    fun close() { dialog?.dismiss(); dialog = null }
}
