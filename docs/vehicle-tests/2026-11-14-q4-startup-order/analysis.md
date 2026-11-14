# 2026-11-14：CarLife 晚启动与既有默认热点排查

本轮只调查下午前两轮连接。用户确认第三轮正常使用，最后主动离车，末尾通道中断不作为异常分析。用户要求查明原因并提出修复方案；本记录不代表业务修复已经实施。

## 材料、版本和事实边界

- 应用日志：`CarPToCarL-log-20261114-140612.txt`，SHA-256 `D3A185F0158136D6FF3C6DEE7C0F9B7D2E603399AD8B82A2B3D9645C32657B16`。导出时间为2026-11-14 14:06:12，源码标识为 `a981fca7e52b-dirty`，版本 `1.2.31-q4-debug`。早期、最近诊断与主日志存在重复，已按事件合并；末尾11月13日旧失败快照不计入今日轮次。
- 源码审查基线：`0957334`。该提交相对 `a981fca` 没有修改本次相关的 `SessionController`、`AirPlayClient` 蓝牙命令门禁和 `net/` 网络控制流；APK的未提交内容仍不能仅凭标识完全还原。下文以提交及函数定位，不将当前行号混作约定的 `bcef70f` 行号。
- USB只读补证：系统 Wi-Fi、Connectivity、NetworkStack 和包信息。历史时间戳用于对应故障；当前策略快照仅表示取证时状态。原始日志、身份地址、SSID和完整协议负载不入库，本记录仅保留必要的时间、消息类型及状态摘要。系统Wi-Fi配置编号193与Connectivity网络编号889属于不同编号空间。
- 按时间整理的必要事件见[脱敏摘录](events.redacted.log)，可按来源哈希及行号回查；摘录不是逐字节协议重放依据。
- 用户反馈：第一轮尝试手动启动CarLife，但无法重新连到车机蓝牙；第二轮手机默认使用车机热点，联网异常。CarLife提示使用手机网络是自动说明，没有可供用户选择的按钮，发生在CarPlay连接完成之前。用户随后手动关闭、开启Wi-Fi，因为否则会一直停留在车机热点默认网络。

## 第一轮：视频源未就绪便发送断蓝牙命令

| 时间 | 日志事实 |
| --- | --- |
| 13:53:03 | 启动连接，等待本机CarLife引擎，同时准备蓝牙和Wi-Fi |
| 13:53:07 | 手机主动蓝牙RFCOMM连接建立 |
| 13:53:11 | AirPlay配对、SETUP和首次RECORD完成 |
| 13:53:13.548 | 补发RECORD完成 |
| 13:53:13.581 | 发送 `disableBluetooth` |
| 13:53:13.588–.590 | CarLife缓存为空，请求编码器重建 |
| 13:53:13.599 | 命令响应200，`accepted=true`；该响应本身不证明全部profile/ACL断开 |
| 13:53:13.615 | 蓝牙iAP2保活结束 |
| 13:53:13.628 | `sourceStreaming=false`，但交接被判为预期，暂停对应蓝牙owner的恢复 |
| 13:53:33.639 | 等首帧超时，CarLife没有推流，本地真实视频送帧计数为0 |
| 13:54:52.938 | 最终断开请求 |

**已确认的实现缺口是：断蓝牙命令没有CarLife当前源就绪门禁。** `SessionController` 在RECORD及Wi-Fi iAP2控制检查后、接入真实CarLife视频之前调用 `AirPlayClient.disableBluetoothAfterActivation`，旁边注释明确为“no CarLife gate”。后者检查RECORD、Wi-Fi iAP2、接收端蓝牙声明、地址和SETUP归属，但不检查CarLife。`videoSender.isRunning`只是传输通道运行，不代表CarLife已经连接或有真实帧。

周五 `be8a44c` 引入命令，`2e1540b`修正params封装后本车能够接受，`1f9bbb2`又在接受后暂停对应蓝牙恢复。三项串联使“CarPlay先连接、CarLife晚启动”路径可以在CarLife尚未满足启动条件时先释放蓝牙，再将其视为无需恢复的交接。今日日志直接证实源为空时发送并接受命令，紧接着蓝牙会话结束；用户无法随后连蓝牙启动CarLife的反馈与此缺口一致。

这里应修正过去的适用范围：周五正向样本只证明“CarLife已经连接及出帧后，命令200和蓝牙关闭没有中断当次CarLife”，没有证明“无CarLife源也可先断蓝牙”。第一轮是后一路径的反例。现有证据不需要把本地写成功或RECORD 200解释为稳定解码，也不将整个蓝牙栈所有profile的物理状态由200推定出来。

### 拟修复范围

1. 保持CarPlay握手、RECORD和控制通道原有时序；源未就绪时仅延后 `disableBluetooth`，不在关键握手线程等待CarLife。
2. 门禁绑定实际当前CarLife HU实例及源代次，确认该源连接、真实视频就绪，同时当前SETUP、控制会话和工作票据仍有效。不得用缓存历史GOP、提示帧、发送器运行或旧HU回调代替当前源状态。
3. CarLife先起时，在原激活条件和源条件同时成立后发送；CarPlay先起时，在当前源后来就绪后补评估。Wi-Fi iAP2较晚就绪同样应有补评估入口，不能因一次未就绪永久跳过。
4. 每个SETUP最多实际发送一次；延后不消耗发送机会，拒绝/错误不新增无限重试。源或SETUP更换、用户取消、旧回调晚到都不能作用于新会话。仅当前命令被接受且归属仍有效时沿用预期交接恢复策略。

本地回归需覆盖源缺失、源晚到、控制晚到、CarLife先起、重复就绪事件、旧源/旧SETUP、取消、拒绝和错误。实车验收分别验证两种启动顺序：CarPlay先连时仍能随后启动CarLife；CarLife先起时不中断既有源。发送或本机测试通过均不是实车显示确认。

## 第二轮：复用的是启动前已存在的系统默认热点

| 时间 | 应用或系统证据 |
| --- | --- |
| 13:51:03 | Wi-Fi系统UID1010自动连接保存配置193，记录为 `NOMINATOR_SAVED`；不是本应用specifier |
| 13:51:04 | 保存热点完成关联及地址配置 |
| 13:51:23 / 13:51:46 | 系统分别标记临时、永久无互联网禁选；现有连接仍保持 |
| 13:53:03.523 | 第一轮起点默认网络已经是车机Wi-Fi，`INTERNET=true`、`VALIDATED=false`、本进程尚未绑定 |
| 13:53:03.770–.803 | 旧suggestion为0；仅被动复用Network889，`activeRequestRetained=false` |
| 13:54:56.629–.683 | 第二轮起点仍是同一车机默认网络；再次被动复用889，无主动请求 |
| 13:55:10–12 | CarLife控制连接建立并收到首帧；用户确认自动手机网络提示在CarPlay完成前出现，但没有提示精确时间或其系统调用记录 |
| 13:55:20–26 | 车机邀请触发AirPlay建立，开始本地送帧；网络仍是889 |
| 13:55:47 / 13:55:57 | NetworkStack对889的DNS探测成功，但多个HTTP/HTTPS探测在连接阶段10秒超时 |
| 13:56:00–03 | 用户确认手动关闭、开启Wi-Fi；系统记录相应开关及889退出 |

NetworkStack补证说明第二轮存在实际外网探测失败，不只是应用看见一个 `!VALIDATED` 标记。它也不能由DNS成功推出外网可用。Wi-Fi配置193的创建/更新/上次连接UID均为系统UID1000，`allowAutojoin=true`、`noInternetAccessExpected=false`；最近连接为13:51:04，最近更新为13:51:23。系统记录中13:53—13:55没有本应用新增、修改或移除该保存配置。本应用UID与CarLife UID均不同于这些系统UID；系统UID记录不进一步证明最初保存操作是谁通过界面触发。

**当前可确认的网络异常是：系统先自动连接了不能通过外网探测的保存热点，且在应用开始前已选其为默认；应用随后只复用这个既有网络。** 不能将它改写成“第二轮预连接新建热点并覆盖CarLife设置”。CarLife自动提示存在是用户事实，但提示是否成功改变整机策略、只改变自身流量，或由厂商其他机制处理，目前没有调用证据。

### 与周五问题的关系

周五午间证据链是“记忆路径发起specifier → 旧Network失效 → 复用注销主动请求 → 临时配置移除 → 系统重连保存热点”。`d350673`已分离主动请求、观察和绑定，见[修复记录](../2026-11-13-q4-network-comparison/wifi-lifecycle-fix.md)。今日前两轮没有specifier、没有主动request、没有Network替换，不能套用该链或认定该修复回归。

`startRememberedWifi → joinCarWifiOnce → adoptIfAlreadyConnected → bindCurrentWifiBlocking`本次只观察、验证身份和绑定本进程；`WifiNetworkLifecycle.startProbe/endProbe`不注销主动请求。未发现此路径写全局蜂窝偏好或调用系统保存网络的兼容分支。记忆预连接参与了提早准备CarPlay，但这与“其制造了本次整机默认网络状态”是不同结论。

事后Connectivity快照为 `Bad Wi-Fi avoidance=false`、`Actively prefer bad wifi=true`、`force true`，与允许优先未验证Wi-Fi相容；它不是13:55时刻策略记录，空的当前Network overrides也不能证明旧889从未有覆盖策略。不能据此给CarLife提示失效编造一个已确认的API原因。

### 修复与验证方向

- 保留固定凭据和周五生命周期修复，不因本次现象回滚；不盲目通过注销网络、重发specifier或清除全部记忆强迫重新关联。
- 明确区分保存热点复用和本应用主动请求。修正“应用专属网络已在位”“其他应用继续走4G/5G”等无条件文案：本进程绑定和整机默认路由是两件事。
- 在启动前后及CarLife就绪时记录网络来源、Network代次、进程绑定、默认网络及蜂窝能力变化；用默认网络回调补足目前周期采样。对启动前已存在的无验证默认车机热点明确报告状态，不把CarPlay建链成功当成整机联网正常。
- 可做一次独立、可恢复的对照：若系统设置支持，关闭目标保存热点的“自动连接”，保留App加密记忆与已有应用专属请求能力，再重复两种启动顺序，确认默认蜂窝及外网可用。这是待验证的规避方案，不是让用户每次开关Wi-Fi或已证实的业务修复；本轮没有操作此设置。不得静默删除用户保存网络或使用ADB写策略作为产品前置条件。
- “拒绝复用默认保存热点并强制specifier”不能直接列作确定修复：系统可能复用同一个主Wi-Fi，强制重建也可能扰动CarLife。现有证据不足以提出已验证的纯App全局路由修复，先以单变量对照确定能否稳定避开保存热点自动连接路径。
- 两种启动顺序都比较：CarLife自动提示前后是否更换Network、系统默认是否为已验证蜂窝、地图/浏览器是否实际可联网、CarLife是否持续。必要时同时保留系统默认网络和CarLife调用证据，才能判断提示为什么未实现预期效果。

普通App不能承诺强制其他应用全部走蜂窝。[Android的bindProcessToNetwork说明](https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network))明确它绑定当前进程；[Wi-Fi网络请求说明](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)也不将此请求作为互联网网络。二者不能替其他应用设整机默认路由。现有调查没有找到本应用覆盖CarLife设置的确定调用；网络部分的产品修复应与已确认的蓝牙门禁缺口分开实施、验证和表述。

## 本机验证

本次没有安装APK、启动手机应用、修改手机网络设置或新增Q4路测；USB操作仅采集只读证据。业务修复尚未实施。文档提交前执行 `build-local.ps1 -NoDaemon`，1042项JVM单测通过（0 failures/errors/skipped），lint 0 errors / 105 warnings / 2 information，Debug APK构建成功（3分16秒）。最初沙箱内Gradle无法连接本机daemon，正常权限重跑通过。该验证针对当前未修改的业务源码，不能视为拟议修复已实现或实车通过；APK未安装。`git diff --check`通过，脱敏摘录人工复核完成。
