# 2026-11-13：记忆预连接、临时配置移除与默认网络切换

本轮以 `8e9fb2b` 为只读调查基线，继续检查午间 `be8a44c` 的网络生命周期，暂停 disableBluetooth 调查。没有修改业务代码、默认配置、手机设置或权限，没有安装、启动应用或新增路测。用户要求纯文档不跑单测/构建，本轮仅检查证据、脱敏、引用和 diff。

## 已定位到哪里

**记忆凭据的主动预连接直接参与了本次异常链路，但还不能证明它单独造成整机断网。** 午间先由记忆预连接发起 specifier 请求；随后已有 Wi-Fi 断开，收到新凭据再次进入“复用”；复用实现会注销旧主动请求。系统历史紧接着记录临时配置移除、断关联、自动重连保存配置。最后，应用观测到默认网络变成未验证的车机 Wi-Fi。

证据最强的实现问题是：**“观察并复用当前 Wi-Fi”与“撤销维持关联的主动请求”共用一个 callback 所有权槽，因此复用有拆除旧请求的副作用。** 这能解释临时配置移除链，但“CarLife 原蜂窝优先选择具体如何失效”和“系统为何在该时刻选中 Wi-Fi”仍缺故障现场策略字段，不能写成完整根因已经确认。

原始材料及版本边界见[对照分析](analysis.md)。新增[生命周期摘录](wifi-lifecycle.redacted.log)补充应用路径、系统请求状态、连接指标及事后策略快照；既有[系统历史](system-history.redacted.log)记录配置创建、移除和自动重连。文件保留原始行号、来源哈希和各段时间顺序，不含身份地址、凭据或完整负载。

## 为什么会进入这条流程

源码以下以函数名和提交定位，避免将当前行号混作约定的 `bcef70f` 行号。

1. `SessionController.startRememberedWifi` 在首次启动时并行运行。已有记忆、目标在场、尚未收到本轮新凭据且未推流，即可继续；在场条件可以是目标 ACL、iAP2 peer 或已关联热点，**不要求 CarLife 已连接**。
2. 它调用 `joinCarWifiSpecifier` / `joinCarWifiOnce`，先复用、再请求。午间 12:40:36.353 首次复用开始，36.359 返回 NULL，36.364 发出主动 specifier 请求。CarLife 控制连接到 40.247 才建立。因此请求确由预连接路径发起，而不是收到 0x5703 后才发起。
3. `WifiCarPlayConnector.joinCarPlayNetwork` 在首次 `onAvailable` 绑定成功后返回 Joined；上层 `WifiJoinGate.finish` 结束这一轮等待，但系统 request 仍保持注册。48.958 首次 available，49.005 本轮 specifier 等待结束，绑定 Network 834。
4. 系统 56.386 创建临时配置234，56.395发起断开，56.550本地主动断关联；connmetrics 在56.571记录 Network 834 断开。**首次 available 并不能证明后续不会再切换关联。** 现有材料没有系统请求处理的完整调用轨迹，不能断言为何配置创建比首次 available 晚约7.4秒，也不能确定该阶段所有回调的归属。
5. 58.779收到0x5703，进入 `onWifiCredentials`。其跳过重入条件是 CarPlay `videoSender` 已运行，或记忆存在且 AirPlay 控制连接已活跃；不是 CarLife 控制连接/首帧。午间尚未达到这两个条件，所以调用 `autoJoinCarWifi` 是当前逻辑的正常分支。
6. `joinCarWifiOnce` 发现缓存 Network 不再可用，58.799开始第二次复用。首轮调用已完成，门禁允许新一轮；58.841另一个调用进入 `WIFI_SHARED_WAIT`，确实共享这次工作。**不是两个并发调用同时创建两个 specifier 的证据。**
7. `adoptIfAlreadyConnected` 发现关联或 Wi-Fi 网络，进入 `bindCurrentWifiBlocking`。它在观察到并验证新 Network 之前，先执行 `callback.replace(..., ::disposeRequest)`。`OwnedSlot.replace` 先处置旧 owner；`disposeRequest` 注销旧 callback/request 并清理进程绑定。随后才注册被动 `registerNetworkCallback`，后者观察网络，不主动维持原 specifier 关联。
8. 58.851系统移除临时配置234，58.908以 `DISCONNECT_NETWORK_REMOVED` 断开；59.098系统将 `specificRequestInProgress` 清为false并恢复autojoin。12:41:00.269系统评分后自动连接保存配置193，01.807建立 Network 836，01.894应用核对SSID后复用它。

第7步的副作用由源码确定；第8步的物理行为由系统历史确定。两者时间和行为高度吻合，属于有实现依据的强关联，仍不冒充已有注销调用栈。系统日志将配置操作归属本应用UID，并不代表应用直接调用了 `WifiManager.removeNetwork`；AOSP 的 [WifiNetworkFactory](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiNetworkFactory.java) 会在请求释放后断开并移除 specifier 临时配置，使用其创建者身份，随后解除 specific-request 对自动连接的限制。

这里的 config 193/234 是 Wi-Fi 配置编号；Network 834/835/836 是 Connectivity 编号，不能混用。connmetrics 另记录59.108 Network 835断开，但不足以独立证明所有配置与Network的一一映射。

## 为什么可能丢掉蜂窝优先效果

事实有三层：

| 层次 | 已有证据 | 不能据此推出的结论 |
| --- | --- | --- |
| 用户反馈 | CarLife连接后提示继续用手机流量；未操作系统WLAN；CarPlay连接后整机断网 | 没有弹窗实现者、精确时刻及所写字段，不能认定调用了某个Android API |
| 故障时历史 | Network 834断开，836重新建立；836在12:41:12.119、23.295等时刻反复验证失败；25.345应用看到默认网络为未验证wlan0 | 25.345是观测时刻，不是精确的系统切换时刻；未取得切换瞬间蜂窝能力及双方完整评分策略 |
| 事后快照 | `Bad Wi-Fi avoidance=false`、`Actively prefer bad wifi=true`、`Avoid bad wifi setting=prompt`；只读查询 `network_avoid_bad_wifi=null` | 当前值不等于故障当时值；当前 Network overrides 为空不能证明旧 Network 从未有覆盖策略 |

AOSP [ConnectivityService](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/service/src/com/android/server/ConnectivityService.java) 的 `setAvoidUnvalidated(Network)` 把选择记录在指定 Network 的 `NetworkAgentInfo` 上，并非按SSID永久记住。因此，如果 CarLife 的提示最终采用这种每网络选择，834消失后新建836不会仅因同名热点而继承它。**这是有平台实现依据的条件推断，尚未证明OPPO/CarLife当时调用了此接口。**

AOSP [NetworkRanker](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/service/src/com/android/server/connectivity/NetworkRanker.java) 也存在让已评估但未验证的Wi-Fi优先的路径：需要主动优先坏Wi-Fi配置、没有该网络的avoid标记，以及其他网络相应的yield策略等条件。ConnectivityService对自动连接、允许此偏好的Wi-Fi设有20秒初始评估窗口，评估后重新匹配网络。836在01.807建立，21.943系统记录无互联网永久禁选，25.345应用观测默认切换，时序与“重新评估后选择变化”相容；不能仅凭约20秒就认定命中了该定时器。

以上AOSP main是2026-11-13查阅的机制参考，不是本机OPPO固件的逐行实现。源码中未发现本应用写蜂窝优先策略的调用；目前更有依据的解释是**我们的连接生命周期引发重新关联，可能使原来的按网络选择不再沿用**，不是已经查到主动覆盖CarLife设置的代码。

追加读取的logcat缓冲只覆盖13:05之后，缺少12:40—12:41；connmetrics的default-network历史也没有提供这次切换记录。834的 `NETWORK_FIRST_VALIDATION_SUCCESS, 0ms` 仅原样保留，不将其解释为车机热点真实可访问公网；836多次验证失败及用户反馈才是本轮互联网异常证据。

## 提交核对：哪些逻辑直接相关

| 提交/来源 | 与当前链路的关系 | 归因边界 |
| --- | --- | --- |
| `1035322` | 引入记忆凭据提前预连接；首次启动可在新凭据到达前主动请求Wi-Fi | 是午间首个请求的直接入口，不是证明每次预连接都断网 |
| `5bb7f7c` | 回滚重建后的当前网络实现：预连接等待目标在场、SSID观测、复用统一走有身份验证的callback | 在场不等于CarLife已连；午间第二次reuse记录 `associated=true`，不能归咎于新增“未知SSID也探测”的分支 |
| `fdbbd98` / `562b24f` | 先复用入口及其后的callback归属管理；当前复用会先处置旧主动请求 | 具体副作用在当前源码存在；不能仅凭提交名将全部问题归给某一提交 |
| `e33d954` | 用 `usableNetwork` 检查缓存绑定，网络失效时允许重新进入入网；引入 `WifiRequestState` | 检测旧Network失效是必要的；问题在后续怎样处置仍注册的请求，不建议撤掉有效性检查 |
| `5a9ccfc` | HFP/A2DP释放与拨号协调；没有改 `net/`，SessionController对应diff未改Wi-Fi控制流 | 可能改变相对时序，但未证明它导致本次路径差异 |

`073bd2a` 曾回滚一批功能，不能把历史提交依次简单相加；本轮以当前源码和 `5bb7f7c` 重建结果核对。`5bb7f7c`、`e33d954`均已在早间日志所报 `aa2579b` 之前；早间APK带dirty，未提交内容不可还原。`aa2579b..be8a44c` 的 `net/` 无diff。因此，“这些代码参与异常链”与“上午到中午的新提交引入回归”是不同判断。

早间06:48:15.052 CarLife控制已连接，15.227记忆预连接获准，15.305直接复用 Network 819；未走午间的首次specifier请求及后续拆除链。两轮都是CarLife先于CarPlay RECORD，但**Wi-Fi准备与CarLife连接的先后不同**，足以让同一套代码走不同分支。

另一个需保留的状态语义问题：`WifiRequestState.lost` 将当前Network丢失标为整个result永久结束，与 `unavailable` 共用终态。[NetworkCallback 文档](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback) 区分网络丢失和请求不可用/释放。当前上层另有丢网重建策略，所以不能只看到此终态就认定本次失败原因。午间56.645的 `onLost` 来自通用WifiLinkWatchdog，不是specifier owner回调的调用证明；本轮未见 `onCarWifiLost` 对应日志。若后续修改，需分别讨论“网络失效”“请求仍注册”“请求已释放”，不能把这个潜在边界混进本次已证实链路。

## 下一步建议：先验证预连接的影响

用户提出可以考虑取消Wi-Fi记忆路径，但明确这是想法，不是实施要求。本轮不删除或停用任何功能。

**优先建议独立实验：暂停收到本轮0x5703之前的主动记忆预连接，保留凭据记忆以及凭据到达后的正常连接。** 本次首个主动请求来自这条路径，有直接证据支持做此对照。仅取消这个入口不需要新增状态猜测、超时或兜底，也不改变HFP/A2DP协调、入站/出站接续和退避。

它不是已证实的完整修复：CarLife较晚连接时，0x5703之后仍可能需要主动请求，旧request被复用流程撤销的问题也可能从其他入口触发。因此，实验成功只支持收窄触发条件；永久取消或调整预连接应同时评估CarPlay先连接路径的功能影响。

实现层后续应审查的明确方向是：将主动 `requestNetwork` 的生命周期与被动观察/复用分开，避免“准备观察一个Network”本身先撤销维持关联的request；再以实际Network回调判断切换结果。不能把未知请求状态猜成成功，不能因复用失败而无条件保留已取消请求，也不能仅加固定等待掩盖生命周期问题。本轮只提出边界，不设计未经证据支持的恢复分支。

当前UI有“清除Wi-Fi记忆”，但它会清除凭据本身，**不等价于只关闭预连接**；本轮未操作，不建议把它当作隔离单变量实验的替代。现有实现未找到独立预连接开关。

验证应覆盖两条路径：CarLife先连接、CarPlay先准备连接。用户无需车上接ADB：后续若批准实验，出发前安装相应版本，车上正常使用，回来导出日志。重点检查首次入网入口、是否再出现中途onLost和重新Network、进程仍能访问车机且默认网络保持蜂窝，并结合用户对浏览器/地图联网及CarLife连续运行的反馈。现有日志足以检查主要现象；若要最终确认策略失效字段，仍需系统现场证据，不能要求用户为此保持车上ADB。

## 普通App权限约束

用户接受车机热点不承载互联网，但进一步明确：不要求用户ADB授权，不采用App原生无法取得的权限或手段，继续按原生命周期思路排查。**当前普通App权限下，不能直接强制整部手机其他应用全部走蜂窝。** `bindProcessToNetwork`只影响自身进程；请求蜂窝Network也不替其他应用改默认网络，不能用它们冒充整机修复。

已只读确认当前App没有 `NETWORK_SETTINGS` / `WRITE_SECURE_SETTINGS`，`SysShell`是本应用UID的普通ProcessBuilder，没有Shizuku权限。系统级避开未验证网络接口要求特权；全局网络策略写入也不是普通网络权限。此前探查的ADB写设置/授权方向不再作为产品方案或实验前置条件，本轮没有执行任何写入或授权。`mobile_data_always_on=0`也不直接解释本次故障：日志已经显示蜂窝曾处于可用默认网络，不能据此顺手开启另一项设置。

可执行方向是修正本应用造成的Wi-Fi生命周期扰动，让系统已有蜂窝选择有机会保持；**能避免扰动不等于能够保证厂商系统永远选蜂窝**。如生命周期调整后仍切换，必须如实保留平台能力边界，继续凭新证据排查，不能补上越权手段。
