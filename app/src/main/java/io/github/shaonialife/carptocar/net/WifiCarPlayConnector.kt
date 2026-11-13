package io.github.shaonialife.carptocar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import io.github.shaonialife.carptocar.util.Bg
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 用车机热点承载 CarPlay 流量。
 *
 * **两种入网方式，默认应用专属网络**：
 *  - [joinCarPlayNetwork]：`WifiNetworkSpecifier` 请求「只对本进程可见」的 WiFi。
 *    请求不声明互联网能力；其它应用的实际路由仍取决于系统与 ROM，需实车核对。
 *  - [bindCurrentWifi]：复用已连接 WiFi；调用方提供目标时必须验证其身份。
 *  - 入网前清理本应用旧 suggestion 并核验结果，不再注册新的 suggestion。
 *
 * 关键点：
 *  - 请求不带 `NET_CAPABILITY_INTERNET`，系统不会因「无互联网」拆连接；
 *  - 应用专属网络必须 `bindProcessToNetwork` 后再建 socket / 注册 mDNS；
 *  - 首次连接会弹系统确认框；SSID 必须精确匹配（热点名可能带空格/中文）。
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** 应用专属网络的连接结果。 */
    sealed interface JoinResult {
        /** 已连接并绑定到本进程；[network] 可用于 NSD/端点判断。 */
        data class Joined(
            val network: Network,
        ) : JoinResult

        /** 系统未允许（用户拒绝/热点不在范围/超时）。可重试。 */
        data object Unavailable : JoinResult

        /** 该安全类型无法用 WifiNetworkSpecifier 表达（WEP）——调用方走旧路径。 */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Used only to inspect association and migrate this package's old suggestions. */
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(WifiManager::class.java)

    private val networks = WifiNetworkLifecycle<ConnectivityManager.NetworkCallback, Network>(
        unregister = { owner -> runCatching { connectivityManager.unregisterNetworkCallback(owner) }; Unit },
        bindProcess = { connectivityManager.bindProcessToNetwork(it) },
    )

    private val identities = LiveWifiIdentity<Network>()
    private var identityCallback: ConnectivityManager.NetworkCallback? = null

    @Synchronized fun startIdentityObservation() {
        if (identityCallback != null) return
        val epoch = identities.restart()
        val observer = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                identities.update(epoch, network, if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                    plainSsid((caps.transportInfo as? WifiInfo)?.ssid) else null)
            }
            override fun onLost(network: Network) { identities.lost(epoch, network) }
        }
        identityCallback = observer
        try {
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), observer)
        } catch (error: Exception) {
            stopIdentityObservation()
            log("WIFI-ID: observation unavailable type=${error.javaClass.simpleName}")
        }
    }

    @Synchronized fun stopIdentityObservation() {
        identities.restart() // Invalidate before unregister: already queued callbacks can still arrive.
        val old = identityCallback
        identityCallback = null
        if (old != null) runCatching { connectivityManager.unregisterNetworkCallback(old) }
    }

    val currentNetwork: Network? get() = networks.network()

    /** Read only. Request registration, network availability and process binding are distinct. */
    fun usableNetwork(): Network? = networks.usable(::isUsable)

    private fun isUsable(network: Network): Boolean =
        runCatching {
            connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                connectivityManager.boundNetworkForProcess == network
        }.getOrDefault(false)

    /** Physical binding snapshot, deliberately independent of cancellation policy. */
    fun diagnosticBinding(): String {
        val network = networks.network()
        val live = network != null && runCatching { connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }.getOrDefault(false)
        val bound = network != null && runCatching { connectivityManager.boundNetworkForProcess == network }.getOrDefault(false)
        return "cached=${network != null},request=${networks.requestOwner() != null},observer=${networks.observerOwner() != null},wifi=$live,processBound=$bound"
    }
    private val identityProof = WifiIdentityProof({ SystemClock.elapsedRealtime() })

    fun recordReceiverInvite(target: String, receiver: String?, local: String?) {
        if (identityProof.record(target, receiver, local)) {
            log("WIFI: target receiver invitation recorded with exact local interface; ttlMs=30000")
            (networks.observerOwner() as? WifiObserver)?.retry()
        }
    }

    fun clearReceiverEvidence() { identityProof.clear() }

    /**
     * 应用专属网络被系统断开（热点关闭/离开范围）时回调。会话层据此提示用户；
     * 空实现 = 不关心（例如手动绑定路径）。
     */
    @Volatile
    var onNetworkLost: ((ssid: String, stillCurrent: () -> Boolean) -> Unit)? = null

    /**
     * 当前绑定网络的**全部**默认网关（车机热点场景 = 车机 IP），**IPv4 排在最前**。
     *
     * mDNS 发现失败时的兜底路径。
     *
     * 为什么不能只取一条（2026-11-04 奥迪 4910 / MIB3 4580 实车）：
     * 原来写的是 `routes.firstOrNull { isDefaultRoute }`，而这台车机的默认路由里
     * 排第一的是 **IPv6 链路本地** `fe80::1086:3aff:fe8d:9239`，于是 mDNS 未发现时
     * 连续 8 次重连全打在这个地址上（每次 2s 超时，`failed to connect … after 2000ms`）；
     * 而同一份日志里 mDNS 解析出的 `10.174.189.1:7000` 才是通的——唯一的实证路径
     * 就这么被丢掉了。热点场景网关就是车机本身，把 IPv4 那条一起收进来几乎零成本。
     *
     * IPv4 优先的理由同 [EndpointHosts]：安卓上最稳，也是真车唯一验证过的路径。
     */
    fun gatewayAddresses(): List<String> {
        val network = networks.network() ?: return emptyList()
        val gateways =
            try {
                connectivityManager
                    .getLinkProperties(network)
                    ?.routes
                    .orEmpty()
                    .filter { it.isDefaultRoute }
                    .mapNotNull { it.gateway?.hostAddress?.substringBefore('%') }
                    .distinct()
            } catch (_: Exception) {
                return emptyList()
            }
        return gateways.filter { it.contains('.') } + gateways.filter { !it.contains('.') }
    }

    /** [gatewayAddresses] 的首选（IPv4 优先）。 */
    fun gatewayAddress(): String? = gatewayAddresses().firstOrNull()

    /**
     * 本机在当前绑定网络上的 IPv4 地址（如 10.174.189.57）。
     * 用于 mDNS 结果过滤：手机双归属（连车机热点 + 自己开热点共享给电脑）时，
     * mDNS 会跨网发现电脑的 AirPlay 接收器——非本网段地址一律不是车机。
     */
    fun localIpv4Address(): String? {
        val network = networks.network() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.firstOrNull { it.contains('.') && !it.contains(':') }
                ?.substringBefore('/')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 本机在当前绑定网络上的全部 IPv6 地址。
     *
     * mDNS 结果的网段验收用：奥迪 4xxx 的 `_airplay._tcp` 常只发链路本地 IPv6，
     * 旧过滤只按 IPv4 前缀判定，会把车机本身也当成「非本网段」丢掉（2026-10-19）。
     */
    fun localIpv6Addresses(): List<String> {
        val network = networks.network() ?: return emptyList()
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.filter { it.contains(':') }
                ?.map { it.substringBefore('%').substringBefore('/') }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 用应用专属网络（WifiNetworkSpecifier）加入车机热点。
     *
     * 阻塞直到连接成功/系统拒绝/超时（内部 200ms 分片，[isCancelled] 可即时退出）。
     * 成功后进程网络已绑定，调用方可直接注册 mDNS / 建 socket。
     *
     * 安全类型映射按 0x5703（xcertplay Iap2WirelessSecurity）：
     * 0=NONE 1=WEP 2=WPA/WPA2 3=WPA3 过渡 4=WPA3 仅。
     * WEP 无对应 API（返回 [JoinResult.Unsupported]，调用方走旧路径）。
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
        graceMillis: Long = 0L,
        isCancelled: () -> Boolean = { false },
    ): JoinResult {
        if (!prepareLocalNetwork(isCancelled)) return JoinResult.Unavailable
        val specifier =
            buildSpecifier(ssid, passphrase, securityType)
                ?: return JoinResult.Unsupported
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
        val latch = CountDownLatch(1)
        val result = WifiRequestState<Network>()
        val unavailable = AtomicBoolean(false)
        if (!ensureWifiObserver(isCancelled)) return JoinResult.Unavailable
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networks.withRequest(this) {
                        if (isCancelled()) return@withRequest
                        val bound = result.available(network) { networks.bindRequest(this, network) { !isCancelled() } }
                        log("CONNECT-DIAG: specifier available request=${System.identityHashCode(this)} bound=$bound")
                        log("WiFi: 系统请求网络已可用（$network bindProcessToNetwork=$bound）；其他应用路由由系统决定")
                        latch.countDown()
                    }
                }

                override fun onUnavailable() {
                    var lost: (() -> Boolean)? = null
                    networks.withRequest(this) {
                        unavailable.set(true)
                        result.unavailable()
                        lost = networks.releaseRequest(this)
                        log("CONNECT-DIAG: specifier unavailable request=${System.identityHashCode(this)} previouslyBound=${lost != null}")
                        log("WiFi: 系统专属网络请求已失效（可能为拒绝、撤销或热点不可用，不推定用户操作）")
                        latch.countDown()
                    }
                    lost?.let { onNetworkLost?.invoke(ssid, it) }
                }

                override fun onLost(network: Network) {
                    var lost: (() -> Boolean)? = null
                    networks.withRequest(this) {
                        if (result.lost(network)) lost = networks.requestLost(this, network)
                        log("CONNECT-DIAG: specifier network lost request=${System.identityHashCode(this)} network=$network; request remains registered")
                    }
                    lost?.let { onNetworkLost?.invoke(ssid, it) }
                }
            }
        val accepted =
            try {
                networks.startRequest(newCallback, { !isCancelled() }) {
                    log("CONNECT-DIAG: requesting system WiFi selection request=${System.identityHashCode(newCallback)}")
                    connectivityManager.requestNetwork(request, newCallback)
                }
            } catch (error: Exception) {
                log("WiFi: 发起应用专属网络请求失败（${error.message}）——可能需要位置权限或系统不支持")
                false
            }
        if (!accepted) return JoinResult.Unavailable
        val startedAt = SystemClock.uptimeMillis()
        val window = WifiJoinWindow(startedAt, timeoutMillis, graceMillis)
        var graceLogged = false
        while (networks.requestOwner() === newCallback && window.waiting(SystemClock.uptimeMillis(), unavailable.get(), isCancelled())) {
            if (!graceLogged && window.inGrace(SystemClock.uptimeMillis())) {
                graceLogged = true
                log("WIFI: specifier pending; extending current owned request by ${graceMillis}ms")
            }
            if (isCancelled()) {
                networks.releaseRequest(newCallback)
                return JoinResult.Unavailable
            }
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                networks.releaseRequest(newCallback)
                return JoinResult.Unavailable
            }
        }
        val network = networks.requestResult(newCallback, { !isCancelled() }, ::isUsable)
        if (network == null) {
            networks.releaseRequest(newCallback)
            log("CONNECT-DIAG: specifier incomplete request=${System.identityHashCode(newCallback)} elapsedMs=${SystemClock.uptimeMillis() - startedAt} reason=${if (unavailable.get()) "system-unavailable" else "deadline"}")
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
    }

    /** 构造 specifier；WEP 或参数不合法返回 null（走旧路径）。 */
    private fun buildSpecifier(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): WifiNetworkSpecifier? {
        return try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (securityType) {
                // 开放热点：无密码
                0 -> Unit
                // WEP：WifiNetworkSpecifier 无对应 API（现代车机不再使用）
                1 -> return null
                // WPA/WPA2 与 WPA3 过渡模式：过渡模式 AP 同时接受 WPA2-PSK
                2, 3 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
                // WPA3 仅
                4 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa3Passphrase(passphrase)
                }
                // 未知类型：按有密码的 WPA2 尝试（比直接放弃更可能成功）
                else -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
            }
            builder.build()
        } catch (error: Exception) {
            log("WiFi: 构造应用专属网络参数失败（${error.message}）")
            null
        }
    }

    /** Observe and bind an already connected target; this does not cancel an active request. */
    fun bindCurrentWifi(ssid: String, onResult: (Boolean) -> Unit = {}) {
        Bg.run("wifi-bind") { onResult(bindCurrentWifiBlocking(15_000L, ssid) != null) }
    }
    private val suggestionMigration = OwnedSuggestionMigration()

    private fun prepareLocalNetwork(isCancelled: () -> Boolean): Boolean {
        if (isCancelled()) return false
        return suggestionMigration.ensure {
            try {
                val manager = wifiManager ?: return@ensure false
                val old = manager.networkSuggestions
                val status = if (old.isEmpty()) WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
                    else manager.removeNetworkSuggestions(old)
                val remaining = manager.networkSuggestions.size
                val success = status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS && remaining == 0
                log("WIFI-POLICY: ownedSuggestionsRemoved=${old.size} status=$status remaining=$remaining ready=$success")
                success
            } catch (error: Exception) {
                log("WIFI-POLICY: migration failed type=${error.javaClass.simpleName}; local join deferred")
                false
            }
        } && !isCancelled()
    }

    private class ReuseProbe(val expectedSsid: String?, val valid: () -> Boolean) {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val lease = WifiNetworkLease<Network>()
    }

    /** One connection-scoped observer monitors bindings even after a reuse waiter finishes. */
    private inner class WifiObserver : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        private val capabilities = mutableMapOf<Network, NetworkCapabilities>()
        private val links = mutableMapOf<Network, LinkProperties>()

        override fun onAvailable(network: Network) {
            networks.withObserver(this) {
                if (networks.probeOwner() != null) log("WIFI: fallback candidate=$network awaiting ordered capabilities and link properties")
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networks.withObserver(this) { capabilities[network] = caps; tryBind(network) }
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            networks.withObserver(this) { links[network] = properties; tryBind(network) }
        }

        fun retry() {
            networks.withObserver(this) { capabilities.keys.toList().forEach(::tryBind) }
        }

        private fun tryBind(network: Network) {
            val probe = networks.probeOwner() as? ReuseProbe ?: return
            if (!probe.valid() || probe.result.get() != null) return
            val caps = capabilities[network] ?: return
            val properties = links[network] ?: return
            val observedSsid = plainSsid((caps.transportInfo as? WifiInfo)?.ssid)
            val target = io.github.shaonialife.carptocar.bt.TargetDevicePrefs.get(context)?.address
            if (!identityProof.accepts(probe.expectedSsid, observedSsid, target, properties.linkAddresses.mapNotNull { it.address.hostAddress })) {
                log("WIFI: fallback not authorized network=$network identity=${if (observedSsid == null) "unknown-no-matching-proof" else "ssid-mismatch"}")
                return
            }
            if (!probe.lease.accept(network)) {
                log("WIFI: fallback ignored replacement network=$network; requires a new vehicle request")
                return
            }
            val bound = networks.bindProbe(probe, network, probe.valid)
            if (bound) probe.result.set(network)
            log("WIFI: fallback selected network=$network identity=${if (observedSsid != null) "ssid-visible" else "target-invite-and-exact-interface"} bound=$bound; lease pinned; activeRequestRetained=${networks.requestOwner() != null}")
            log("WiFi: 目标身份已核对——绑定当前 WiFi（$network bindProcessToNetwork=$bound）")
            probe.latch.countDown()
        }

        override fun onLost(network: Network) {
            var lost: (() -> Boolean)? = null
            networks.withObserver(this) {
                capabilities.remove(network)
                links.remove(network)
                (networks.probeOwner() as? ReuseProbe)?.let {
                    it.lease.lost(network)
                    it.result.compareAndSet(network, null)
                }
                lost = networks.observerLost(this, network)
                log("CONNECT-DIAG: observer network lost network=$network bindingLost=${lost != null} activeRequestRetained=${networks.requestOwner() != null}")
            }
            lost?.let { onNetworkLost?.invoke("", it) }
        }
    }

    private fun ensureWifiObserver(isCancelled: () -> Boolean): Boolean {
        val observer = WifiObserver()
        return try {
            networks.ensureObserver(observer, { !isCancelled() }) {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, observer)
            }
        } catch (error: Exception) {
            log("WiFi: 注册网络观察失败（${error.message}）")
            false
        }
    }

    /** A bounded reuse probe owns no association request and cannot release one on exit. */
    fun bindCurrentWifiBlocking(
        timeoutMillis: Long,
        expectedSsid: String? = null,
        isCancelled: () -> Boolean = { false },
    ): Network? {
        if (!prepareLocalNetwork(isCancelled) || !ensureWifiObserver(isCancelled)) return null
        val probe = ReuseProbe(expectedSsid) { !isCancelled() }
        if (!networks.startProbe(probe, probe.valid)) return null
        try {
            (networks.observerOwner() as? WifiObserver)?.retry()
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (networks.probeOwner() === probe && SystemClock.uptimeMillis() < deadline) {
                if (isCancelled()) return null
                try {
                    if (probe.latch.await(200, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return networks.probeResult(probe, probe.valid)
        } finally {
            networks.endProbe(probe)
        }
    }

    /**
     * 系统当前是不是已经连在 [ssid] 这条热点上。
     *
     * 三种来源都算：用户手动在系统 WLAN 里连过、历史建议 登记的建议网络被
     * 系统自动关联、上一轮的应用专属网络还没被系统拆掉。
     *
     * 读不到 SSID 时一律返回 false——Android 11+ 没给定位/附近设备权限时
     * `connectionInfo.ssid` 是 `<unknown ssid>`，这时**不能**猜，猜错会把进程网络绑到
     * 家里的 WiFi 上（AirPlay 必然连不上，而且其它应用瞬间断网）。
     */
    fun isAssociatedTo(ssid: String): Boolean {
        if (runCatching { identities.matches(ssid, connectivityManager.allNetworks.toList()) }.getOrDefault(false)) return true
        // 主路径：ConnectivityManager 逐条网络的 transportInfo。
        //
        // `wifiManager.connectionInfo` 单独用会漏：Android 12+ 上它对**应用专属网络**
        // （WifiNetworkSpecifier 建出来的那条）常常报 `<unknown ssid>` 或 netId=-1，
        // 于是「手机其实还挂在车机热点上」被误判成没连——白白再发一次 specifier、
        // 再等 15 秒系统确认框（2026-11-04 奥迪 4910 第二段实录：16:22:37 判成没连，
        // 16:22:52 超时 15s，之后才靠建议网络兜回来）。
        val viaConnectivity =
            runCatching {
                connectivityManager.allNetworks.any { network ->
                    val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@any false
                    val info = caps.transportInfo as? WifiInfo ?: return@any false
                    plainSsid(info.ssid) == ssid
                }
            }.getOrDefault(false)
        if (viaConnectivity) return true

        // 备用：老路径。部分机型 transportInfo 不填 SSID，但 connectionInfo 有。
        val manager = wifiManager ?: return false
        val info =
            @Suppress("DEPRECATION")
            runCatching { manager.connectionInfo }.getOrNull() ?: return false
        @Suppress("DEPRECATION")
        if (info.networkId < 0) return false
        return plainSsid(info.ssid) == ssid
    }

    /** 去掉系统给的双引号，把「读不到」的占位值统一成 null。 */
    private fun plainSsid(raw: String?): String? {
        val plain = raw?.removePrefix("\"")?.removeSuffix("\"") ?: return null
        return if (plain.isEmpty() || plain == UNKNOWN_SSID) null else plain
    }

    /**
     * 目标热点已经在系统里连着时，**直接把它绑到本进程**——不发 specifier、不弹确认框。
     *
     * 为什么优先于 [joinCarPlayNetwork]：specifier 每发一次，系统就要用户在一个确认框上
     * 点「连接」；车上没人点屏，等到超时（我们 15s / 车机更早）就整轮失败，表现成
     * 「重连时卡在加入热点」。而断线重连的绝大多数场景里，手机本来就还在车机热点上——
     * 系统这张关联表还在，缺的只是 `bindProcessToNetwork`。
     *
     * 不做校验就绑会绑错网，所以先过 [isAssociatedTo]。
     *
     * @return 绑定成功返回网络；未连在该 SSID 上 / 等待超时返回 null（调用方走 specifier）
     */
    fun adoptIfAlreadyConnected(
        ssid: String,
        timeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
    ): Network? {
        if (isCancelled()) return null
        val associated = isAssociatedTo(ssid)
        val hasWifi = associated || runCatching { connectivityManager.allNetworks.any {
            connectivityManager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } }.getOrDefault(false)
        if (!hasWifi) return null
        // Use the same ordered, location-aware callback that actually authorizes the binding.
        // A redacted synchronous SSID does not force an immediate specifier request.
        val budget = if (associated) timeoutMillis else minOf(timeoutMillis, 1_200L)
        val reused = bindCurrentWifiBlocking(budget, ssid, isCancelled)
        log("WIFI-ID: reuse associated=$associated hasWifi=$hasWifi success=" + (reused != null))
        return reused
    }

    fun release() { networks.release() }

    private companion object {
        /** 没权限读 SSID 时系统给的占位值。 */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
