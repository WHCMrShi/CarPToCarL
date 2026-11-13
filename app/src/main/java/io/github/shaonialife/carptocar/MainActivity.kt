package io.github.shaonialife.carptocar

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.bt.AppUiPresence
import io.github.shaonialife.carptocar.bt.BtCompatPrefs
import io.github.shaonialife.carptocar.bt.BtProfiles
import io.github.shaonialife.carptocar.bt.CarApproachPrefs
import io.github.shaonialife.carptocar.bt.CarApproachReceiver
import io.github.shaonialife.carptocar.bt.TargetDevicePrefs
import io.github.shaonialife.carptocar.carlife.CarGpsOption
import io.github.shaonialife.carptocar.carlife.CarLifeHijack
import io.github.shaonialife.carptocar.input.CarInputClient
import io.github.shaonialife.carptocar.input.TouchRouter
import io.github.shaonialife.carptocar.launcher.VirtualLauncher
import io.github.shaonialife.carptocar.launcher.settings.AppOrientationCompat
import io.github.shaonialife.carptocar.launcher.settings.AutoConnectPrefs
import io.github.shaonialife.carptocar.ui.DarkMessageDialog
import io.github.shaonialife.carptocar.ui.PanelWindow
import io.github.shaonialife.carptocar.ui.ScreenDimmer
import io.github.shaonialife.carptocar.util.AppVersion
import io.github.shaonialife.carptocar.util.Bg
import io.github.shaonialife.carptocar.util.DeviceIdentity
import io.github.shaonialife.carptocar.util.LogExporter
import io.github.shaonialife.carptocar.util.SysShell
import io.github.shaonialife.carptocar.video.StreamFps
import java.util.Locale

/**
 * 「靠近车机」自动启动标记（2026-10-30）：由 [io.github.shaonialife.carptocar.bt.CarApproachReceiver]
 * 在检测到目标车机蓝牙靠近时放进 Intent。带这个标记进来 = 冷启动自动连接，
 * 也可以在 App 已在后台时重新武装一次自动连接（onNewIntent）。
 */
const val EXTRA_CAR_APPROACH = "io.github.shaonialife.carptocar.extra.CAR_APPROACH"

/**
 * 手机侧界面（Material 3 暗色重构，设计稿 design/phone-ui-proto.html）：
 * 英雄卡承载连接状态与主操作；连接准备清单化；Shell 状态与日志折叠收纳。
 * 会话管线（对象、日志缓冲）全部由 [SessionController] 持有——Activity 只是挂屏：
 * 销毁重建不影响会话，重进界面日志自动续显；连接状态由 1s 轮询对账。
 */
class MainActivity : AppCompatActivity() {
    private enum class HeroState { IDLE, CONNECTING, CONNECTED }

    /** 连接中的默认副标题（无阶段提示时）。 */
    private companion object {
        const val DEFAULT_CONNECTING_HINT = "正在准备连接…"

        /** 本机蓝牙地址未填写：英雄卡副标题与输入框下方提示共用同一句。 */
        const val HINT_MAC_MISSING = "请先保存本机蓝牙地址"

        /** 未填写时被拦下的 Snackbar 文案。 */
        const val TIP_MAC_MISSING = "本机蓝牙地址未填写 —— 请先在「连接准备」里填写并保存"

        /** 地址就绪时的英雄卡副标题（与「未填写」提示互斥）。 */
        const val HINT_IDLE_READY = "点击下方按钮发起连接"

        /** 「启动连接」被门禁拦下时的视觉强度（不透明度）。 */
        const val CTA_BLOCKED_ALPHA = 0.45f
    }

    private lateinit var heroStateText: TextView
    private lateinit var btnResidualCleanup: TextView
    private lateinit var heroSubline: TextView
    private lateinit var heroStats: TextView
    /** 「显示」卡片里的分辨率行（车机通告值 + 本次码流尺寸）。 */
    private lateinit var ctaButton: LinearLayout
    private lateinit var ctaIcon: ImageView
    private lateinit var ctaLabel: TextView
    private lateinit var ssidInput: TextView
    private var macEditing = false
    private lateinit var macInput: EditText
    private lateinit var btDesc: TextView
    private lateinit var btnPickTarget: TextView
    private lateinit var logBody: View
    private lateinit var logChevron: ImageView
    private lateinit var switchLog: androidx.appcompat.widget.SwitchCompat
    private lateinit var logText: TextView
    private lateinit var snackbar: TextView

    // ---- 英雄卡状态机（1s 轮询 SessionController 对账）----
    private var heroState: HeroState? = null
    private var connectedAt = 0L

    // 启动残留屏检查进行中（英雄卡显示「检查中」）；发现的残留暂缓清理时
    // 记在 pendingResidualDisplays，状态旁的「清理残留屏」按钮可再次发起
    private var startupCheckRunning = false
    private var pendingResidualDisplays: List<Pair<Int, String>>? = null

    // 英雄卡实时帧率：1s 轮询对 VideoSender 已发帧计数取差值
    private var lastSentFrames = 0L

    /**
     * 冷启动自动连接请求挂起中：等前置条件齐备（启动残留扫描已结束、
     * 英雄卡空闲）才真正发起。旋转/重建不会置位——[maybeAutoConnect] 全程幂等。
     */
    private var autoConnectRequested = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heroTick: Runnable =
        object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                tickHero()
                mainHandler.postDelayed(this, 1_000L)
            }
        }

    /**
     * 挂屏注册：日志续显（含划掉界面重进时已有会话的历史日志）+ SSID 回填。
     */
    private val uiHintSinkRef: (String) -> Unit = { message ->
        runOnUiThread { if (!isFinishing && !isDestroyed) snack(message, 6_000L) }
    }

    /** 当前打开的「加入热点」确认弹窗：取消连接时联动关闭（触发流程中止）。 */
    private var wifiJoinDialog: androidx.appcompat.app.AlertDialog? = null

    /** MIB3 控制链路的首页监听（onDestroy 摘掉，避免单例持有 Activity）。 */

    private val consoleLock = Any()
    private val pendingConsole = io.github.shaonialife.carptocar.util.ConsoleLogBuffer()
    private var consoleScheduled = false
    private val flushConsole =
        Runnable {
            val text =
                synchronized(consoleLock) {
                    val batch = pendingConsole.drain()
                    consoleScheduled = false
                    batch
                }
            if (!isFinishing && !isDestroyed) appendConsole(text)
        }
    private val logListener: (String, Long) -> Unit = { line, epoch ->
        synchronized(consoleLock) {
            val accepted = pendingConsole.append(line, epoch)
            if (accepted && !consoleScheduled) {
                consoleScheduled = true
                mainHandler.postDelayed(flushConsole, 100)
            }
        }
    }

    // UI-only snapshot: never populates connection credentials or starts a network request.
    private var wifiDisplayKey: Pair<String?, String?>? = null
    private var rememberedDisplaySsid: String? = null
    private var liveDisplaySsid: String? = null

    private val ssidSink: (String) -> Unit = { ssid ->
        runOnUiThread {
            refreshWifiMemoryDisplay()
            liveDisplaySsid = ssid.takeIf { it.isNotBlank() }
            renderWifiMemoryDisplay()
        }
    }

    private fun renderWifiMemoryDisplay() {
        ssidInput.text = rememberedDisplaySsid ?: liveDisplaySsid.orEmpty()
        findViewById<TextView>(R.id.btnClearWifiMemory).apply {
            isEnabled = wifiDisplayKey?.second != null && SessionController.canClearRememberedWifi()
            setTextColor(getColor(if (isEnabled) R.color.m3_primary else R.color.m3_outline))
            alpha = if (isEnabled) 1f else 0.45f
        }
    }

    private fun refreshWifiMemoryDisplay() {
        val target = TargetDevicePrefs.get(this)?.address?.uppercase()
        val prefs = getSharedPreferences(io.github.shaonialife.carptocar.net.CarWifiMemory.PREFS, Context.MODE_PRIVATE)
        val key = target to target?.let { prefs.getString(it, null) }
        if (wifiDisplayKey == key) return
        if (wifiDisplayKey?.first != target) liveDisplaySsid = null
        wifiDisplayKey = key
        rememberedDisplaySsid = null
        renderWifiMemoryDisplay()
        if (target == null || key.second == null) return
        val context = applicationContext
        Bg.run("wifi-memory-display") {
            // Read independently of SessionController.loadFixedWifi: no connection state is changed.
            val savedName = runCatching {
                io.github.shaonialife.carptocar.net.CarWifiMemory(context).load(target)?.wifi?.ssid
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || wifiDisplayKey != key ||
                    TargetDevicePrefs.get(this)?.address?.uppercase() != target ||
                    prefs.getString(target, null) != key.second) return@runOnUiThread
                rememberedDisplaySsid = savedName
                renderWifiMemoryDisplay()
            }
        }
    }

    /**
     * 单权限申请入口。**只用于「通知」这类不属于连接前提的权限。**
     *
     * 连接前提的权限不能走这里：Android 12+ 的 `BLUETOOTH_CONNECT`、
     * `NEARBY_WIFI_DEVICES` 和用于 SSID 身份核对的定位权限是独立条目，逐个申请就会
     * 连续弹出好几个内容几乎一样的系统框（框里都带应用名），用户看到的是
     * 「怎么一直要我点」。连接前统一走 [connectionPermissionLauncher]——
     * 一次请求、一个框。
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("权限申请结果: $granted")
        }

    /**
     * 连接车机的运行时权限，**一次性打包申请**。
     *
     * 系统对同一权限组（Android 13+ 的「附近的设备」组）只显示一个授权框，
     * 所以把 `BLUETOOTH_CONNECT` 与 `NEARBY_WIFI_DEVICES` 放进同一次请求里，
     * 用户点一次「允许」就够；分成两次请求则会连弹两个框，正是「一直提示」的根源。
     */
    private val connectionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            log(
                "权限申请结果: " +
                    result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" },
            )
            val resumeConnection = pendingStartAfterPermission.getAndSet(false)
            val missing = requiredConnectionPermissions()
            when {
                missing.isNotEmpty() -> {
                    connectionPermissionDenied.set(true)
                    promptConnectionPermissionDenied(missing)
                }

                resumeConnection -> {
                    proceedToConnect { ensureBluetoothReady { startConnectionNow() } }
                }
            }
        }

    /** 权限授权后自动继续启动连接（「只想开个开关」的路径上不要置位）。 */
    private val pendingStartAfterPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * 本进程内已被拒绝过：**不再重复弹系统框**。
     *
     * 重复弹没有意义——系统从第三次起自己就不显示了，调用只会立刻返回「未授权」，
     * 白白让用户以为程序在反复索要权限。改为记日志 + 一次自家提示（带「去设置」）。
     */
    private val connectionPermissionDenied =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** 「缺权限，请去设置」这条自家提示每个进程只弹一次，之后只记日志。 */
    private val permissionHintShown =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 连接前必须拿到的权限（缺哪个列哪个）。
     *
     * - 蓝牙连接权限用于 iAP2；13+ 还申请附近 WiFi 权限。
     * - 所有支持版本申请精确位置权限，用于读取 SSID 核对目标网络身份。
     */
    private fun requiredConnectionPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (!isGranted(Manifest.permission.BLUETOOTH_CONNECT)) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isGranted(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                missing += Manifest.permission.NEARBY_WIFI_DEVICES
            }
        }
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return missing
    }

    /**
     * 权限被拒后的唯一提示：说明缺哪一项、为什么必须给，并给一个「去设置」按钮。
     *
     * 提示只弹一次（[permissionHintShown]），避免每次点「启动连接」都被自己的框挡住。
     */
    private fun promptConnectionPermissionDenied(missing: Collection<String>) {
        if (!permissionHintShown.compareAndSet(false, true)) return
        val names =
            missing.distinctBy { if (it == Manifest.permission.ACCESS_COARSE_LOCATION) Manifest.permission.ACCESS_FINE_LOCATION else it }.joinToString("、") { permission ->
                when (permission.substringAfterLast('.')) {
                    "BLUETOOTH_CONNECT" -> "附近的蓝牙设备"
                    "NEARBY_WIFI_DEVICES" -> "附近的 WLAN 设备"
                    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION" -> "精确位置"
                    else -> permission.substringAfterLast('.')
                }
            }
        DarkMessageDialog.show(
            activity = this,
            title = "缺少「$names」权限",
            message =
                "连接蓝牙和热点需要附近设备权限；核对当前热点名称需要精确位置权限。" +
                    "我们不读取手机坐标。请到应用权限设置允许对应权限后重试。",
            actions =
                listOf(
                    DarkMessageDialog.Action("知道了") {},
                    DarkMessageDialog.Action("去设置", DarkMessageDialog.Emphasis.PRIMARY) {
                        runCatching {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", packageName, null),
                                ),
                            )
                        }.onFailure { log("权限: 无法打开应用详情页，请手动到设置里授予") }
                    },
                ),
        )
    }

    private var cancelHold: io.github.shaonialife.carptocar.ui.HoldToCancel? = null

    private fun cancelConnectingFromButton() {
        if (heroState != HeroState.CONNECTING || SessionController.videoSender?.isRunning == true) return
        wifiJoinDialog?.cancel()
        wifiJoinDialog = null
        CarApproachPrefs.setSuppressed(this, true)
        pendingStartAfterPermission.set(false)
        SessionController.cancelConnecting(this)
    }

    /**
     * 启动后自动连接（默认开启，开关在「连接准备」卡里）。
     *
     * 满足全部前置条件才发起，任一不满足就**原地返回、保留请求**，由对应时机再调一次：
     * - 启动残留扫描进行中 / 有残留待确认 → 等 [detectResidualDisplays] 收尾续上
     *   （否则「是否清理残留屏」弹窗会和连接流程的热点确认弹窗抢焦点）；
     * - 已在连接/已连接 → 不重复发起。
     *
     * 走的是和手动点「启动连接」完全相同的链路（权限 → 蓝牙开关 → WLAN → 会话），
     * [SessionController.startConnection] 本身幂等，不会双开。
     */
    private fun maybeAutoConnect() {
        if (!autoConnectRequested || isFinishing || isDestroyed) return
        if (!AutoConnectPrefs.enabled(this)) {
            autoConnectRequested = false
            log("AUTOCONNECT: 已关闭——等待手动点「启动连接」")
            return
        }
        if (startupCheckRunning || pendingResidualDisplays != null) return
        if (SessionController.isSessionRunning() || SessionController.sessionStarting.get()) return
        if (heroState != HeroState.IDLE) return

        autoConnectRequested = false
        log("AUTOCONNECT: 打开 App 即自动发起连接（可在「连接准备」里关闭）")
        proceedToConnect { ensureBluetoothReady { startConnectionNow() } }
    }

    /**
     * 电池优化豁免：只在「还没豁免 + 本次会话没提示过」时弹一次。
     *
     * 「靠近车机自动启动」依赖系统把蓝牙 ACL 广播投递给**不在前台**的进程；
     * 一旦被列入电池优化（MagicOS 的「应用启动管理」是同一类限制），系统会冻结进程
     * 或直接丢弃广播，表现就是「有时候能自动连、有时候毫无反应」。
     */
    private fun ensureBatteryExempt(force: Boolean = false) {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        if (!force && !CarApproachPrefs.batteryPromptDue(this)) return
        CarApproachPrefs.markBatteryPrompt(this)
        DarkMessageDialog.show(
            this,
            "允许后台运行",
            "「靠近车机自动启动」要在 App 没打开时收到车机的蓝牙信号。\n\n" +
                "系统若对本应用限制后台（电池优化 / 应用启动管理），这条信号可能被丢掉，" +
                "表现为有时能自动连、有时没反应。请在接下来的系统页面里选择「不限制 / 允许」。",
            listOf(
                DarkMessageDialog.Action("稍后", DarkMessageDialog.Emphasis.TEXT) {
                    log("APPROACH: 未豁免电池优化——后台自动启动可能被系统限流")
                },
                DarkMessageDialog.Action("去设置", DarkMessageDialog.Emphasis.PRIMARY) {
                    requestBatteryExemption()
                },
            ),
        )
    }

    /**
     * 「显示在其他应用上层」（2026-10-30）。
     *
     * 这条权限在这个项目里有第二重身份：Android 10+ 的后台启动界面限制，**豁免条件之一
     * 就是持有它**。没有它，车机靠近时 `startActivity` 会被系统静默拦下——日志写着
     * 「已拉起」，屏幕上什么都没有，接收端也因此连不上（实测拒绝时刻与触发时刻完全吻合）。
     *
     * 所以它和电池优化是同一件事的两半：一个保「收得到广播」，一个保「起得来界面」。
     */
    private fun ensureOverlayPermission(force: Boolean = false) {
        if (Settings.canDrawOverlays(this)) return
        if (!force && !CarApproachPrefs.overlayPromptDue(this)) return
        CarApproachPrefs.markOverlayPrompt(this)
        DarkMessageDialog.show(
            this,
            "允许后台打开界面",
            "车机靠近时，手机往往黑屏在口袋里——系统默认不许应用在这时把自己弹到前台。\n\n" +
                "把「显示在其他应用上层」给本应用，系统才会放行这次启动；否则它会静默拦下：" +
                "日志里写着已拉起，屏幕上却什么也没有，接收端也连不上。",
            listOf(
                DarkMessageDialog.Action("稍后", DarkMessageDialog.Emphasis.TEXT) {
                    log("APPROACH: 未授予「显示在其他应用上层」——后台拉起界面可能被系统拦下")
                },
                DarkMessageDialog.Action("去授权", DarkMessageDialog.Emphasis.PRIMARY) {
                    val opened =
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        }.isSuccess
                    log(
                        if (opened) {
                            "APPROACH: 已打开「显示在其他应用上层」设置——请允许本应用"
                        } else {
                            "APPROACH: 无法打开悬浮窗设置，请到 特殊访问权限 → 显示在其他应用上层 里允许"
                        },
                    )
                },
            ),
        )
    }

    /** 拉起系统的「忽略电池优化」授权页；部分 ROM 不认包名直连，退回列表页。 */
    private fun requestBatteryExemption() {
        val direct =
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val opened =
            runCatching { startActivity(direct) }.isSuccess ||
                runCatching { startActivity(fallback) }.isSuccess
        log(
            if (opened) {
                "APPROACH: 已打开电池优化设置——请把本应用设为「不限制」"
            } else {
                "APPROACH: 无法打开电池优化设置，请手动在系统设置里允许本应用后台运行"
            },
        )
    }

    /**
     * 启动连接的前置检查：**权限（一次申请完）** → 位置服务（仅 Android 12/12L）
     * → 蓝牙开关。任一不满足都引导（蓝牙关闭可一键跳系统设置），通过后回调继续。
     *
     * 权限这里只调用一次 [connectionPermissionLauncher]：以前是「先申请蓝牙权限、
     * 授权后再申请 WLAN 权限」两段接力，于是用户会连着看到两个内容几乎一样的系统框
     * （都带应用名），看起来就像「一直提示要与 CarPToCarL 配合的设备」。
     */
    private fun ensureBluetoothReady(onReady: () -> Unit) {
        val missing = requiredConnectionPermissions()
        if (missing.isNotEmpty()) {
            if (connectionPermissionDenied.get()) {
                log(
                    "权限: 仍缺 " +
                        missing.joinToString { it.substringAfterLast('.') } +
                        "——本次不再弹系统授权框（重复弹没有用，只能去设置里给）",
                )
                promptConnectionPermissionDenied(missing)
                return
            }
            pendingStartAfterPermission.set(true)
            connectionPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (!locationServiceEnabled()) {
            DarkMessageDialog.show(
                activity = this,
                title = "需要开启位置服务",
                message =
                    "读取已连接热点的名称需要开启位置服务，用于复用车机热点、减少重复选网。" +
                        "我们不读取手机坐标。开启后请重试。",
                actions =
                    listOf(
                        DarkMessageDialog.Action("取消") {},
                        DarkMessageDialog.Action("去开启", DarkMessageDialog.Emphasis.PRIMARY) {
                            runCatching {
                                startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                )
                            }.onFailure { log("WIFI: 无法打开位置服务设置，请手动开启") }
                        },
                    ),
            )
            log("WIFI: 位置服务未开启——无法核对当前热点名称，本次连接未发起")
            return
        }
        val on =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
                    ?.isEnabled == true
            } catch (_: SecurityException) {
                true
            }
        if (!on) {
            DarkMessageDialog.show(
                activity = this,
                title = "蓝牙未开启",
                message = "与车机的无线配对通信依赖蓝牙。请先开启手机蓝牙，并确认已与车机配对。",
                actions =
                    listOf(
                        DarkMessageDialog.Action("取消") {},
                        DarkMessageDialog.Action("去开启蓝牙", DarkMessageDialog.Emphasis.PRIMARY) {
                            runCatching {
                                startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                                )
                            }.onFailure { log("BT: 无法打开蓝牙设置，请手动开启") }
                        },
                    ),
            )
            log("BT: 蓝牙未开启——本次连接未发起")
            return
        }
        onReady()
    }

    /** 位置服务是否开启（仅 Android 12/12L 的应用专属网络需要）。 */
    private fun locationServiceEnabled(): Boolean =
        try {
            androidx.core.location.LocationManagerCompat
                .isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
        } catch (_: Exception) {
            true
        }

    /*
     * 这里原本还有 ensureWifiLocationReady()：权限检查（13+ 的 NEARBY_WIFI_DEVICES /
     * 12/12L 的 ACCESS_FINE_LOCATION）+ 位置服务检查，各自配一个 launcher，授权后
     * 再接力回连接流程。
     *
     * 2026-11-08 合并：权限部分收进 requiredConnectionPermissions() +
     * connectionPermissionLauncher（一次请求，系统只弹一个「附近的设备」框），
     * 位置服务检查上移进 ensureBluetoothReady()。原因是旧结构在首次连接时会
     * 依次弹「蓝牙」「附近的 WLAN 设备」「通知」三个系统框，用户体感就是
     * 「一直提示要与 CarPToCarL 配合的设备」。
     */

    /** 长按确认后沿用原断开行为，抑制停车期间自动重连。 */
    private fun disconnectFromButton() {
        CarApproachPrefs.setSuppressed(this, true)
        SessionController.disconnect(this)
    }

    /**
     * 「启动连接」放行检查。
     *
     * 旧实现这里是 Shizuku 硬门禁（虚拟屏/注入都靠它）。2026-10-24 起 Shizuku 依赖
     * 整体移除，画面唯一来源是本机 CarLife 直通。
     *
     * 2026-10-26 起这里加了**本机蓝牙地址硬门禁**：地址是整个链路的设备身份
     * （0x4300/0x4E0E、AirPlay SETUP、ctrl 广播 id 都用它），没有它连上去接收端
     * 只会回 SETUP 403，用户看到的却是一句「连接失败」，无从下手。所以未填写时
     * 直接拦下并提示，而不是让他白等一轮。
     *
     * 放在这里而不是按钮的点击回调里，是因为所有入口（手动点、启动后自动连接、
     * 蓝牙/位置权限授权后续跑）都汇到这一个函数，改一处即可全覆盖。
     */
    private fun proceedToConnect(onReady: () -> Unit) {
        if (!requireBluetoothMac()) return
        onReady()
    }

    /**
     * 当前可用的本机蓝牙地址：手填优先 → 已解析缓存。都没有返回 null。
     *
     * 只读缓存（[DeviceIdentity.cachedBluetoothMac]），**不**触发 shell 解析——
     * 本方法在 UI 线程上每次 tick 都可能被调用，绝不能再花 4 秒去 `content query`。
     * onCreate 已经用完整解析跑过一轮，正常机型这里能直接拿到真实地址。
     */
    private fun effectiveBluetoothMac(): String? =
        DeviceIdentity.overrideBluetoothMac(this)
            ?: DeviceIdentity.cachedBluetoothMac()?.takeIf { it != DeviceIdentity.PLACEHOLDER_MAC }

    /**
     * 本机蓝牙地址门禁：未填写就拦下「启动连接」并告知用户。
     *
     * @return true = 地址已就绪，可以继续；false = 未填写，已提示并中止本次启动。
     */
    private fun requireBluetoothMac(): Boolean {
        // 连接只使用已保存的地址，编辑中的草稿不能隐式写入设备身份。
        val mac = effectiveBluetoothMac()
        refreshMacGate()
        if (mac != null) return true

        snack(TIP_MAC_MISSING, 3_600L)
        log("MAC: 本机蓝牙地址未填写——已拦截本次「启动连接」")
        // 焦点落到输入框并滚到可视区，用户下一步该做什么一眼可见
        macInput.requestFocus()
        macInput.post {
            macInput.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, macInput.width, macInput.height),
                true,
            )
        }
        return false
    }

    /**
     * 刷新门禁相关的界面：IDLE 时英雄卡副标题与按钮置灰。
     *
     * 「置灰但仍可点」是刻意的：完全 `isEnabled=false` 的话，用户点下去没有任何
     * 反馈，只会以为 App 卡了；保持可点、点击即给出「未填写」提示才是有效引导。
     */
    private fun refreshMacGate() {
        if (!::macInput.isInitialized || !::ctaButton.isInitialized) return
        val mac = effectiveBluetoothMac()
        if (heroState == HeroState.IDLE) {
            ctaButton.alpha = if (mac == null) CTA_BLOCKED_ALPHA else 1f
            if (pendingResidualDisplays == null) {
                heroSubline.text = if (mac == null) HINT_MAC_MISSING else HINT_IDLE_READY
            }
        }
    }

    /** 「启动连接」主体（权限已在 [ensureBluetoothReady] 里一次申请完；会话启动交给 [SessionController]，幂等不双开）。 */
    private fun startConnectionNow() {
        runOnUiThread {
            // 通知权限（API 33+）：前台服务通知可见；拒绝也不影响保活。
            // **每个进程只问一次**——以前每点一次「启动连接」就问一遍，用户会以为
            // App 在反复索要权限（这个框同样带应用名）。拒绝后不再纠缠。
            if (Build.VERSION.SDK_INT >= 33 &&
                notificationPermissionAsked.compareAndSet(false, true) &&
                !isGranted("android.permission.POST_NOTIFICATIONS")
            ) {
                permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
            SessionController.startConnection(this)
        }
    }

    /** 通知权限是否已经问过（只问一次，拒绝也不再打扰）。 */
    private val notificationPermissionAsked =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configureSystemBars()
        // 告诉「靠近车机」那边：界面确实起来了（它据此判断后台启动有没有被系统拦下）
        AppUiPresence.markBroughtUp()
        // 起来就把「系统没让 App 到前台」那条提示撤掉——它已经没有意义了
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(CarApproachReceiver.BLOCKED_NOTIFICATION_ID)
        }

        heroStateText = findViewById(R.id.heroStateText)
        btnResidualCleanup = findViewById(R.id.btnResidualCleanup)
        btnResidualCleanup.setOnClickListener {
            pendingResidualDisplays?.let { promptResidualDisplays(it) }
        }
        heroSubline = findViewById(R.id.heroSubline)
        heroStats = findViewById(R.id.heroStats)
        ctaButton = findViewById(R.id.ctaButton)
        ctaIcon = findViewById(R.id.ctaIcon)
        ctaLabel = findViewById(R.id.ctaLabel)
        ssidInput = findViewById(R.id.ssidInput)
        macInput = findViewById(R.id.macInput)
        btDesc = findViewById(R.id.btDesc)
        btnPickTarget = findViewById(R.id.btnPickTarget)
        logBody = findViewById(R.id.logBody)
        logChevron = findViewById(R.id.logChevron)
        switchLog = findViewById(R.id.switchLog)
        logText = findViewById(R.id.logText)
        snackbar = findViewById(R.id.snackbar)

        logText.movementMethod = ScrollingMovementMethod()
        // 控制台在长页面 ScrollView 内：按下时申请父级不拦截竖向手势，否则无法手动滚动
        logText.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP,
                -> updateLogBottomButton()
            }
            false
        }
        findViewById<ImageView>(R.id.btnLogBottom).setOnClickListener {
            // 跳到底部：滚动后自动跟底也随之恢复
            scrollConsoleToBottom()
        }

        // 进程重启兜底：若上次会话未正常退役，「常亮」改过的屏幕超时需要恢复
        run {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.contains("screen_off_timeout_backup") &&
                VirtualLauncher.appDisplayId < 0
            ) {
                val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
                prefs.edit().remove("screen_off_timeout_backup").apply()
                Bg.run("screen-timeout-restore") {
                    SysShell.exec(
                        "settings put system screen_off_timeout $backup",
                    )
                }
            }
        }

        // 会话中枢（进程级）：登记 applicationContext + 创建会话对象；
        // 挂屏注册：日志续显 + SSID 回填
        SessionController.ensure(this)
        SessionController.logListener = logListener
        // 连接流程的重要提示（WiFi 未开启/热点加入限制等）以 Snackbar 长显
        SessionController.uiHintSink = uiHintSinkRef
        // 兜底提示弹窗（现在只剩「WiFi 未开启」一种：加入热点的确认框已在 2026-11-08
        // 全部取消，改为静默自动连）。取消连接时 cancel() 联动关闭（触发 onChoice(null)
        // → 流程检查取消标志中止）
        SessionController.dialogSink = { spec, onChoice ->
            runOnUiThread {
                if (isFinishing || isDestroyed || !spec.isValid()) {
                    onChoice(null)
                    return@runOnUiThread
                }
                var answered = false
                // 取消（返回键/点外部/被 cancelConnecting 联动关闭）= null，流程据此中止；
                // Navigation belongs to the caller: negative may mean cancel/keep/wait.
                val dialog =
                    DarkMessageDialog.show(
                        activity = this,
                        title = spec.title,
                        message = spec.message,
                        actions =
                            listOf(
                                DarkMessageDialog.Action(spec.negative) {
                                    answered = true
                                    log("CONNECT-DIAG: wifi-dialog choice=negative title=${spec.title}")
                                    onChoice(false.takeIf { spec.isValid() })
                                },
                                DarkMessageDialog.Action(
                                    spec.positive,
                                    DarkMessageDialog.Emphasis.PRIMARY,
                                ) {
                                    answered = true
                                    log("CONNECT-DIAG: wifi-dialog choice=positive title=${spec.title}")
                                    onChoice(true.takeIf { spec.isValid() })
                                },
                            ),
                        onCancel = { if (!answered) onChoice(null) },
                    )
                wifiJoinDialog = dialog
                log("CONNECT-DIAG: wifi-dialog shown title=${spec.title}")
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val monitor = object : Runnable {
                    override fun run() {
                        if (!dialog.isShowing) return
                        if (isFinishing || isDestroyed || !spec.isValid()) {
                            log("CONNECT-DIAG: wifi-dialog retired title=${spec.title}")
                            dialog.cancel()
                        } else handler.postDelayed(this, 200)
                    }
                }
                handler.postDelayed(monitor, 200)
            }
        }
        SessionController.ssidSink = ssidSink
        refreshWifiMemoryDisplay()
        renderConsole(SessionController.logText())

        setUpUiControls(savedInstanceState)
        // 首次启动和窗口尺寸变化都按可用窗口宽度选择栏数。
        applyContentLayout()
        findViewById<android.widget.FrameLayout>(R.id.contentArea).post {
            if (!isFinishing && !isDestroyed) applyContentLayout()
        }

        // 启动后自动连接：只在**冷启动**发起（旋转/重建不重复）。
        // 真正发起由 maybeAutoConnect 判定——前置条件不齐时它会等下一次时机。
        // 「靠近车机」拉起的这一轮即使 Activity 正在后台重建，也要重新武装一次。
        //
        // 这里刻意留 700ms 再判定：`detectResidualDisplays()` 在 onCreate 更靠后执行，
        // 它会把 startupCheckRunning 置真。延后判定是为了让残留虚拟屏扫描先开跑——
        // 一边扫描一边起新会话会互相踩（旧屏未清就建新屏）。扫描结束时它也会再调一次
        // maybeAutoConnect，所以真正的发起时机以自检完成为准，这里只是起步。
        val launchedByApproach = intent?.getBooleanExtra(EXTRA_CAR_APPROACH, false) == true
        // 用户**自己**点开了 App = 明确想用：立刻解除「刚断开过」的抑制。
        // 否则在车上（蓝牙一直连着，不会再收到 ACL_DISCONNECTED）关掉 App 之后，
        // 抑制会一直挂着，之后的抖动/车机重启都唤不醒它（2026-10-30 用户要求：
        // 还在车上时必须能重新拉起）。自动拉起那一轮不清，避免自己解除自己的防抖。
        if (!launchedByApproach && CarApproachPrefs.suppressed(this)) {
            CarApproachPrefs.setSuppressed(this, false)
            log("APPROACH: 用户主动打开 App——已解除「刚断开过」的抑制，本次仍可自动触发")
        }
        if (savedInstanceState == null || launchedByApproach) {
            autoConnectRequested = true
            if (launchedByApproach) {
                log(
                    "APPROACH: 检测到目标车机蓝牙靠近，App 已被自动拉起——" +
                        "由前台发起连接",
                )
                // 消费掉标记：旋转/重建时不再当作一次新的靠近
                intent?.removeExtra(EXTRA_CAR_APPROACH)
            }
            mainHandler.postDelayed({ maybeAutoConnect() }, 700L)
        }
    }

    /** onCreate 的 UI 初始化尾部：开关/按钮/折叠卡（会话逻辑不变，仅挂新界面）。 */
    private fun setUpUiControls(savedInstanceState: Bundle?) {
        val preferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // 清理旧版本遗留的模式偏好（模式已固定为「隐形屏」启动器）
        if (preferences.contains("mode")) {
            preferences.edit().remove("mode").apply()
        }

        // ---- 英雄卡主操作 ----
        ctaButton.setOnClickListener {
            when (heroState) {
                HeroState.CONNECTED -> Unit
                // 前置检查：蓝牙权限/开关 → 音频选择（Shizuku 已移除，不再有硬门禁）
                HeroState.IDLE -> proceedToConnect { ensureBluetoothReady { startConnectionNow() } }
                // 连接中仅由长按确认，短按不取消。
                HeroState.CONNECTING -> Unit
                null -> {}
            }
        }

        cancelHold = io.github.shaonialife.carptocar.ui.HoldToCancel(
            ctaButton,
            allowed = {
                heroState == HeroState.CONNECTED ||
                    (heroState == HeroState.CONNECTING && SessionController.videoSender?.isRunning != true)
            },
            confirm = {
                if (heroState == HeroState.CONNECTED) disconnectFromButton() else cancelConnectingFromButton()
            },
        )

        // ---- 连接准备 ----
        // 启动后自动连接：默认开启。打开 App 即发起连接，不必每次点英雄卡；
        // 需要手动控制连接启动时关掉它。
        val switchAutoConnect = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoConnect)
        switchAutoConnect.isChecked = AutoConnectPrefs.enabled(this)
        switchAutoConnect.setOnCheckedChangeListener { _, checked ->
            AutoConnectPrefs.setEnabled(this, checked)
            log("AUTOCONNECT: 启动后自动连接已${if (checked) "开启" else "关闭"}")
        }

        // 靠近车机自动启动：后台静默检测（只听系统蓝牙 ACL 事件，不扫描不轮询）。
        // 前提是「目标车机」已指定——否则一律不动作，避免误连测试台/其它已配对设备。
        val switchCarApproach =
            findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchCarApproach)
        switchCarApproach.isChecked = CarApproachPrefs.enabled(this)


        // 用户已开启时，进界面按 7 天节流提醒一次。
        // 延后 3 秒提示，避免打断首页首次展示。
        if (switchCarApproach.isChecked && TargetDevicePrefs.get(this) != null) {
            mainHandler.postDelayed(
                {
                    if (!isFinishing && !isDestroyed) {
                        ensureBatteryExempt()
                        // 两半缺一不可：电池优化保「收得到广播」，悬浮窗保「起得来界面」
                        ensureOverlayPermission()
                    }
                },
                3_000L,
            )
        }
        switchCarApproach.setOnCheckedChangeListener { _, checked ->
            CarApproachPrefs.setEnabled(this, checked)

            log(
                "APPROACH: 靠近车机自动启动已${if (checked) "开启" else "关闭"}" +
                    (if (checked && TargetDevicePrefs.get(this) == null) "（尚未指定目标车机，暂不会触发）" else ""),
            )
            // 手机端能不能「先于别人动手」取决于系统愿不愿意把广播投递过来：
            // 电池优化一开，后台进程被冻结/限流，ACL 广播就送不到，功能表现为时好时坏。
            // 用户刚拨开开关 = 明确要用这个功能，此时不受 7 天节流限制。
            if (checked) {
                ensureBatteryExempt(force = true)
                ensureOverlayPermission(force = true)
            }
        }

        // 本机蓝牙地址：Honor/MagicOS 等机型把 MAC 全面脱敏（连 adb shell 都读不到），
        // 而 WinPlay 系接收端按它做设备过滤——手填是唯一可行来源，存进 settings 偏好，
        // DeviceIdentity 会优先采用（影响 0x4300/0x4E0E、AirPlay SETUP 与 ctrl 广播 id）。
        val savedMac = DeviceIdentity.overrideBluetoothMac(this) ?: effectiveBluetoothMac()
        macEditing = savedInstanceState?.getBoolean("macEditing") ?: (savedMac == null)
        macInput.setText(if (macEditing) savedInstanceState?.getString("macDraft") ?: savedMac.orEmpty() else savedMac.orEmpty())
        val macKeyListener = macInput.keyListener
        val macButton = findViewById<TextView>(R.id.btnSaveMac)
        fun setMacEditing(editing: Boolean) {
            macEditing = editing
            macInput.isEnabled = editing
            macInput.setTextColor(getColor(if (editing) R.color.m3_on_surface else R.color.m3_on_var))
            macInput.keyListener = if (editing) macKeyListener else null
            macInput.isFocusable = editing
            macInput.isFocusableInTouchMode = editing
            macInput.isCursorVisible = editing
            macInput.isClickable = editing
            macInput.isLongClickable = editing
            macButton.text = if (editing) "保存" else "修改"
            if (!editing) {
                macInput.clearFocus()
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(macInput.windowToken, 0)
            }
        }
        setMacEditing(macEditing)
        macButton.setOnClickListener {
            if (!macEditing) {
                setMacEditing(true)
                macInput.requestFocus()
                macInput.setSelection(macInput.text.length)
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(macInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                return@setOnClickListener
            }
            val result = DeviceIdentity.setOverride(this, macInput.text.toString())
            val resolved = DeviceIdentity.bluetoothMac(this, forceRetry = true)
            when {
                result == null -> {
                    snack("地址格式不对——需 12 位十六进制，如 AA:BB:CC:DD:EE:FF")
                    log("MAC: 保存失败——输入无法解析为蓝牙地址（${macInput.text}）")
                }
                resolved == DeviceIdentity.PLACEHOLDER_MAC -> {
                    macInput.setText("")
                    snack("已清除手填地址——将使用自动解析结果")
                    log("MAC: 已清除手填地址，当前自动解析=${DeviceIdentity.PLACEHOLDER_MAC}（接收端会拒绝）")
                }
                else -> {
                    macInput.setText(resolved)
                    setMacEditing(false)
                    snack("已保存 $resolved")
                    log("MAC: 本机蓝牙地址已设为 $resolved（0x4300/SETUP/ctrl 广播都用它）")
                }
            }
            // 保存/清除都直接影响「启动连接」门禁，立刻同步连接提示与按钮状态
            refreshMacGate()
        }

        // 「画质增强」（写 CarLife 官方调试配置）入口已按用户要求从首页移除：
        // 实测这条 MediaFormat 通道对码率/帧率无效，而 /sdcard/BaiduCarlife/vehicle
        // 一存在就会让 CarLife 进入调试模式，白担开销。CarLifeDevConfig 保留备用
        // （需要时可在代码里直接调用 provision()），界面不再暴露。

        findViewById<TextView>(R.id.btnClearWifiMemory).setOnClickListener {
            if (SessionController.clearRememberedWifi(this)) snack("已清除车机 Wi-Fi 记忆，下次通过蓝牙重新获取")
            else snack("请先断开连接，再清除 Wi-Fi 记忆")
        }
        // 目标车机选择：配对了多台车机时指定唯一目标（只向它发请求/只接受它连入）
        btnPickTarget.setOnClickListener { showTargetPicker() }

        // ---- 折叠卡：日志 ----
        bindExpandable(R.id.logHeader, logBody, logChevron)

        // 启动残留虚拟屏检测（页面已不再展示 Shell 状态，仅保留自动清理与英雄卡状态联动）
        detectResidualDisplays()

        // ---- 实时日志开关（默认关闭；不影响文件记录和导出；选择持久化） ----
        var logEnabled = preferences.getBoolean("logEnabled", false)
        SessionController.logEnabled = logEnabled
        switchLog.isChecked = logEnabled
        renderConsole(SessionController.logText())
        switchLog.setOnCheckedChangeListener { _, checked ->
            logEnabled = checked
            SessionController.logEnabled = checked
            preferences.edit().putBoolean("logEnabled", checked).apply()
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnExportLog).setOnClickListener { exportLogFile() }
        // 长按导全部历史：默认只导本次启动，历史日志量大时按需手动取
        findViewById<ImageView>(R.id.btnExportLog).setOnLongClickListener {
            exportLogFile(LogExporter.Scope.ALL)
            true
        }

        // 这里原本「启动即申请 BLUETOOTH_CONNECT」：结果是**每打开一次 App 就弹一次
        // 系统「附近的设备」框**（框里带应用名），用户会以为程序在反复索要权限。
        // 权限改到真正要用的时候（点「启动连接」/ 打开自动连接）一次性申请，
        // 见 ensureBluetoothReady()。

        // 通知使用权（媒体会话枚举，音乐卡数据源）：Shizuku 移除后没有静默授予通道，
        // 需要用户自己在系统设置里打开；这里不再阻塞启动。

        // ---- 显示设置（原「显示」面板迁移至首页，2026-10-24） ----
        bindDisplayCard()

        // 常亮+暗幕：界面开关已移除，行为固定为「默认开启」，由会话起停驱动
        // （ScreenDimmer 由 VirtualLauncher.attach 在会话启动时挂载）。
        ScreenDimmer.enabled = true

        TouchRouter.logSink = { line -> log(line) }
        VirtualLauncher.logSink = { line -> log(line) }
        CarInputClient.logSink = { line -> log(line) }

        // 启动即开始守候 CarLife 端口 —— 车机画面的唯一来源（隐形屏已按用户要求移除）。
        // **不自动拉起 CarLife+**：由用户手动打开它并点「无线连接」，端口一开即接管。
        if (CarLifeHijack.isEnabled(this)) {
            mainHandler.postDelayed(
                {
                    CarLifeHijack.prewarmFromStartup(applicationContext) { line ->
                        runOnUiThread { log(line) }
                    }
                },
                800L,
            )
        } else {
            log("CARLIFE: 本地劫持已在设置里关闭——本轮不会有车机画面")
        }

        // 版本号走运行时读取：BuildConfig.VERSION_NAME 会被 Kotlin 折叠成字面量，
        // 改版本号后增量编译不重编本类时，日志会印上一版的号（1.2.13 踩过一次）。
        log("CarPToCarL ${AppVersion.nameWithTag(this)}（画面=本地劫持 CarLife · 音频=PCM 直通）就绪")
        log("真车：与车机蓝牙配对 + 手机加入车机热点 -> 点「启动连接」（自动主动连入车机）")
        log("测试台：两台手机蓝牙配对 -> 备用机跑 xcertplay 无线模式 -> 点「启动连接」等待回连")
        // 接收端（WinPlay 等）按真实蓝牙地址过滤设备：解析不到就等于注定 SETUP 403，
        // 必须在启动时就明确告知，而不是让用户对着「接收端拒绝了会话」无从上手。
        val identityMac = DeviceIdentity.bluetoothMac(this)
        if (identityMac == DeviceIdentity.PLACEHOLDER_MAC) {
            log(
                "MAC: 未取到本机真实蓝牙地址（厂商脱敏/Shizuku 已移除）——" +
                    "请在上方「本机蓝牙地址」填入后保存；否则 WinPlay 类接收端会回 SETUP 403",
            )
        } else {
            val from = if (DeviceIdentity.hasOverride(this)) "手填" else "自动解析"
            log("MAC: 本机蓝牙地址=$identityMac（$from）")
        }
        // 启动即按上面的解析结果刷新门禁（副标题/按钮置灰/输入框提示行）
        refreshMacGate()
    }

    private fun bindExpandable(
        headerId: Int,
        body: View,
        chevron: ImageView,
    ) {
        findViewById<View>(headerId).setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            chevron
                .animate()
                .rotation(if (opening) 180f else 0f)
                .setDuration(250L)
                .start()
        }
    }

    // ==================== 英雄卡状态机 ====================

    private fun tickHero() {
        val s =
            when {
                SessionController.videoSender?.isRunning == true -> HeroState.CONNECTED
                SessionController.isSessionRunning() || SessionController.sessionStarting.get() ->
                    HeroState.CONNECTING
                else -> HeroState.IDLE
            }
        if (s != heroState) {
            if (s == HeroState.CONNECTED) connectedAt = SystemClock.elapsedRealtime()
            heroState = s
            applyHeroState(s)
        }
        // 连接中：副标题跟随阶段提示（英雄卡内即可见，不必划到底部看日志）
        if (s == HeroState.CONNECTING) {
            heroSubline.text = (SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT).trimEnd('…', '.', ' ')
        }
        // 启动残留屏检查进行中：状态与副标题临时切换为检查提示
        if (s == HeroState.IDLE && startupCheckRunning) {
            heroStateText.text = "检查中"
            heroSubline.text = "正在检查残留屏…"
        }
        updateConnectedSubline()
        updateHeroStats()
        updateReadyRow()
    }

    /**
     * 当前真正在用的码流尺寸。
     *
     * 直通模式下由 CarLife 引擎决定，而且**会变**：会话建立时引擎往往还停在默认
     * 1280×720，收到车机分辨率（98311）之后才重建编码器。所以这里每次现取，
     * 不能只记建链那一刻的快照——否则界面会一直显示临时值。
     *
     * 取值顺序是硬性的：**先 SPS 真值**。98311 申请值与 65544 回包值都只是「谈定的
     * 档位」，引擎会按白名单降档（申请 1412×654 实编 1280×720，2026-10-29 日志实证）。
     * 拿协商值当码流尺寸，界面就会写着「码流同尺寸」而车机上根本不是那个尺寸。
     */
    private fun effectiveStreamSize(): Pair<Int, Int>? {
        if (CarLifeHijack.isAttached || CarLifeHijack.isActive) {
            CarLifeHijack.actualStreamSize?.let { return it }
            val (w, h) = CarLifeHijack.videoSize
            if (w > 0 && h > 0) return w to h
        }
        val w = SessionController.activeStreamWidth
        val h = SessionController.activeStreamHeight
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * 码流尺寸是否已经由 SPS 确认（false = 手上只有协商档位，还在等第一帧关键帧）。
     *
     * 非直通模式（自己出画面）没有「协商 vs 实编」这回事，恒为 true。
     */
    private fun streamSizeConfirmed(): Boolean =
        if (CarLifeHijack.isAttached || CarLifeHijack.isActive) {
            CarLifeHijack.actualStreamSize != null
        } else {
            true
        }

    /**
     * 英雄卡统计行（仅连接时显示）：运行时分辨率 + 实测帧率。
     * 实际帧率 = 1s 轮询对已发帧计数取差值；新会话计数归零时先对齐基线。
     * 目标帧率不再展示（车机报 maxFPS 就跟随、没报按 60，见 [StreamFps]），只报实测值。
     *
     * 分辨率报**像素**，不再是「100%」这种只有自己看得懂的比例：车机原值在前，
     * 码流与它不一致时用 `→` 接实际值（直通模式由 CarLife 引擎定尺寸）。
     */
    private fun updateHeroStats() {
        if (heroState != HeroState.CONNECTED) return
        val sent = SessionController.videoSender?.framesSentTotal ?: 0
        if (sent < lastSentFrames) lastSentFrames = sent
        val fps = sent - lastSentFrames
        lastSentFrames = sent
        val carW = SessionController.activeCarWidth
        val carH = SessionController.activeCarHeight
        val stream = effectiveStreamSize()
        val resolution =
            when {
                carW <= 0 -> "—"
                !SessionController.activeCarResolutionFromCar -> {
                    // 车机没回 /info：这个值其实是手机横屏兜底，必须标明
                    "${carW}×$carH（手机兜底）"
                }
                stream == null || (stream.first == carW && stream.second == carH) -> "$carW×$carH"
                else -> "$carW×$carH → ${stream.first}×${stream.second}"
            }
        heroStats.text = "分辨率 $resolution · $fps fps"
    }

    private fun applyHeroState(s: HeroState) {
        heroStateText.text =
            when (s) {
                HeroState.IDLE -> "未连接"
                HeroState.CONNECTING -> "正在连接"
                HeroState.CONNECTED -> "已连接"
            }
        heroStats.visibility = if (s == HeroState.CONNECTED) View.VISIBLE else View.GONE

        when (s) {
            HeroState.IDLE -> {
                val pending = pendingResidualDisplays
                // 门禁：本机蓝牙地址未填写 → 副标题直接说明卡在哪，按钮同时置灰
                val macMissing = effectiveBluetoothMac() == null
                heroSubline.text =
                    when {
                        pending != null -> "待清理 ${pending.size} 个残留屏"
                        macMissing -> HINT_MAC_MISSING
                        else -> HINT_IDLE_READY
                    }
                styleCta(
                    R.drawable.bg_cta_fill,
                    R.color.m3_on_primary,
                    R.drawable.ic_play,
                    "启动连接",
                    blocked = macMissing,
                )
            }
            HeroState.CONNECTING -> {
                heroSubline.text = (SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT).trimEnd('…', '.', ' ')
                styleCta(R.drawable.bg_cta_fill, R.color.m3_on_primary, R.drawable.ic_close, "长按取消连接")
            }
            HeroState.CONNECTED -> {
                styleCta(R.drawable.bg_cta_outline_err, R.color.m3_error, R.drawable.ic_power, "长按断开连接")
            }
        }

        ctaIcon.rotation = 0f
    }

    /**
     * 英雄卡主操作按钮统一样式（三个状态仅风格/图标/文案不同）。
     *
     * @param blocked 置灰但不禁用——门禁未通过时仍可点击，点上会立刻说明原因
     *   （禁用态点下去毫无反馈，用户只会以为界面卡死）。
     */
    private fun styleCta(
        bgRes: Int,
        colorRes: Int,
        iconRes: Int,
        label: String,
        blocked: Boolean = false,
    ) {
        cancelHold?.cancel()
        ctaButton.setBackgroundResource(bgRes)
        ctaLabel.setTextColor(getColor(colorRes))
        ctaIcon.imageTintList =
            android.content.res.ColorStateList
                .valueOf(getColor(if (bgRes == R.drawable.bg_cta_fill) R.color.q4_action_accent else colorRes))
        ctaIcon.setImageResource(iconRes)
        ctaLabel.text = label
        ctaButton.contentDescription = label
        ctaButton.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: android.view.accessibility.AccessibilityNodeInfo,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
        ctaButton.isEnabled = true
        ctaButton.alpha = if (blocked) CTA_BLOCKED_ALPHA else 1f
    }

    private fun updateConnectedSubline() {
        if (heroState != HeroState.CONNECTED) return
        val elapsed = SystemClock.elapsedRealtime() - connectedAt
        val mm = elapsed / 60_000
        val ss = elapsed % 60_000 / 1_000
        heroSubline.text = String.format(Locale.US, "%02d:%02d", mm, ss)
    }

    private fun bindDisplayCard() {
        val navigationSwitch = findViewById<SwitchCompat>(R.id.switchInstrumentNavigation)
        io.github.shaonialife.carptocar.media.NavigationNowPlaying.ensure(this)
        navigationSwitch.isChecked = io.github.shaonialife.carptocar.media.NavigationNowPlaying.isNavigation()
        navigationSwitch.setOnCheckedChangeListener { _, enabled ->
            io.github.shaonialife.carptocar.media.NavigationNowPlaying.select(enabled)
        }
        val ctx = this

        // 「使用车机 GPS」（默认关闭）：只控制要不要把车机 0xFFFB 定位转成 CarLife 0x18010。
        // 关掉即回到「完全用手机定位」，随时可来回切（下一条定位就按新值走）。
        val gpsSwitch = findViewById<SwitchCompat>(R.id.switchCarGps)
        gpsSwitch.isChecked = CarGpsOption.enabled
        gpsSwitch.setOnCheckedChangeListener { _, checked ->
            CarGpsOption.save(ctx, checked)
            snack(if (checked) "已启用：使用车机 GPS" else "已关闭：只用手机定位")
        }

        // 目标帧率没有设置项：车机 /info 报 maxFPS 就跟随，没报按 60（见 video/StreamFps.kt）。
        // 这里先按默认 60 起，等 SETUP 拿到车机值后由 SessionController 覆盖。
        VirtualLauncher.setStreamFps(StreamFps.DEFAULT_FPS)

    }

    /** 更新热点凭据和蓝牙配对状态。 */
    private fun updateReadyRow() {
        refreshWifiMemoryDisplay()
        renderWifiMemoryDisplay()
        val btGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        var bondedCount = -1
        if (btGranted) {
            try {
                val adapter =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                bondedCount = adapter?.bondedDevices?.size ?: -1
            } catch (_: SecurityException) {
                bondedCount = -1
            }
        }

        btDesc.text = TargetDevicePrefs.get(this)?.name ?: "未指定车机"
        if (bondedCount >= 0) {
            // 弹窗内可扫描并直接配对未配对设备，因此不再只在「已有配对」时显示：
            // 一台都没配对时，这里正是用户的入口。
            btnPickTarget.visibility = View.VISIBLE
        } else {
            btnPickTarget.visibility = View.GONE
        }
    }

    // ---- 目标车机选择弹窗（扫描/配对状态）----

    /** 当前打开的弹窗（扫描回调要往里加行；关闭时统一收尾）。 */
    private var targetPicker: androidx.appcompat.app.AlertDialog? = null

    private var targetPickerReceiver: BroadcastReceiver? = null

    /** 弹窗内已展示的扫描结果（按地址去重；已配对的单独进上组，不进这里）。 */
    private val scannedTargets = LinkedHashMap<String, TargetPickerEntry>()

    /** 扫描期间点过「配对」的设备地址（配对完成后自动选中为目标）。 */
    private var pendingBondAddress: String? = null

    private data class TargetPickerEntry(
        val address: String,
        val name: String,
        val rssi: Int,
        val bonded: Boolean,
    )

    /** 扫描权限被拒后置位：授权回调里继续（弹窗可能已关，则忽略）。 */
    private var pendingTargetScan = false

    private val targetScanPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BT-SCAN: BLUETOOTH_SCAN 授权: $granted")
            if (granted && pendingTargetScan) {
                pendingTargetScan = false
                targetPicker?.let { startTargetScan(it) }
            } else if (!granted) {
                pendingTargetScan = false
                targetPicker?.let { _ ->
                    updatePickerStatus("未授予「附近的设备」权限——无法扫描；可到系统设置授权后重开本弹窗", warn = true)
                    pickerToast("未授予「附近的设备」权限——无法扫描附近设备")
                }
            }
        }

    /**
     * 目标车机选择弹窗（2026-10-18 改版）：
     *  组 1「已配对」——直接选用（车机无线 CarPlay 的前置是系统级蓝牙配对）；
     *  组 2「附近的设备」——实时扫描（经典蓝牙 discovery），点击未配对设备即发起
     *  系统配对，配对成功后自动设为目标。
     *
     * 首行「自动（不指定）」保留原有语义：出站逐个尝试、入站来者不拒；
     * 选定后出站只连目标、入站拒绝非目标（防误连家里另一台车机）。
     * 选择立即生效（链路动态读取目标，无需重连）。
     */
    private fun showTargetPicker() {
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            snack("蓝牙未开启——请先开启蓝牙")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_target_picker, null)
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setView(view)
                .create()
        targetPicker = dialog
        scannedTargets.clear()
        pendingBondAddress = null
        view.findViewById<TextView>(R.id.btnCancelPicker).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnRescan).setOnClickListener { startTargetScan(dialog) }
        renderBondedGroup(view)
        dialog.setOnDismissListener {
            stopTargetScan()
            targetPicker = null
            pendingBondAddress = null
            updateReadyRow()
        }
        // 首帧前完成内容和窗口尺寸初始化，扫描结果只改变列表内容。
        dialog.create()
        PanelWindow.apply(dialog, this, heightFraction = 0.85f)
        view.requestFocus()
        dialog.show()
        startTargetScan(dialog)
    }

    /** 渲染「已配对」组：自动行 + 每个已配对设备。 */
    private fun renderBondedGroup(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.bondedList)
        val empty = view.findViewById<TextView>(R.id.bondedEmpty)
        list.removeAllViews()
        val current = TargetDevicePrefs.get(this)
        // 「自动（不指定）」是模式而非设备：单独放在两组之上，不混进「已配对」组
        view.findViewById<LinearLayout>(R.id.autoRowContainer).apply {
            removeAllViews()
            addView(
                inflateTargetRow(
                    parent = this,
                    name = "自动",
                    address = "轮询已配对设备",
                    action = if (current == null) "当前" else "选择",
                    highlighted = current == null,
                    iconTint = R.color.m3_outline,
                ) {
                    TargetDevicePrefs.set(this@MainActivity, null)
                    SessionController.onRememberedWifiTargetChanged()
                    log("TARGET: 已取消目标指定——出站逐个尝试已配对设备，入站接受任一设备")
                    // 先关弹窗再提示（snackbar 在 Activity 窗口内，会被弹窗盖住）
                    targetPicker?.dismiss()
                    snack("已切换为自动")
                },
            )
        }
        val bonded = bondedDevices()
        empty.visibility = if (bonded.isEmpty()) View.VISIBLE else View.GONE
        bonded.forEach { device ->
            val address = device.address
            val name = deviceDisplayName(device)
            val isCurrent = current != null && TargetDevicePrefs.matches(current, address)
            list.addView(
                inflateTargetRow(
                    parent = list,
                    name = name,
                    address = address,
                    action = if (isCurrent) "当前" else "选择",
                    highlighted = isCurrent,
                    iconTint = R.color.m3_primary,
                ) {
                    setTarget(device, name, closePicker = true)
                },
            )
        }
    }

    /** 单个设备行（两组共用；选中时红底白字）。 */
    private fun inflateTargetRow(
        parent: android.view.ViewGroup,
        name: String,
        address: String,
        action: String,
        highlighted: Boolean,
        iconTint: Int,
        onClick: () -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_target_device, parent, false)
        row.background =
            ContextCompat.getDrawable(
                this@MainActivity,
                if (highlighted) R.drawable.bg_bt_device_row_current else R.drawable.bg_bt_device_row,
            )
        row.findViewById<TextView>(R.id.devName).apply {
            text = name
            setTextColor(getColor(if (highlighted) android.R.color.white else R.color.m3_on_surface))
        }
        row.findViewById<TextView>(R.id.devAddr).apply {
            text = address
            setTextColor(getColor(if (highlighted) android.R.color.white else R.color.m3_mono_dim))
        }
        row.findViewById<TextView>(R.id.devAction).apply {
            text = action
            setTextColor(getColor(if (highlighted) android.R.color.white else R.color.m3_primary))
            background = if (highlighted) null else ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tbtn)
        }
        row.findViewById<ImageView>(R.id.devIcon).setColorFilter(
            getColor(if (highlighted) android.R.color.white else iconTint),
        )
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * 开始扫描附近设备：注册 ACTION_FOUND / ACTION_BOND_STATE_CHANGED 后 startDiscovery。
     * 发现结果按地址去重、排除已配对设备（它们在上一组）；点「配对」调 createBond()。
     */
    private fun startTargetScan(dialog: androidx.appcompat.app.AlertDialog) {
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            } ?: return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingTargetScan = true
            updatePickerStatus("需要「附近的设备」权限才能扫描…", warn = true)
            targetScanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            return
        }
        val scannedList = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // 已配对的地址集合：扫描结果里剔掉（避免同一设备在两处出现）
        val bondedAddresses =
            bondedDevices()
                .map { it.address }
                .toSet()
        scannedTargets.clear()
        scannedList.removeAllViews()
        updatePickerStatus("正在扫描附近的蓝牙设备…")
        stopTargetScan()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                            onTargetFound(dialog, device, rssi, bondedAddresses)
                        }

                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                            onBondStateChanged(dialog, device, state)
                        }
                    }
                }
            }
        targetPickerReceiver = receiver
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
        // 系统（蓝牙栈）发出的广播：必须用 EXPORTED 才能在部分 ROM 上收到
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val started =
            try {
                adapter.startDiscovery()
            } catch (_: SecurityException) {
                false
            }
        if (!started) {
            updatePickerStatus("扫描启动失败——请确认蓝牙已开启后点「重新扫描」", warn = true)
        } else {
            log("BT-SCAN: 开始扫描附近蓝牙设备")
        }
    }

    /** 扫描到一台设备：已配对→忽略；新设备→加一行（点「配对」发起系统配对）。 */
    private fun onTargetFound(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        rssi: Int,
        bondedAddresses: Set<String>,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address in bondedAddresses || scannedTargets.containsKey(address)) return
        val name = deviceDisplayName(device)
        scannedTargets[address] = TargetPickerEntry(address, name, rssi, bonded = false)
        val list = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // 扫描仍在继续：状态行保留并计数，用户知道还可以继续等其它设备。
        // 发现新设备只更新计数，保持当前列表位置。
        dialog.findViewById<TextView>(R.id.scanStatus)?.apply {
            text = "继续扫描中…已发现 ${scannedTargets.size} 台未配对设备"
            setTextColor(getColor(R.color.m3_mono_dim))
            visibility = View.VISIBLE
        }
        list.addView(
            inflateTargetRow(
                parent = list,
                name = name,
                address = "$address · ${rssi}dBm",
                action = "配对",
                highlighted = false,
                iconTint = R.color.m3_amber,
            ) {
                beginBond(device)
            },
        )
    }

    /** 点击未配对设备：先停扫描（扫描期间配对易失败），再 createBond()。 */
    private fun beginBond(device: BluetoothDevice) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        stopDiscoveryOnly()
        pendingBondAddress = address
        val name = deviceDisplayName(device)
        val ok =
            try {
                device.createBond()
            } catch (error: SecurityException) {
                log("BT-PAIR: 配对「$name」失败 - 缺少权限：${error.message}")
                false
            }
        if (ok) {
            log("BT-PAIR: 已发起与「$name」($address) 的配对——请在系统弹窗确认")
            pickerNotice("正在配对「$name」：请在系统弹窗确认")
        } else {
            pendingBondAddress = null
            log("BT-PAIR: 配对「$name」($address) 发起失败（设备可能已配对或蓝牙忙）")
            pickerNotice("无法发起配对——请在系统蓝牙设置中手动配对")
        }
    }

    /**
     * 弹窗打开期间的提示。
     *
     * 弹窗是**独立窗口**（更高层级 + 全屏遮罩），Activity 里的 snackbar 必然被它盖住
     * ——用户只能看到底部一条被压暗的色块（2026-10-18 反馈）。系统 Toast 属更高窗口层，
     * 能浮在弹窗之上；同时更新弹窗内的状态行作为长期可见的说明。
     */
    private fun pickerNotice(message: String) {
        pickerToast(message)
        updatePickerStatus(message, warn = true)
    }

    /**
     * 弹窗打开期间的系统 Toast。系统 Toast 属更高窗口层，能浮在弹窗之上；
     * Activity 内的 snackbar 会被弹窗（独立窗口 + 遮罩）完全盖住。
     */
    private fun pickerToast(message: String) {
        android.widget.Toast
            .makeText(this, message, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /** 更新扫描状态，不改变用户正在浏览的列表位置。 */
    private fun updatePickerStatus(
        message: String,
        warn: Boolean = false,
    ) {
        val dialog = targetPicker ?: return
        val status = dialog.findViewById<TextView>(R.id.scanStatus) ?: return
        status.text = message
        status.setTextColor(getColor(if (warn) R.color.m3_amber else R.color.m3_mono_dim))
        status.visibility = View.VISIBLE
    }

    /** 配对状态变化：成功后设为目标并关弹窗；失败给出提示。 */
    private fun onBondStateChanged(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        state: Int,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address != pendingBondAddress) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                pendingBondAddress = null
                val name = deviceDisplayName(device)
                log("BT-PAIR: ✓ 已与「$name」($address) 完成配对——自动设为目标")
                // 先关弹窗再提示：snackbar 在 Activity 窗口内，弹窗未关时会被盖住
                dialog.dismiss()
                setTarget(device, name)
                // 「只配对、不连接」：系统/车机在配对完成后常会自动建立 HFP/A2DP
                // 连接，而奥迪实测这种「已连接」状态会让车机拒绝 CarPlay。立即与
                // 1.5s 后各断一次（车机侧重连通常在这两个时间点之间发生）。
                val app = applicationContext
                val profileOperation = BtProfiles.captureOperation {
                    TargetDevicePrefs.get(app)?.address?.equals(address, true) == true
                }
                Bg.run("bt-pair-only") {
                    repeat(2) { attempt ->
                        if (!profileOperation.isCurrent()) return@run
                        val dropped = BtProfiles.disconnect(app, device, profileOperation)
                        if (dropped.isNotEmpty()) {
                            log(
                                "BT-PAIR: 已断开配对后自动建立的 ${dropped.joinToString("/")} 连接" +
                                    "（保持仅配对，避免车机拒绝 CarPlay）",
                            )
                        }
                        if (attempt == 0) {
                            try {
                                Thread.sleep(1_500)
                            } catch (_: InterruptedException) {
                                return@run
                            }
                        }
                    }
                }
            }

            BluetoothDevice.BOND_NONE -> {
                pendingBondAddress = null
                log("BT-PAIR: 与 $address 配对未完成（用户取消或失败）")
                // 弹窗仍开着：用 Toast（浮在弹窗之上）+ 弹窗内状态行，不用被遮挡的 snackbar
                pickerNotice("配对未完成——可再点「配对」重试，或到系统蓝牙设置手动配对")
            }
            // BOND_BONDING：继续等（系统确认框未应答）
        }
    }

    /**
     * 设为出站目标；正在进行的连接在下一轮尝试/下一次连入时切换。
     *
     * @param closePicker true = 先关闭设备选择弹窗再提示（snackbar 属 Activity 窗口，
     *   弹窗开着时必被盖住——见 pickerNotice 注释）。
     */
    private fun setTarget(
        device: BluetoothDevice,
        name: String,
        closePicker: Boolean = false,
    ) {
        if (closePicker) targetPicker?.dismiss()
        val previous = TargetDevicePrefs.get(this)
        TargetDevicePrefs.set(this, TargetDevicePrefs.Target(device.address, name))
        SessionController.onRememberedWifiTargetChanged()
        log(
            "TARGET: 目标车机已设为「$name」(${device.address})" +
                "——出站只连该设备、入站拒绝其它设备" +
                if (previous != null && previous.address != device.address) {
                    "（原目标「${previous.name}」）"
                } else {
                    ""
                },
        )
        snack("目标已设为「$name」——只向该设备发请求")
        updateReadyRow()
    }

    /** 停掉扫描并注销监听（弹窗关闭/重扫时调用）。 */
    private fun stopTargetScan() {
        stopDiscoveryOnly()
        targetPickerReceiver?.let {
            runCatching { unregisterReceiver(it) }
            targetPickerReceiver = null
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun stopDiscoveryOnly() {
        // 调用点均已先确认 BLUETOOTH_SCAN 已授予（扫描流程入口），且此处整体捕获异常
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    private fun bondedDevices(): List<BluetoothDevice> =
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.bondedDevices
                ?.toList()
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

    /** 设备展示名（无名称时退回地址）；读名需要 BLUETOOTH_CONNECT，异常时用地址。 */
    private fun deviceDisplayName(device: BluetoothDevice): String =
        try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            try {
                device.address
            } catch (_: SecurityException) {
                "未知设备"
            }
        }

    // ==================== 通用 UI 工具 ====================

    private fun log(message: String) {
        // 写入进程级缓冲（logcat + 文件 + 挂屏渲染均由 SessionController 统一处理）
        SessionController.appendLog(message)
    }

    /** Shell 查询/执行（仅后台线程调用；两个诊断面板共用，勿再各写局部函数）。 */
    private fun shExec(
        cmd: String,
        timeoutMs: Long = 4_000L,
    ): SysShell.Result =
        SysShell
            .exec(cmd, timeoutMs)

    private fun sh(cmd: String): String = shExec(cmd).output

    /**
     * 导出日志到「下载/CarPToCarL/」并弹窗告知位置。
     */
    private fun exportLogFile(scope: LogExporter.Scope = LogExporter.Scope.CURRENT_SESSION) {
        snack(if (scope == LogExporter.Scope.ALL) "正在导出全部日志…" else "正在导出本次启动的日志…")
        val fallback = SessionController.logText()
        Bg.run("export-log") {
            val result = LogExporter.export(applicationContext, fallback, scope)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    snack("导出失败——请检查存储空间后重试", 4_000L)
                    return@runOnUiThread
                }
                log("日志已导出（${scope.label}）: ${result.displayPath}（${result.bytes} 字节）")
                val scopeNote =
                    if (scope == LogExporter.Scope.CURRENT_SESSION) {
                        "本次启动日志 · 长按导出留存历史"
                    } else {
                        "全部留存日志 · 无固定天数"
                    }
                DarkMessageDialog.show(
                    this,
                    title = "日志已导出",
                    message =
                        "${result.displayPath}\n\n" +
                            "文件大小：${formatBytes(result.bytes)}\n$scopeNote",
                    actions =
                        listOf(
                            DarkMessageDialog.Action("打开文件") {
                                openExportedFile(result.uri, result.displayPath)
                            },
                            DarkMessageDialog.Action("打开文件夹") {
                                openExportFolder(result.displayPath)
                            },
                            DarkMessageDialog.Action("分享文件") {
                                shareExportedFile(result.uri)
                            },
                            DarkMessageDialog.Action("关闭", onClick = {}),
                        ),
                )
            }
        }
    }

    /** 人类可读大小：<1KB 显示字节（整数除法会显示成误导性的「0 KB」）。 */
    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes 字节"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }

    /**
     * 打开系统文件管理器并定位到导出目录（DocumentsUI 的 document URI 可直达子目录）。
     *
     * 为什么优先**显式组件**：荣耀 ROM 的隐式 ACTION_VIEW + directory 会被 resolver
     * 乱匹配（2026-10-18 实测弹出了「网易云音乐」默认项）；同一条 intent 用
     * `am start` 却正确落到 DocumentsUI。显式指定 FilesActivity 可跳过 resolver，
     * 失败再逐级回退（别的 ROM 组件名可能不同 → 隐式 → 外部存储根 → 文字指引）。
     */
    private fun openExportFolder(displayPath: String) {
        val folder = displayPath.substringBeforeLast('/')
        val relative = folder.removePrefix("/storage/emulated/0/")
        val dirUri =
            Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3A" + Uri.encode(relative).replace("/", "%2F"),
            )
        val explicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                component =
                    android.content.ComponentName(
                        "com.android.documentsui",
                        "com.android.documentsui.files.FilesActivity",
                    )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val implicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/directory",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        for (intent in listOf(explicit, implicit, storageRoot)) {
            if (runCatching { startActivity(intent) }.isSuccess) {
                if (intent !== explicit) snack("请按路径打开：$folder", 5_000L)
                return
            }
        }
        snack("请到「文件管理 → 下载 → CarPToCarL」查看", 5_000L)
    }

    /** 用系统默认应用打开（多数 ROM 会落到文件管理器 / 文本查看器预览）。 */
    private fun openExportedFile(
        uri: Uri,
        displayPath: String,
    ) {
        val view =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(view) }.isSuccess) return
        // 无文本查看器时退到「文件管理器」：DocumentsUI 的内部存储根（用户按路径进 下载/CarPToCarL）
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/root",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(storageRoot) }.isSuccess) {
            snack("请按路径打开：$displayPath", 5_000L)
            return
        }
        snack("没有可打开文件的应用——请到文件管理器中按上述路径查找", 5_000L)
    }

    /** 分享日志文件（微信/QQ/邮件等），内容 URI 带临时读权限。 */
    private fun shareExportedFile(uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        runCatching { startActivity(Intent.createChooser(send, "分享日志文件")) }
            .onFailure { snack("没有可分享的应用") }
    }

    private fun updateLogBottomButton() {
        findViewById<ImageView>(R.id.btnLogBottom)?.visibility =
            if (consoleAtBottom()) View.INVISIBLE else View.VISIBLE
    }

    /** 日志内容最大滚动偏移。 */
    private fun consoleMaxScroll(): Int {
        val layout = logText.layout ?: return 0
        return (
            layout.height + logText.totalPaddingTop + logText.totalPaddingBottom - logText.height
        ).coerceAtLeast(0)
    }

    /** 是否已在底部（40px 容差：吸收行高舍入，避免永远差几像素判不到底）。 */
    private fun consoleAtBottom(): Boolean = consoleMaxScroll() - logText.scrollY <= 40

    private fun scrollConsoleToBottom() {
        // 短内容保持顶部；长内容仅滚到实际底部，不能侵入内边距。
        logText.scrollTo(0, consoleMaxScroll())
        logText.post { updateLogBottomButton() }
    }

    private fun renderConsole(text: String) {
        // 改文本前已在底部（或用户上翻阅读中）：决定本次是否自动跟底
        val follow = consoleAtBottom()
        logText.text =
            if (SessionController.logEnabled) text else "实时显示已关闭，文件日志仍在记录"
        logText.post {
            if (!SessionController.logEnabled) {
                logText.scrollTo(0, 0)
                updateLogBottomButton()
            } else if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    /**
     * 增量追加一行日志（SessionController 逐行直送；此前每行全量 toString + setText
     * 整段文本，高频日志下 UI/GC 双重放大）。超限时从进程缓冲全量对账一次完成裁剪。
     */
    private fun appendConsole(line: String) {
        val follow = consoleAtBottom()
        if (logText.text.length > 44_000) {
            renderConsole(SessionController.logText())
            return
        }
        logText.append(line)
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    private var snackRunnable: Runnable? = null

    /** 自绘 M3 Snackbar（底部反色胶囊，替代 Toast）。 */
    private fun snack(
        message: String,
        durationMs: Long = 2_400L,
    ) {
        snackbar.text = message
        snackbar.visibility = View.VISIBLE
        snackbar.translationY = snackbar.height + 60f
        snackbar
            .animate()
            .translationY(0f)
            .setDuration(260L)
            .start()
        snackRunnable?.let { mainHandler.removeCallbacks(it) }
        snackRunnable =
            Runnable {
                snackbar
                    .animate()
                    .translationY(snackbar.height + 60f)
                    .setDuration(260L)
                    .withEndAction { snackbar.visibility = View.GONE }
                    .start()
            }.also { mainHandler.postDelayed(it, durationMs) }
    }

    /**
     * 解析 dumpsys display 中的 CarPlay 虚拟屏：返回 (displayId, 名称)。
     * 注意 uniqueId 形如 virtual:com.android.shell,2000,CarPlayDock,36——尾数 36 是
     * 代次计数器而非显示屏 ID；真实 ID 在 "displayId=39, uniqueId='…'" 段里。
     */
    private fun parseCarPlayDisplays(dump: String): List<Pair<Int, String>> =
        Regex("displayId=(\\d+), uniqueId='[^']*CarPlay(Dock|AppHi|App|Map)")
            .findAll(dump)
            .map { it.groupValues[1].toInt() to "CarPlay${it.groupValues[2]}" }
            .distinctBy { it.first }
            .toList()

    /**
     * 启动残留虚拟屏检测。
     *
     * 「高级 · Shell 全局状态」面板已按用户要求从界面移除（2026-10-24），这里只保留
     * 有实际价值的部分：上次会话异常退出遗留的 CarPlay 虚拟屏自动清理 + 英雄卡状态联动。
     */
    private fun detectResidualDisplays() {
        startupCheckRunning = true
        tickHero()
        Bg.run("residual-scan") {
            var residual =
                parseCarPlayDisplays(sh("dumpsys display"))
                    .filter { it.first !in currentSessionDisplayIds() }
            if (residual.isNotEmpty()) {
                log(
                    "SHELL: 启动检测到 ${residual.size} 个残留虚拟屏: " +
                        residual.joinToString(", ") { "${it.second}(${it.first})" },
                )
                // 残留屏 = 上次会话异常退出（进程被杀）：横屏覆盖的会话末复原
                // 也没执行——一并清理，避免应用在手机侧残留横屏状态
                runCatching {
                    val reset =
                        AppOrientationCompat
                            .resetAll(applicationContext)
                    if (reset > 0) log("ORIENTATION: 残留会话的横屏覆盖已复原 $reset 个")
                }
                // 先自动清理一轮（含旧版本 UserService 进程回收——其持有的屏跨进程
                // 无法 release，只有进程回收能释放；重装 APK 后按版本复用的旧进程正是
                // 「点清理释放 0/N」的根因，2026-10-16）。清不掉的再问用户。
                val released = releaseDisplays(residual.map { it.first })
                log("SHELL: 启动残留自动清理（释放 $released/${residual.size}）")
                if (released > 0) {
                    residual =
                        parseCarPlayDisplays(sh("dumpsys display"))
                            .filter { it.first !in currentSessionDisplayIds() }
                }
            }
            runOnUiThread {
                startupCheckRunning = false
                if (residual.isNotEmpty()) {
                    // 用户「暂不」时保留 pending，状态旁的按钮可再次发起清理
                    pendingResidualDisplays = residual
                    updateResidualChip()
                    if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                    promptResidualDisplays(residual)
                    // 自动展开日志卡：启动清理的两条记录直接可见（卡片默认折叠）
                    findViewById<View>(R.id.logBody).visibility = View.VISIBLE
                    findViewById<ImageView>(R.id.logChevron)
                        .animate()
                        .rotation(180f)
                        .setDuration(250L)
                        .start()
                } else if (heroState == HeroState.IDLE) {
                    // 无残留：恢复「未连接」默认状态展示
                    applyHeroState(HeroState.IDLE)
                }

                // 启动自动连接在这里续最后一次：扫描期间英雄卡显示「检查中」，
                // 且若有残留待确认，连接流程的热点/网络弹窗会和清理弹窗抢焦点，故推迟到这里。
                maybeAutoConnect()
            }

            // 预绑定 UserService：预热触摸注入，并触发旧版本残留进程回收（绑定回调内含
            // reapStaleUserServices——重装 APK 后旧进程持有的虚拟屏只有进程回收能释放）。
            // **必须晚于上方残留扫描**：回收会让旧进程的虚拟屏立即消失。
            CarInputClient.ensure(this)
        }
    }

    /** 当前会话持有的虚拟屏（不参与残留清理）。 */
    private fun currentSessionDisplayIds(): Set<Int> =
        buildSet {
            val launcher = VirtualLauncher
            listOf(
                launcher.dockDisplayId,
                launcher.appDisplayId,
                launcher.mapDisplayId,
                launcher.gridDisplayId,
            ).forEach { if (it >= 0) add(it) }
        }

    /** 启动检测到残留 CarPlay 虚拟屏：询问是否立即清理（上次会话异常退出遗留）。 */
    private fun promptResidualDisplays(residual: List<Pair<Int, String>>) {
        if (isFinishing || isDestroyed) return
        val names = residual.joinToString("\n") { "· ${it.second}（displayId=${it.first}）" }
        DarkMessageDialog.show(
            activity = this,
            title = "检测到残留虚拟屏",
            message =
                "发现 ${residual.size} 个上次会话遗留的 CarPlay 虚拟屏：\n$names\n\n" +
                    "残留屏会持续占用显示资源，并可能影响下次连接。是否立即清理？",
            actions =
                listOf(
                    DarkMessageDialog.Action("暂不") {},
                    DarkMessageDialog.Action("清理", DarkMessageDialog.Emphasis.PRIMARY) {
                        pendingResidualDisplays = null
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        Bg.run("residual-cleanup") {
                            val released = releaseDisplays(residual.map { it.first })
                            log("SHELL: 残留虚拟屏清理完成（释放 $released/${residual.size}）")
                            runOnUiThread {
                                snack("已释放 $released/${residual.size} 个残留屏")
                                if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                            }
                        }
                    },
                ),
            onCancel = {
                // 返回键/点外部关闭 = 暂不：保留英雄卡的「清理残留屏」入口
                updateResidualChip()
            },
        )
    }

    /** 英雄卡状态旁的「清理残留屏」按钮显隐（有待清理残留时出现）。 */
    private fun updateResidualChip() {
        btnResidualCleanup.visibility =
            if (pendingResidualDisplays != null) View.VISIBLE else View.GONE
    }

    /** 释放指定虚拟屏（CarInputClient 懒绑定 + 3 秒短等）。后台线程调用，返回成功数。 */
    private fun releaseDisplays(ids: List<Int>): Int {
        if (ids.isEmpty()) return 0
        var svc = CarInputClient.service
        if (svc == null) {
            CarInputClient
                .ensure(this)
            val deadline = SystemClock.uptimeMillis() + 3_000L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val bound = svc ?: return 0
        ids.forEach { runCatching { bound.releaseDisplay(it) } }
        // 未直接释放的屏极可能由旧版本 UserService 进程持有（releaseDisplay 只查本进程
        // map，跨进程无法释放——重装 APK 后 Shizuku 不回收旧进程的已知问题）。
        // 回收旧进程让其随进程销毁释放，再以实际扫描结果复核（进程回收是异步的）。
        CarInputClient.reapStaleUserServices()
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (true) {
            val alive = parseCarPlayDisplays(sh("dumpsys display")).map { it.first }.toSet()
            val gone = ids.count { it !in alive }
            if (gone >= ids.size || SystemClock.uptimeMillis() >= deadline) return gone
            Thread.sleep(200)
        }
    }

    /**
     * App 已在后台/前台时又被「靠近车机」拉起（singleTask + SINGLE_TOP）：
     * 不重建界面，只重新武装一次自动连接。已在连接或已连上时 [maybeAutoConnect] 自己会跳过。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 已在运行被拉到前台：也算「界面到位」，同样撤掉被拦提示
        AppUiPresence.markBroughtUp()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(CarApproachReceiver.BLOCKED_NOTIFICATION_ID)
        }
        if (!intent.getBooleanExtra(EXTRA_CAR_APPROACH, false)) return
        intent.removeExtra(EXTRA_CAR_APPROACH)
        if (isFinishing || isDestroyed) return
        log("APPROACH: 检测到目标车机蓝牙靠近（App 已在运行）——由前台重新发起连接")
        autoConnectRequested = true
        // App 已在后台运行过：启动自检那一轮早就跑完，不必再为它让路。
        // 只留一个极短延迟，让 onResume 先把窗口恢复到前台（权限弹窗需要已恢复的 Activity）。
        mainHandler.postDelayed({ maybeAutoConnect() }, 150L)
    }

    override fun onResume() {
        super.onResume()
        // 界面在前台 = 用户正看着：车机远离 30 秒后的收摊要让路（别把正在看的界面掐掉）
        AppUiPresence.markResumed()
        mainHandler.post(heroTick)
        // 从系统设置/别的界面回来：门禁提示与按钮状态重新对一次（地址可能已补填）
        refreshMacGate()

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("macEditing", macEditing)
        if (macEditing && ::macInput.isInitialized) outState.putString("macDraft", macInput.text.toString())
    }

    /**
     * 旋转（已声明 configChanges，Activity 不重建）：视图与所有内存状态原样保留。
     * 需要处理两件事：
     * 1. **内容区切成双栏**——横屏屏高只有 ~300dp，英雄卡单独就占满整屏，
     *    其余内容全被挤到屏幕外（用户实测「要滑动才能看到」）；
     * 2. **重算弹窗窗口**——它们按旧方向的屏宽算过（92%），不重算会只占中间一半。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFinishing || isDestroyed) return
        applyContentLayout()
        PanelWindow.resizeShowing(this)
    }

    /**
     * 窄窗口单栏，宽窗口双栏；大字体提高双栏阈值。
     * 两栏分别滚动，短窗口里主操作也能滚到，不被裁掉。
     */
    private fun configureSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        val light = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        val root = findViewById<View>(R.id.mainRoot)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                    androidx.core.view.WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            applyContentLayout()
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun applyContentLayout() {
        val scroll = findViewById<android.widget.ScrollView>(R.id.mainScroll)
        val row = findViewById<android.widget.LinearLayout>(R.id.contentRow)
        val hero = findViewById<android.widget.LinearLayout>(R.id.heroCard)
        val detail = findViewById<android.widget.LinearLayout>(R.id.detailColumn)
        val density = resources.displayMetrics.density
        val gap = (16 * density).toInt()
        val root = findViewById<View>(R.id.mainRoot)
        val windowWidth = resources.configuration.screenWidthDp -
            ((root.paddingLeft + root.paddingRight) / density).toInt()
        val landscape = windowWidth >= if (resources.configuration.fontScale > 1.3f) 840 else 600
        val heroPane = findViewById<android.widget.ScrollView>(R.id.heroPane)
        val page = findViewById<android.widget.LinearLayout>(R.id.pageColumn)
        page.layoutParams = (page.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            width = (windowWidth.coerceAtMost(1200) * density).toInt()
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        val padTop = ((if (landscape) 12 else 24) * density).toInt()
        val padBottom = ((if (landscape) 12 else 20) * density).toInt()
        hero.setPadding(hero.paddingStart, padTop, hero.paddingEnd, padBottom)
        if (landscape) {
            if (hero.parent === row) {
                row.removeView(hero)
                heroPane.addView(hero)
            }
            // 宽度用**屏幕宽减页面内边距**推算，不读 contentArea.width：
            // 旋转瞬间后者还是旧方向的测量值（竖屏 1168 → 英雄卡只有 537px，
            // 副标题被挤成三行，真机截图暴露）。
            val pagePadding =
                (16 * resources.displayMetrics.density * 2).toInt()
            val available =
                (windowWidth.coerceAtMost(1200) * density - pagePadding).toInt().coerceAtLeast(1)
            val heroWidth = (available * 0.46f).toInt().coerceAtLeast(1)
            // 高度必须 WRAP_CONTENT：FrameLayout 会把 MATCH_PARENT/WRAP 视作铺满，
            // 铺满会把按钮顶到卡片底缘、副标题挤成三行（真机截图暴露）
            heroPane.visibility = View.VISIBLE
            heroPane.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        heroWidth,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = android.view.Gravity.START or android.view.Gravity.TOP }
            // 右侧内容让出英雄卡宽度（含间距）：滚动条仍在屏幕右缘
            hero.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = gap }
            heroPane.layoutParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            scroll.setPaddingRelative(heroWidth + gap, scroll.paddingTop, 0, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        } else {
            heroPane.visibility = View.GONE
            if (hero.parent === heroPane) {
                heroPane.removeView(hero)
                row.addView(
                    hero,
                    0,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            (hero.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                topMargin = gap
                marginEnd = 0
            }
            scroll.setPaddingRelative(0, scroll.paddingTop, 0, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        }
    }

    override fun onPause() {
        cancelHold?.cancel()
        super.onPause()
        AppUiPresence.markPaused()
        mainHandler.removeCallbacks(heroTick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) cancelHold?.cancel()
    }

    override fun onDestroy() {
        cancelHold?.cancel()
        super.onDestroy()
        // 蓝牙扫描/配对监听随界面销毁收尾：Activity 退出后不再需要（也避免广播泄漏）
        runCatching { stopTargetScan() }
        targetPicker = null
        // 只解绑"挂屏"，不动会话——连接由前台服务保活、对象归 SessionController 持有；
        // 显式断开请用「断开连接」按钮（任意实例都能断到真管线）。
        if (SessionController.logListener === logListener) SessionController.logListener = null
        mainHandler.removeCallbacks(flushConsole)
        synchronized(consoleLock) {
            pendingConsole.clear(SessionController.logGeneration)
            consoleScheduled = false
        }
        if (SessionController.uiHintSink === uiHintSinkRef) SessionController.uiHintSink = null
        SessionController.dialogSink = null
        if (SessionController.ssidSink === ssidSink) SessionController.ssidSink = null
    }
}
