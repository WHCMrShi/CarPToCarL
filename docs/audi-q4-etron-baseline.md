# 奥迪 Q4 e-tron 开发基线

分析日期：2026-11-05。源仓库：WHCMrShi/CarPToCarL，基于 main 提交
`20136db`（RTSP 全量报文落盘）。开发分支：`codex/audi-q4-etron`。

## 目标与当前边界

先完成 Q4 e-tron 的连接兼容与功能稳定性，再重写 UI。
本次只建立开发基线与代码分析记录，不修改协议、默认配置、UI 或版本号。
尚未收到 Q4 e-tron 的实车日志，以下关注点不是已确认的 Q4 缺陷。
仓库中的其他奥迪实车、固件分析与历史问题用于提供线索，不能代替目标车辆验证。

## 链路与代码入口

| 阶段 / 功能 | 主要代码（相对 app/src/main/java/io/github/shaonialife/carptocar/） | 排查重点 |
| --- | --- | --- |
| 会话调度 | SessionController.kt、service/CarPlayService.kt | 连接取消、重试、生命周期、并发回调 |
| 蓝牙与 iAP2 | bt/Iap2BluetoothClient.kt、bt/Iap2BluetoothServer.kt、iap2/Iap2SenderSession.kt、iap2/Iap2LinkEngine.kt | 主动拨号/车机回连、身份、热点凭据、0x4301、ACK 与重传 |
| Wi-Fi 与端点 | net/WifiCarPlayConnector.kt、net/CarPlayServiceBrowser.kt、net/WifiLinkWatchdog.kt | 入网、Network 有效性、绑定、mDNS 与地址候选 |
| AirPlay | airplay/AirPlayClient.kt、airplay/PairingClients.kt、airplay/CarDisplayGeometry.kt | 配对、SETUP/RECORD、共享密钥、屏幕选择 |
| CarLife 画面来源 | carlife/CarLifeHijack.kt、carlife/CarLifeHuClient.kt | 引擎启动、通道重连、编码参数、首帧 |
| 视频 | video/VideoSender.kt、video/VideoDeliveryGate.kt | 车机画布、SPS/PPS、首帧门、恢复 |
| 音频 | audio/AudioSender.kt、audio/AudioProfileMemory.kt、SessionController.kt | 媒体/导航/通话切换、静音保活、音频流重建 |
| 触控与语音 | input/TouchRouter.kt、input/TouchReports.kt、carlife/VoiceKeyRouter.kt | 坐标、按键、requestSiri、长短按 |
| 车机 GPS / 媒体信息 | iap2/NmeaLocation.kt、iap2/Iap2NowPlaying.kt、media/MediaListenerService.kt | GPS 开关、媒体状态与按键回传 |
| 奥迪扩展控制 | mib3/Mib3ControlLink.kt、mib3/Mib3Viwi.kt | EA 协议声明、0xEA03 就绪门、viwi 响应 |

主链路是手机 CarLife 引擎 → 本应用接管 H.264/PCM → 无线 CarPlay 车机。
视频以直通为主，不能简单通过改分辨率声明修复所有黑屏问题。
SessionController 同时协调连接、网络、音视频和恢复，是后续改动需要重点检查相互影响的入口。

## 与 Q4 兼容相关的现有约束

- `iap2/ReceiverProfile.kt` 对稀疏能力清单保持保守判断，不能仅凭未声明旧 Wi-Fi 消息就切换协议。
- 车机窗口从实际 AirPlay 信息协商；已有文档中的 1412×654 对应其他样本，不应写死给 Q4。
- 现有配对流程存在无共享密钥的降级路径。出现“有画面、无声音/触控”时先查配对结果，而非直接调音量或编码。
- EA 扩展控制与 CarPlay 投屏分属不同链路；扩展控制不可用不能直接归因为投屏失败。
  现有 Mib3ControlLink 将 0xEA03 作为数据发送就绪条件，修改时应保留这条约束。
- 历史奥迪样本记录过音频流长时间空闲后首播无声、EA 未就绪时发送数据导致 iAP2 失去 ACK；
  主线已有对应处理，需要在 Q4 上回归，暂不重复打补丁。

参考：`docs/connect-hardening.md`、`docs/wifi-link-watchdog.md`、
`docs/audi-mib3-ea-tunnel-20261104.md`、`docs/mib3-carplay-display-contract.md`、
`docs/audio-call-issues.md`、`docs/carlife-voice-siri-20261104.md`。

## 构建与验证现状

- Android/Kotlin 工程：minSdk 31、compileSdk/targetSdk 34、JVM 17；AGP 8.7.3、Kotlin 2.0.21。
- Gradle wrapper 指向原作者本机的
  `C:/Users/jjuugk/Desktop/cardeck/.tools/gradle-8.10.2-bin.zip`，新机器无法直接复用。
  准备本地构建时需改成可访问、可验证的 Gradle 8.10.2 发行源。
- 当前会话环境未发现 PATH 中的 java/adb，JAVA_HOME/ANDROID_HOME 也未配置。
  本次没有完成编译、单元测试、lint 或 APK 安装；不声称代码已通过构建或实车验证。
- 配好工具链后执行 `gradlew.bat spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
  对具体 BUG 优先增加能覆盖根因的回归测试；无线协议和车机表现仍需实车确认。
- 仓库不包含发布私钥；后续测试 APK 的签名和覆盖安装方式需结合当前车测包确定。

## 后续车测反馈格式

每个问题建议附上以下信息，便于将现象和日志对应：

1. Q4 年款、地区版本、MMI 软件版本（设置中的版本信息）。
2. 手机型号、Android/MagicOS 等系统版本、CarLife 版本、当前安装 APK 版本与来源。
3. 操作步骤、预期结果、实际结果、复现频率、发生时刻；说明是首次连接还是重连。
4. 问题发生后立即导出应用日志，优先提供“全部历史日志”，以覆盖断开/重启前后的过程。
   当前导出位置是手机 `下载/CarPToCarL/CarPToCarL-log-*.txt`。
5. 如涉及黑屏、音频或触控，分别说明画面、音乐、导航播报、触控、方向盘按键是否正常。

`util/LogExporter.kt` 会附带最近的连接失败快照，但快照可能来自之前的失败轮次，需按时间核对。
main 基线另有私有目录中的 `files/rtsp-YYYYMMDD.log`（单文件轮转），普通日志导出目前没有包含它；
需要完整 RTSP 时再安排采集方式，不把普通导出等同于完整协议转储。
对外分享日志前可遮盖热点密码、配对材料和设备地址，保留消息类型、时间顺序及长度信息。

## 迭代顺序

1. 用目标车辆日志确定实际车机能力和连接路径，建立首连与重连基线。
2. 逐项修复连接、断线恢复、黑屏、音频、触控/语音、GPS/媒体联动，记录对应测试结果。
3. 功能稳定后再重写 UI。每次实现和验证结果提交到开发分支并推送，保留 main 作为对照。
