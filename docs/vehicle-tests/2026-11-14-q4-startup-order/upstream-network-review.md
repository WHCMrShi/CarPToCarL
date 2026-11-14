# 2026-11-14：上游网络选择逻辑只读复核

本报告按用户后续要求检查上游文档、代码和相关问题记录，只涉及网络调查；与本轮蓝牙门禁修复分开。没有合并上游、修改网络业务逻辑、操作手机设置或安装上游APK。

## 来源与固定版本

通过GitHub连接器读取仓库元数据，`WHCMrShi/CarPToCarL`的 `parent` 和 `source` 均为 **`jjuugk/CarPToCarL`**；包名中的 `shaonialife` 不能用来推定上游仓库。该结论也与本分支既有[11月10日上游记录](../../upstream-reference/2026-11-10-main-8c6007a/README.md)一致。[仓库元数据](https://api.github.com/repos/WHCMrShi/CarPToCarL)

本次固定读取main提交 [`07119e28bec3bb08e00878653c3e2c1604fa81ec`](https://github.com/jjuugk/CarPToCarL/commit/07119e28bec3bb08e00878653c3e2c1604fa81ec)，提交时间2026-11-14 22:07:30（北京时间），README显示1.2.43。所有代码链接固定到该提交，避免main后续更新改变结论。核查了README、网络连接器、复用/就绪策略、保存网络解析、SessionController相关调用、网络历史提交，以及公开issue/PR列表和相关正文；没有逐一下载所有issue附件或验证其他车型实车结果。

## 找到了什么，能否用于本次Q4问题

**上游知道保存热点可能影响联网，但没有发现已实现、可直接移植的“让整机始终优先蜂窝”修复。** 当前代码仍优先利用系统保存热点自动连接，并在specifier失败后登记网络建议。这与本分支已移除suggestion、保留固定加密凭据的策略不同。

| 上游事实 | 对本次Q4问题的适用范围 |
| --- | --- |
| README提醒不要手动连接无互联网车机热点；同时使用说明保留系统自动关联/复用路径 | 表明上游承认普通保存热点影响联网的风险，不是已解决OPPO默认网络选择的证据 |
| `joinCarWifiOnce` / `joinSpecifierThenSuggestion`采用“复用 → specifier → 失败才登记suggestion” | 不是每次都会登记建议；直接照搬却会恢复本分支已去掉的系统自动关联入口，不能作为本次修复 |
| `noteSavedNetworkIfAny`只记录并保留保存热点，以便系统自动连接；不再提供旧的清除/保留询问 | 没有避免本次“App启动前系统已自动连保存热点”路径 |
| `CarNetworkReadiness`区分已就绪、已关联未绑定、无地址、错误网络和缺失；`NetworkReusePolicy`据此选择复用、等待或重连 | 有助于避免DHCP未完成时反复重发请求；其快照没有默认网络、蜂窝、INTERNET/VALIDATED等字段，不能判断或修复整机上网走向 |
| `currentUsableNetwork`和失网owner检查拒绝失效句柄及迟到回调 | 解决已丢失Network仍被当作可用的问题；本次889在前两轮持续存在，属于不同故障条件 |

代码出处：[SessionController入网及保存网络处理](https://github.com/jjuugk/CarPToCarL/blob/07119e28bec3bb08e00878653c3e2c1604fa81ec/app/src/main/java/io/github/shaonialife/carptocar/SessionController.kt#L2774)、[NetworkReusePolicy](https://github.com/jjuugk/CarPToCarL/blob/07119e28bec3bb08e00878653c3e2c1604fa81ec/app/src/main/java/io/github/shaonialife/carptocar/net/NetworkReusePolicy.kt)、[CarNetworkReadiness](https://github.com/jjuugk/CarPToCarL/blob/07119e28bec3bb08e00878653c3e2c1604fa81ec/app/src/main/java/io/github/shaonialife/carptocar/net/CarNetworkReadiness.kt)、[README联网提示](https://github.com/jjuugk/CarPToCarL/blob/07119e28bec3bb08e00878653c3e2c1604fa81ec/README.md#L451)。

`WifiCarPlayConnector.registerSuggestion`的注释明确区分系统自动连接与进程绑定，承认其他应用是否回落移动数据由系统决定。实现调用 `addNetworkSuggestions`，设置的两个interaction字段处理交互要求，不构成整机蜂窝优先策略。上游调用处仍有“其他应用继续走4G/5G”的无条件日志，与连接器注释的能力边界并不一致，不能将这句日志当API保证。[registerSuggestion实现及说明](https://github.com/jjuugk/CarPToCarL/blob/07119e28bec3bb08e00878653c3e2c1604fa81ec/app/src/main/java/io/github/shaonialife/carptocar/net/WifiCarPlayConnector.kt#L411)

## 相关历史改动与公开问题

- [`264e204`，11月8日](https://github.com/jjuugk/CarPToCarL/commit/264e20402230da92d478a29f00602010179e4733)：为减少人工确认，强化保存热点复用和建议自动关联，将旧的保存热点清除询问改为记录并保留。该提交当时曾将建议放在specifier前；当前main已经改回specifier优先，不能用旧提交描述替代现状。
- [`2b741946`，11月11日](https://github.com/jjuugk/CarPToCarL/commit/2b741946a6074061b3d5dcc97972b35eb1ff6aca)：引入上述五态网络快照和复用策略，重点是网络关联、地址和绑定是否就绪，避免把“还在等地址”误判为需要重建。
- [`c12d30e9`，11月14日](https://github.com/jjuugk/CarPToCarL/commit/c12d30e9b971a4534e9b00d69749ccbe5eff93a8)：增加实际进程绑定、Wi-Fi能力、地址及owner检查；失网按Network值和当前callback核对，旧失败只清理自己的owner。该大提交还包含会话、资源和重连改动，不能因为有几项网络保护就整包合入Q4。
- [issue #15](https://github.com/jjuugk/CarPToCarL/issues/15) / [PR #17](https://github.com/jjuugk/CarPToCarL/pull/17)：问题是取消/失网后缓存Network非空导致ENONET循环。维护者在issue评论中记录已通过本地提交 `7a2243a` 整合；GitHub PR本身是closed但 `merged=false`，不将其写成GitHub合并。现行上游及Q4已有可用性检查；它不能解释今天仍有效的889被选为默认热点。
- [issue #24](https://github.com/jjuugk/CarPToCarL/issues/24)涉及vivo/宝马已连蓝牙和热点但等待凭据、SSID未知；[issue #5](https://github.com/jjuugk/CarPToCarL/issues/5)标题提到一加/奥迪热点问题，正文只有日志附件。已检查的正文未给出CarLife手机网络提示被覆盖的确定API原因；未分析的附件不能被当成“肯定没有同类问题”。

另一个不能直接移植的边界：最新上游仍用单个 `callback` 槽。`watchLoss`进入时会注销原callback再注册观察者，没有Q4 `d350673`那样明确分开主动请求与观察生命周期。因此，不能用上游的新复用类覆盖本分支已针对周五证据修复的请求所有权实现。[watchLoss](https://github.com/jjuugk/CarPToCarL/blob/07119e28bec3bb08e00878653c3e2c1604fa81ec/app/src/main/java/io/github/shaonialife/carptocar/net/WifiCarPlayConnector.kt#L620)

## 文档与实现须分开判断

[上游连接分层文档](https://github.com/jjuugk/CarPToCarL/blob/07119e28bec3bb08e00878653c3e2c1604fa81ec/docs/carplay-connection-layers-20261109.md)与当前三层入网大体一致。[网络心跳文档](https://github.com/jjuugk/CarPToCarL/blob/07119e28bec3bb08e00878653c3e2c1604fa81ec/docs/wifi-link-watchdog.md)却仍将activeNetwork与进程绑定混述，并使用“没有WIFI日志即可排除Wi-Fi”的过强判断。Q4不应沿用这两点：默认网络与绑定必须分别观测，没有采样变化也不能证明无线层没有问题。

[Android的进程绑定说明](https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network))限定作用于当前进程，[Wi-Fi网络请求说明](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)不把本地设备网络请求作为互联网网络。这些公开能力不允许将“App访问车机正常”升级为“CarLife、地图和其他App均已走蜂窝”。本轮没有发现上游另有改变这一边界的实现。

## 对当前修复方案的影响

1. 今日前两轮的已确认路径保持不变：系统在App启动前自动连接保存配置并将其作为默认，App被动复用；系统外网探测失败。上游资料没有推翻这条证据，也未提供“App覆盖CarLife提示设置”的确定调用。
2. 网络部分保持只读，不引入上游suggestion、猜测性重连或新全局设置。可以借鉴“网络来源/关联/地址/绑定分别记录”的观测思路，但需独立评审，保留Q4身份校验和请求所有权。
3. 一次关闭目标保存热点自动连接、保留App加密凭据的可恢复对照仍是候选；先验证其是否避免启动前走系统保存热点，再确认两种启动顺序的蜂窝默认与实际联网。不是已执行或已证实的解决方案，也不要求每次手动开关Wi-Fi。
4. 当前可明确实施的蓝牙源就绪门禁修复仍独立进行。不得把上游网络审阅、该蓝牙修复的本机通过，写成第二轮整机联网问题已经修复。

本报告为只读调查结果，未运行上游构建、未进行上游APK/Q4实车验证；本仓库同期蓝牙修复的1055项JVM测试、lint及Debug构建通过，但不构成上游代码或网络修复的验证。文档链接与diff已复核。
