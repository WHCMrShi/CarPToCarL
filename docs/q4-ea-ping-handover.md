# Q4 Wi-Fi EA Ping 实验交接

2026-11-20 · 下一步：**用已安装的测试包采集一次启用 EA Ping 的实车日志，不先改协议。**

## 从哪个分支继续

| 项目 | 位置 |
| --- | --- |
| 源码 | [`WHCMrShi/CarPToCarL` 的 `codex/q4-ea00-retest`](https://github.com/WHCMrShi/CarPToCarL/tree/codex/q4-ea00-retest)，从 Q4 基线 `86f4d21` 单独建立 |
| 手机已装 | Debug `1.2.31-q4-debug`，源码修订号 `4bf11b18dd8a`；覆盖安装后已核对 APK 哈希和私有采集文件的启动标记 |
| Ping 实现 | `a29c76f`；本机离线单测、lint 和 Debug APK 构建通过，**Ping 尚未实车验证** |
| 实车证据归档 | `codex/audi-q4-etron`；仅文档，未合入实验代码 |

回家后在该仓库执行 `git fetch origin`，切到 `codex/q4-ea00-retest` 并快进到远端；如果本地没有这个分支，用 `git switch --track origin/codex/q4-ea00-retest`。手机已装匹配版本时不用重装。若要重建，按[本机工具链说明](local-build-windows.md)准备 Windows 环境，再运行 `./scripts/build-local.ps1`。

## 实车已确认什么

- 早期两轮在蓝牙上提交六条 `0xEA00`，没有观测到 `0xEA03`。[脱敏记录](https://github.com/WHCMrShi/CarPToCarL/blob/3117081fe9d0382c130f0937375d31382cafde4b/docs/vehicle-tests/2026-11-20-q4-ea00-retest/analysis.md)
- Wi-Fi 版 `e79f865f70ac` 在 CarPlay 先连的一轮中，经 Wi-Fi iAP2 认证后提交三条申请。只有 `de.audi.mmiconnectdatatransfer`（协议 ID 1、EA 会话 ID 257）在约 161 ms 后收到 `SessionStatusOK`。`eadata0`（258）和 `eadata1`（259）未见状态，不判拒绝。回执早于 CarLife 接入及 `disableBluetooth`。[脱敏记录](https://github.com/WHCMrShi/CarPToCarL/blob/3117081fe9d0382c130f0937375d31382cafde4b/docs/vehicle-tests/2026-11-20-q4-wifi-ea/analysis.md)
- 该轮普通日志没有 EA 入站数据帧，正文文件只有启动标记。状态正常不是业务数据已到达的证明；`0xEA02` 是启动应用请求，不是会话回执。新版 CarLife 先启动的独立冷启动尚未测试。

## 当前测试包行为

Wi-Fi iAP2 协商、识别和认证后，使用同一链路当次声明的协议 ID 经**控制会话**申请 EA，不等 `disableBluetooth`，不回退蓝牙。只有同连接的 MMI 申请收到匹配的 `SessionStatusOK` 且「EA Ping」打开，才经协商的 Wi-Fi **EA 数据会话**每 5 秒提交一个字节 `00`；外层数据报另带两字节 EA 会话 ID。链路不可写就跳过，不补发。开关默认关，关闭开关、收到 `SessionClose` 或断链即停。没有 Pong 样例，不预设回复格式，也不恢复旧车控界面。

实验分支的代码入口：`VirtualIap2Link.updateEaPingLocked` 决定何时发，`Iap2LinkEngine.sendEaPing` 构造链路数据报，`EaPayloadLog` 保存入站正文。

## 下次停车实测

1. 打开「使用车机 GPS」下方的 **EA Ping**（不必打开 GPS）。分别记录 **CarPlay 先连接**和 **CarLife 先启动**两轮的启动顺序与时间，确认获得匹配的 MMI 正常状态后留出多个发送周期。
2. 用 USB 接回手机、允许调试。通过 Debug 包的 `run-as` 读取两份应用私有文件；不用在手机手动导出：

   ```powershell
   adb devices -l
   adb shell run-as io.github.shaonialife.carptocar cat files/main_debug.log | Select-String 'revision=|EA:'
   adb shell run-as io.github.shaonialife.carptocar cat files/ea_payloads.hex.log
   ```

3. 按修订号、连接、时间和 EA 会话 ID 对账。先找 `0xea03 session=257 status=0`，再看 `ping submitted` 或 `ping skipped`。本地提交不等于车机收到。若有匹配的 `RX WiFi data` 且长度大于零，再读专用文件里的完整 `bodyHex`；不要把第一帧直接当完整消息，也不要先认定它是 Pong 或车辆信息。
4. 如果正文文件只有启动标记、普通日志也无入站数据，结论仍是“本轮未观测到 EA 数据”。若有提交而无入站，先记录实际发送次数与观察窗口，不凭空加请求或改 Ping 格式。只归档脱敏时间线和字段摘要，原始日志、完整正文及地址不入库。
