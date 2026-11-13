# 2026-11-13 Q4：午间断网与早间正常联网对照

## 结论与证据边界

用户反馈：午间 CarLife 连接后发送 `disableBluetooth` 没有导致 CarLife 终止，但手机互联网流量不走蜂窝；早上正常联网。用户补充确认：午间没有操作系统 WLAN，也没有手动选择保持连接。本次只分析和归档，不修改业务代码、默认配置或版本号。

直接可见的网络差异是：午间 **12:41:25.345**，应用查询到的默认网络从已验证的 `rmnet_data4` 蜂窝变为未验证的 `wlan0` 车机 Wi-Fi；早间保留的网络状态记录未出现这一变化。这与用户的断网反馈一致。日志尚不能解释系统选择 Wi-Fi 的原因，也没有切换时刻蜂窝本身的完整能力快照或其他应用的流量证据。

另一个必须单独记录的事实是：午间 **12:41:07.044**，车机对 `disableBluetooth` 返回 **422 Unprocessable Entity，accepted=false**。因此本轮支持“发送请求、收到拒绝后 CarLife 仍继续”，不能升级成“命令成功执行并断开蓝牙后 CarLife 仍继续”。422 的具体原因未确定，不据此新增参数猜测、重发或恢复策略。

## 材料、版本与启动顺序

| 样本 | 原文件 | 日志内源码标识 | 用户反馈 |
| --- | --- | --- | --- |
| 早间 | `CarPToCarL-log-20261113-072817.txt` | `aa2579b69a52-dirty` | 正常联网 |
| 午间 | `CarPToCarL-log-20261113-124359.txt` | `be8a44ca8ed1` | CarLife 继续，手机断网；未操作系统 WLAN |

原文件 SHA-256 分别为：

- 早间：`24cedd4f5fe58b2beb02a2b64bf5e2a04e20a720cdd1eca43cc2b6e477ca3558`
- 午间：`9c19cc0fa33a7f055fbbd9fbe9a0348a9007a3eb9e81a781ac61e41a8a8f59ef`

[早间摘录](morning.redacted.log)、[午间摘录](noon.redacted.log)仅保留本轮相关事件，去掉导出中完全重复的行，按时刻排序并标记原文件行号。移除身份地址、SSID 和协议负载；`[IP]` 不表示原地址彼此相等。原始文件不入库；不能用于逐字节重放。导出尾部附带的旧失败快照不能当作本轮失败：早间附有11月12日00:36结束的快照，午间附有当天11:41结束的快照。

两份日志均为 **CarLife 控制连接及首帧早于 CarPlay RECORD**：早间06:48:15.052建立CarLife控制连接、18.259首帧、42.416首次RECORD完成；午间12:40:40.247控制连接、42.781首帧、12:41:04.947首次RECORD完成。本次没有新增“CarPlay先连接、再启动CarLife”的验证样本；此路径仍未证明能在成功执行 `disableBluetooth` 后拉起CarLife。

早间APK带 `dirty`，不能仅凭提交号完整还原它。仓库内 `aa2579b69a52..be8a44c` 的 `net/` 源码无差异；这能排除该提交范围内直接修改网络连接器，不能排除早间未提交改动、蓝牙时序的间接影响或设备环境差异。本次也不是只切换 `disableBluetooth` 的严格A/B实验。

## 网络路径对照

| 事件 | 早间正常样本 | 午间断网样本 |
| --- | --- | --- |
| 初始默认网络 | 06:48:15.023：蜂窝，INTERNET=true、VALIDATED=true | 12:40:25.177：蜂窝，INTERNET=true、VALIDATED=true |
| 本包旧 suggestion | 15.286：removed=0、remaining=0、ready=true | 36.362：removed=0、remaining=0、ready=true |
| 首次获得目标 Wi-Fi | 15.305：按SSID核对并复用已有Network 819；15.309 reuse成功 | 48.958：specifier可用并绑定Network 834 |
| 中途丢失/重新关联 | 保留的网络记录中未出现onLost | 12:40:56.645 onLost、进程解绑；12:41:01.826重新onAvailable |
| 最终采用的绑定路径 | 复用当前Wi-Fi | 12:41:01.894按SSID核对并复用Network 836；01.901 reuse成功 |
| 绑定后的默认网络 | 06:48:20.045：进程绑定wlan0，但默认仍为已验证蜂窝 | 12:41:04.744：进程绑定wlan0，但默认仍为已验证蜂窝 |
| 随后的关键变化 | 导出中未发现默认网络切到未验证Wi-Fi的记录 | 12:41:25.345：默认变为wlan0，INTERNET=true、VALIDATED=false，进程仍绑定wlan0 |

`WifiLinkWatchdog.currentSignature` 分别读取 `ConnectivityManager.activeNetwork` 和 `boundNetworkForProcess`。两者不是同一项：日志里“程序已绑Wi-Fi，但默认仍蜂窝”的阶段正是直接反例。Android文档说明 [getActiveNetwork](https://developer.android.com/reference/android/net/ConnectivityManager#getActiveNetwork()) 返回当前默认数据网络；[bindProcessToNetwork](https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network)) 约束调用进程后续未单独绑定的socket及域名解析，不能把它解释为替其他应用设置默认路由。

`defaultInternet=true` 是能力声明，不等于实际可上网；[NET_CAPABILITY_VALIDATED](https://developer.android.com/reference/android/net/NetworkCapabilities#NET_CAPABILITY_VALIDATED) 才表示系统成功验证连通性。反过来，未验证也不能单独证明所有目的地址都不可达；本轮手机断网来自用户反馈与网络切换记录相互印证。

时间戳是观察到变化的时间，不是系统内部作出选择的精确时刻。当前看门狗主要记录Wi-Fi回调和状态变化，不包含系统的网络评分/选择理由、所有蜂窝能力变化和其他应用的UID路由，不能据此断言蜂窝被关闭或已经不可用。

## disableBluetooth 与本地释放的结果

- 12:40:41.412：本地guard的HFP/A2DP释放请求均为 `ACCEPTED`，复查均为 `DISCONNECTING`，原操作已被取代（`superseded=true`）；没有把它记为断开完成。
- 12:40:44.127：后续guard查询HFP/A2DP均为 `DISCONNECTED`；LE为 `UNKNOWN`，没有据此发设备级disconnect。该观测发生在 `disableBluetooth` 之前。
- 12:40:52.723：guard动作7结束；52.725出站动作8开始，54.365拨号成功；期间guard记为等待，55.876才开始动作9。这个片段支持释放与拨号协调生效，不等于覆盖全部并发场景或空口事件。
- 12:41:07.031：发送一次 `disableBluetooth`；07.044收到422。不能把之前本地释放的结果归功于这条命令，也不能以422猜测车机内部完全没有副作用。
- 12:41:25.345：默认网络变化，晚于拒绝响应18.301秒；仅有先后关系不足以证明因果。
- 12:42:21.867：CarLife源累计2933帧、最新帧年龄18ms；12:42:23.017仍有音频直通，26.056仍有视频帧率记录。26.677才出现 `final-disconnect requested`，26.682是 `local-stop-or-cancel`。支持CarLife在请求及默认网络变化后仍持续工作，不能把本地送帧量当作车机稳定解码确认。

## 可以排除的简单归因与仍缺失的证据

1. **“只要复用当前Wi-Fi就会断网”不成立。** 早间也用相同复用路径且用户报告正常联网。午间先specifier、丢失、再复用另一Network是可见差异，尚不是根因证明。
2. **“本包旧suggestion没清掉”没有本次证据。** 两次均为零；这不检查用户保存网络或其他包的配置。不能以此推断存在其他包干预。
3. **“新disableBluetooth成功断蓝牙导致断网”没有证据。** 本轮响应为422。[11月10日旧证据](../2026-11-11-rollback-non-audio/handover.md)已在23:06:26、23:07:21记录未验证wlan默认网络，而当时尚未实现该命令；此类现象并不依赖本次新命令。
4. **“午间用户主动选择Wi-Fi为默认”不符合本次反馈。** 用户明确没有操作系统WLAN。保存网络策略、系统自动选择和蜂窝当时状态仍需系统证据，不能从“无人操作”进一步推定其中某项已发生。

## 下一步建议（未实施）

优先补齐**默认网络从蜂窝切到Wi-Fi时系统为何这样选择**，再决定业务改动。下一次可复现现场，在连接前、连接后仍能联网时、断网当下分别采集只读 `adb shell dumpsys connectivity`、`adb shell dumpsys wifi`，并用带时间戳的系统logcat覆盖整个切换过程。材料只存本机，筛选脱敏后归档；需要核对默认Network、蜂窝是否仍在且validated、Wi-Fi验证状态和系统实际留下的选择原因。不要用离车后的正常快照替代故障现场。

同时记录浏览器和CarLife联网是否都失败、准确时刻，以及启动先后。现有应用日志缺少蜂窝在切换时的完整状态；如再做诊断代码，范围应只补默认网络/蜂窝能力变化、网络ID及进程绑定的关联记录，不先添加自动重连、强行解绑、强绑蜂窝或未知状态兜底。

`disableBluetooth` 的422单独调查：对照本次命令结构、身份来源和协议要求，必要时采集接受命令的对照；本轮不自动改地址、发多个变体或重试。只有得到接受响应及实际profile/ACL变化证据，才能验证“执行断开后CarLife持续”的问题。

目前不建议仅凭本次两份日志删除当前Wi-Fi复用路径、扩大suggestion清理范围，或把整个CarPToCarL进程改绑蜂窝。这些动作都没有定位到系统选择原因，且现有早间正常样本反对“复用或进程绑定本身必然失败”的假设。

## 本机验证

本次仅新增脱敏证据和分析、同步交互模型。`build-local.ps1 -NoDaemon` 通过：174个套件、1003项JVM单测，0 failures/errors/skipped；lint 0 errors、105 warnings、2 information；Debug APK构建成功。另核对摘录去重、时间顺序、来源行号与脱敏，`git diff --check`通过。不安装新APK，不声称进行了新的Q4实车测试。
