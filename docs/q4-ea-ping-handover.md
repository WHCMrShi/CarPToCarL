# Q4 Wi-Fi EA Ping 实验交接

2026-11-20 · 本分支新增 `eadata0/1` 手动申请。分别验证 CarLife 先启动与 CarPlay 先连接，记录每次点击及车机回执。

## 从哪个分支继续

| 项目 | 位置 |
| --- | --- |
| 源码 | [`WHCMrShi/CarPToCarL` 的 `codex/q4-ea00-retest`](https://github.com/WHCMrShi/CarPToCarL/tree/codex/q4-ea00-retest)，从 Q4 基线 `86f4d21` 单独建立 |
| 上轮手机包 | Debug `1.2.31-q4-debug`，源码修订号 `4bf11b18dd8a`；手动申请需要新版测试包 |
| Ping 实现 | `a29c76f`；已用 `4bf11b18dd8a` 实车取证，CarPlay 先连的一轮有 407 次可逐条配对的 Ping 应答 |
| 手动申请实现 | `c629bdf72b94`；1128 项单测、lint 和 Debug APK 通过，待安装及实车复测 |
| 实车证据归档 | `codex/audi-q4-etron`；仅文档，未合入实验代码 |

重建测试包时，在 `codex/q4-ea00-retest` 上快进到远端，再按[本机工具链说明](local-build-windows.md)运行 `./scripts/build-local.ps1`。

## 实车已确认什么

- 早期两轮在蓝牙上提交六条 `0xEA00`，没有观测到 `0xEA03`。[脱敏记录](https://github.com/WHCMrShi/CarPToCarL/blob/3117081fe9d0382c130f0937375d31382cafde4b/docs/vehicle-tests/2026-11-20-q4-ea00-retest/analysis.md)
- Wi-Fi 版 `e79f865f70ac` 在 CarPlay 先连的一轮中，经 Wi-Fi iAP2 认证后提交三条申请。只有 `de.audi.mmiconnectdatatransfer`（协议 ID 1、EA 会话 ID 257）在约 161 ms 后收到 `SessionStatusOK`。`eadata0`（258）和 `eadata1`（259）未见状态，不判拒绝。回执早于 CarLife 接入及 `disableBluetooth`。[脱敏记录](https://github.com/WHCMrShi/CarPToCarL/blob/3117081fe9d0382c130f0937375d31382cafde4b/docs/vehicle-tests/2026-11-20-q4-wifi-ea/analysis.md)
- 后续两轮在 `257` 记录到 492 条单字节入站。CarPlay 先连的一轮可配对 407 次 Ping，CarLife 首帧先到的一轮有 85 条周期性入站。[脱敏时间线](https://github.com/WHCMrShi/CarPToCarL/blob/924095b/docs/vehicle-tests/2026-11-20-q4-wifi-ea-ping/analysis.md)

## 当前测试包行为

Wi-Fi iAP2 认证后只自动申请 MMI，会话 ID 为 `257`。点击「EA Ping」下方的「申请 eadata0」或「申请 eadata1」各提交一条 `0xEA00`，同一连接的手动会话 ID 从 `258` 起递增。收到任何 Wi-Fi `0xEA03` 时提示状态，收到非 MMI 会话的 EA 入站时提示会话和字节数。原日志与专用正文采集保留。

同连接的 MMI 申请收到匹配的 `SessionStatusOK` 且「EA Ping」打开后，经协商的 Wi-Fi EA 数据会话每 5 秒提交 Ping。开关默认关闭，关闭开关、收到 `SessionClose` 或断链即停。

实验分支的代码入口：`VirtualIap2Link.updateEaPingLocked` 决定何时发，`Iap2LinkEngine.sendEaPing` 构造链路数据报，`EaPayloadLog` 保存入站正文。

## 下次停车实测

1. 分别记录 **CarPlay 先连接**和 **CarLife 先启动**两轮的启动顺序、Ping 开关状态、手动申请时间与收到的提示。对比 Ping 打开和关闭时，尽量保持 Wi-Fi 认证到点击的间隔一致。收到 VIWI 的 `0xEA03 OK` 后先观察入站。
2. 用 USB 接回手机、允许调试。通过 Debug 包的 `run-as` 读取两份应用私有文件；不用在手机手动导出：

   ```powershell
   adb devices -l
   adb shell run-as io.github.shaonialife.carptocar cat files/main_debug.log | Select-String 'revision=|EA:'
   adb shell run-as io.github.shaonialife.carptocar cat files/ea_payloads.hex.log
   ```

3. 按修订号、连接、时间和 EA 会话 ID 对账。先找 `0xea03 session=257 status=0`，再看 `ping submitted` 或 `ping skipped`。本地提交不等于车机收到。若有匹配的 `RX WiFi data` 且长度大于零，再读专用文件里的完整 `bodyHex`；不要把第一帧直接当完整消息，也不要先认定它是 Pong 或车辆信息。
4. 如果正文文件只有启动标记、普通日志也无入站数据，结论仍是“本轮未观测到 EA 数据”。若有提交而无入站，先记录实际发送次数与观察窗口，不凭空加请求或改 Ping 格式。只归档脱敏时间线和字段摘要，原始日志、完整正文及地址不入库。
