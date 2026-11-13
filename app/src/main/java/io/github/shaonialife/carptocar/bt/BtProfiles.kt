package io.github.shaonialife.carptocar.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 目标设备的音频配置文件释放；三个入口与出站拨号共用动作租约。
 *
 * Q4 2026-11-11 日志有 HFP/A2DP 在线、出站失败后入站成功的时序，
 * 但不能证明音频配置文件是唯一拒绝原因，更不能从它推断 iPhone 内部策略。
 * 证据与实现边界见 docs/q4-carplay-interaction-model.md 的 2026-11-13 记录。
 *
 * 沿用既有反射 disconnect；隐藏接口可能失败或被系统拒绝，权限不等于成功。
 * getProfileProxy/getConnectionState 查询缺失或抛错记 UNKNOWN；DISCONNECTED
 * 仅表示查询报告离线，不是空口确认。请求返回值和后续观测分别记录。
 */
object BtProfiles {
    private val operationScope = BtProfileOperationScope()
    private val actions = BtActionCoordinator()
    internal fun invalidatePendingOperations() {
        operationScope.invalidate()
        actions.cancelObsoleteAction()
    }
    internal fun cancelObsoleteAction() = actions.cancelObsoleteAction()
    internal fun acquireAction(operation: BtProfileOperation, source: String, log: (String) -> Unit) =
        actions.acquire(operation, source, log)
    internal fun captureOperation(current: () -> Boolean = { true }): BtProfileOperation =
        operationScope.capture(BtSessionGate.links, current)
    /** 守卫一直拿不到对端地址时，空转多久就收工（毫秒）。 */
    private const val NO_PEER_GIVE_UP_MILLIS = 60_000L

    /** 关心的配置文件（按断开优先级排列）。 */
    private val profiles: List<Int> =
        listOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.LE_AUDIO,
        )

    private fun profileName(profile: Int): String =
        when (profile) {
            BluetoothProfile.HEADSET -> "通话(HFP)"
            BluetoothProfile.A2DP -> "音频(A2DP)"
            BluetoothProfile.LE_AUDIO -> "低功耗音频(LE)"
            else -> "profile#$profile"
        }

    /** profile id → 已获取的代理（进程内复用；代理本身无会话状态）。 */
    private val proxies = HashMap<Int, ProfileProxyLease<BluetoothProfile>>()

    /** Missing proxies, permission failures and unsupported profiles are unknown, never offline. */
    internal fun states(
        context: Context,
        address: String,
    ): Map<String, BtProfileState> {
        val device = remoteDevice(context, address)
        return profiles.associate { id ->
            profileName(id) to observeBtProfileState {
                if (device == null || Thread.currentThread().isInterrupted) null
                else proxy(context, id)?.getConnectionState(device)
            }
        }
    }

    /**
     * 尽力断开该设备的全部配置文件连接。
     *
     * Requests and observed states are reported separately. All callers share the action lease.
     */
    internal fun disconnect(
        context: Context,
        device: BluetoothDevice,
        operation: BtProfileOperation,
        action: BtActionCoordinator.Lease? = null,
        source: String = "pair",
        log: (String) -> Unit = {},
    ): BtProfileReleaseResult<String> {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                val result = BtProfileReleaseResult(
                    after = profiles.associate { profileName(it) to BtProfileState.UNKNOWN },
                    superseded = !operation.isCurrent(),
                )
                log("BT-RELEASE: source=$source addressUnavailable=true ${result.describe()}")
                return result
            }
        return disconnectAddress(context, address, operation, device, action, source, log)
    }

    /** 同 [disconnect]，只给地址（守卫按地址轮询时用）。 */
    private fun disconnectAddress(
        context: Context,
        address: String,
        operation: BtProfileOperation,
        deviceHint: BluetoothDevice? = null,
        action: BtActionCoordinator.Lease? = null,
        source: String = "guard",
        log: (String) -> Unit = {},
    ): BtProfileReleaseResult<String> {
        val lease = action ?: acquireAction(operation, source, log)
            ?: return BtProfileReleaseResult(superseded = true)
        check(lease.operation === operation)
        try {
            lease.phase("RELEASING_PROFILES")
            val device = deviceHint ?: remoteDevice(context, address)
            val result = disconnectProfilesForOperation(
                operation,
                snapshot = { states(context, address) },
                prepareDisconnect = { name ->
                    val id = profiles.first { profileName(it) == name }
                    if (device == null) null else proxy(context, id)?.let { service ->
                        { disconnectProfile(service, device) }
                    }
                },
                awaitDisconnected = { targets, timeout, current ->
                    awaitDisconnected(context, address, targets, timeout, current)
                },
                disconnectDevice = {
                    // Coordinator excludes concurrent local dials; owner check still protects inbound RFCOMM.
                    submitBtDisconnect { BluetoothDevice::class.java.getMethod("disconnect").invoke(device) }
                },
            )
            log("BT-RELEASE: action=${lease.id} source=$source ${result.describe()} (ACCEPTED=系统接受请求，DISCONNECTED=查询报告离线)")
            return result
        } finally {
            if (action == null) lease.close()
        }
    }

    /**
     * 沿用既有建链期守卫的节奏和退出条件，不把周期性查询视为 Q4 拒绝原因的证明。
     * 每轮先等待共同动作租约，再重新查询；拨号或另一轮释放未结束时不并行清理。
     *
     * 跑在独立后台线程上；返回尝试配置释放的轮数（包括请求结果未知），不代表实际离线次数。
     */
    fun guardAudioProfiles(
        context: Context,
        addressProvider: () -> String?,
        shouldStop: () -> Boolean,
        onEvent: (String) -> Unit,
    ): Int {
        val started = SystemClock.uptimeMillis()
        var drops = 0
        var idlePolls = 0
        onEvent(
            "BT-GUARD: 开始盯音频配置文件（每 ${AudioProfileGuardPolicy.POLL_MILLIS}ms 一次，" +
                "上限 ${AudioProfileGuardPolicy.MAX_MILLIS / 1000}s，iAP2 会话建立即收工）",
        )
        while (!shouldStop() && !Thread.currentThread().isInterrupted) {
            val elapsed = SystemClock.uptimeMillis() - started
            if (!AudioProfileGuardPolicy.shouldKeepGuarding(elapsed)) {
                onEvent(
                    "BT-GUARD: 已盯满 ${AudioProfileGuardPolicy.MAX_MILLIS / 1000}s，" +
                        "停止（释放尝试 $drops 轮）——仍未建立 iAP2 会话",
                )
                return drops
            }
            val address = addressProvider()
            if (address == null) {
                // 还没有对端（没指定目标、也还没建链）：空转 60 秒就收工，别留一条
                // 白睡的线程跑到 5 分钟上限。
                idlePolls++
                if (idlePolls * AudioProfileGuardPolicy.POLL_MILLIS >= NO_PEER_GIVE_UP_MILLIS) {
                    onEvent("BT-GUARD: 一直没有对端可盯（未指定目标、也没建链）→ 收工")
                    return drops
                }
            } else {
                idlePolls = 0
                val operation = captureOperation {
                    !shouldStop() && address.equals(addressProvider(), true) &&
                        AudioProfileGuardPolicy.shouldKeepGuarding(SystemClock.uptimeMillis() - started)
                }
                if (operation.isCurrent()) {
                    val result = disconnectAddress(context, address, operation, log = onEvent)
                    if (result.requests.isNotEmpty()) drops++
                }
            }
            try {
                Thread.sleep(AudioProfileGuardPolicy.POLL_MILLIS)
            } catch (_: InterruptedException) {
                return drops
            }
        }
        return drops
    }

    private fun awaitDisconnected(
        context: Context,
        address: String,
        targets: Set<String>,
        timeoutMillis: Long,
        current: () -> Boolean,
    ): Map<String, BtProfileState> {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var observed = profiles.associate { profileName(it) to BtProfileState.UNKNOWN }
        while (current() && SystemClock.uptimeMillis() < deadline) {
            observed = states(context, address)
            if (!current() || targets.all { observed[it] == BtProfileState.DISCONNECTED }) return observed
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return observed
            }
        }
        return observed
    }

    @SuppressLint("MissingPermission")
    private fun remoteDevice(
        context: Context,
        address: String,
    ): BluetoothDevice? =
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?.getRemoteDevice(address)
        } catch (_: Throwable) {
            null
        }

    private fun proxy(
        context: Context,
        profile: Int,
    ): BluetoothProfile? {
        if (Thread.currentThread().isInterrupted || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null
        synchronized(proxies) { proxies[profile]?.get()?.let { return it } }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
        val latch = CountDownLatch(1)
        val lease = ProfileProxyLease<BluetoothProfile> { service ->
            runCatching { adapter.closeProfileProxy(profile, service) }
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(id: Int, service: BluetoothProfile) {
                lease.deliver(service)
                latch.countDown()
            }
            override fun onServiceDisconnected(id: Int) {
                synchronized(proxies) {
                    if (proxies[id] === lease) proxies.remove(id)
                    lease.retire()
                }
                latch.countDown()
            }
        }
        val requested = runCatching { adapter.getProfileProxy(context.applicationContext, listener, profile) }.getOrDefault(false)
        if (!requested || !awaitProfileReady { latch.await(1_200, TimeUnit.MILLISECONDS) }) {
            lease.retire()
            return null
        }
        synchronized(proxies) {
            if (Thread.currentThread().isInterrupted) { lease.retire(); return null }
            // Another caller may have completed first. Its listener owns its cached entry.
            proxies[profile]?.get()?.let { lease.retire(); return it }
            val ready = lease.get() ?: run { lease.retire(); return null }
            proxies[profile] = lease
            return ready
        }
    }

    /**
     * 断开某配置文件的连接。`disconnect(BluetoothDevice)` 是 @SystemApi（编译期
     * 不可见），反射调用；失败不抛，由调用方按最终状态判定。
     */
    private fun disconnectProfile(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
    ): BtDisconnectRequest =
        submitBtDisconnect {
            proxy.javaClass
                .getMethod("disconnect", BluetoothDevice::class.java)
                .invoke(proxy, device)
        }
}

/**
 * [BtProfiles.guardAudioProfiles] 的节奏与上限（纯逻辑，便于单测）。
 *
 * 轮询取 1.5 秒：车机把 HFP 拉回来到它真正听到「在线」之间通常只有几百毫秒，
 * 间隔再长就会漏掉；再短则每次都要走一遍 `getProfileProxy` 查询，白耗电。
 *
 * 上限 5 分钟：宝马 fork 实测的抖动窗口是 2~4 分钟，留 1 分钟余量。到点还在抖
 * 说明这条路走不通（车机根本不打算开 iAP2 窗口），继续断只会让用户看着蓝牙
 * 反复连断，不如停下来把控制权交回去。
 */
object AudioProfileGuardPolicy {
    const val POLL_MILLIS = 1_500L
    const val MAX_MILLIS = 300_000L

    /** 守卫是否还该继续盯：从开始计时起，未到上限就一直盯。 */
    fun shouldKeepGuarding(
        elapsedMillis: Long,
        maxMillis: Long = MAX_MILLIS,
    ): Boolean = elapsedMillis < maxMillis
}
