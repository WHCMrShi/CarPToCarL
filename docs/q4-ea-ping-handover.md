# Q4 Wi-Fi EA Ping 实验交接

2026-11-20 晚间 · 已验证手动申请确实提交。两条 VIWI 通道在已有 Pong 的情况下仍未见状态，车机侧处理结果待取证。

## 从哪个分支继续

| 项目 | 位置 |
| --- | --- |
| 源码 | [`WHCMrShi/CarPToCarL` 的 `codex/q4-ea00-retest`](https://github.com/WHCMrShi/CarPToCarL/tree/codex/q4-ea00-retest)，从 Q4 基线 `86f4d21` 单独建立 |
| 手机已装 | Debug `1.2.31-q4-debug`，源码修订号 `bfbda5e862a1`；覆盖安装后已核对 APK 哈希和私有采集文件的启动标记 |
| Ping 实现 | `a29c76f`；本机离线单测、lint 和 Debug APK 构建通过，已用 `4bf11b18dd8a` 实车取证 |
| 本交接与实车证据 | `codex/audi-q4-etron`；仅文档，未合入实验代码 |

回家后在该仓库执行 `git fetch origin`，切到 `codex/q4-ea00-retest` 并快进到远端；如果本地没有这个分支，用 `git switch --track origin/codex/q4-ea00-retest`。手机已装匹配版本时不用重装。若要重建，按[本机工具链说明](local-build-windows.md)准备 Windows 环境，再运行 `./scripts/build-local.ps1`。

## 实车已确认什么

- 早期两轮在蓝牙上提交六条 `0xEA00`，没有观测到 `0xEA03`。[脱敏记录](vehicle-tests/2026-11-20-q4-ea00-retest/analysis.md)
- Wi-Fi 版 `e79f865f70ac` 在 CarPlay 先连的一轮中，经 Wi-Fi iAP2 认证后提交三条申请。只有 `de.audi.mmiconnectdatatransfer`（协议 ID 1、EA 会话 ID 257）在约 161 ms 后收到 `SessionStatusOK`。`eadata0`（258）和 `eadata1`（259）未见状态，不判拒绝。回执早于 CarLife 接入及 `disableBluetooth`。[脱敏记录](vehicle-tests/2026-11-20-q4-wifi-ea/analysis.md)
- 下午的实车样本记录到 492 条 MMI 入站，其中 CarPlay 先连的一轮有 407 次可逐条配对的 Ping 应答。CarLife 首帧先到的一轮有 85 条周期性入站，发送与回执明细未保留。[脱敏时间线](vehicle-tests/2026-11-20-q4-wifi-ea-ping/analysis.md)
- 晚间的手动测试包共提交 30 次 VIWI 申请。实车日志中四条 Wi-Fi 链路各收到一次 MMI 状态，EA 专用文件另有 33 条 MMI 入站。两条 VIWI 会话路径仍未见状态或数据。[手动申请时间线](vehicle-tests/2026-11-20-q4-viwi-manual/analysis.md)

## 当前测试包行为

Wi-Fi iAP2 认证后自动申请 MMI 会话。两条 VIWI 协议各有一个手动按钮，每次只提交一次 `0xEA00`，使用当次声明的协议 ID 和从 `258` 起递增的会话 ID。界面提示任意 Wi-Fi `0xEA03` 以及非 MMI EA 入站。收到匹配的 MMI `SessionStatusOK` 且打开「EA Ping」后，约每 5 秒发送一次 Ping。开关默认关，关闭开关、收到 `SessionClose` 或断链即停。

实验分支的代码入口：`VirtualIap2Link.updateEaPingLocked` 决定何时发，`Iap2LinkEngine.sendEaPing` 构造链路数据报，`EaPayloadLog` 保存入站正文。

## 下次停车实测

1. 记录 **CarPlay 先连接**和 **CarLife 先启动**的次序、点击时间和 Ping 开关状态。若能拿到车机侧诊断日志，按会话 ID 核对申请是否进入 `CIAP2EAPProtocolViwi` 及内部 TCP 连接结果。每条新链路先用少量申请，避免历史会话条目影响后续结果。
2. 用 USB 接回手机、允许调试。通过 Debug 包的 `run-as` 读取两份应用私有文件；不用在手机手动导出：

   ```powershell
   adb devices -l
   adb shell run-as io.github.shaonialife.carptocar cat files/main_debug.log | Select-String 'revision=|EA:'
   adb shell run-as io.github.shaonialife.carptocar cat files/ea_payloads.hex.log
   ```

3. 按修订号、连接、时间和 EA 会话 ID 对账。先找 `0xea03 session=257 status=0`，再看 `ping submitted` 或 `ping skipped`。对照专用文件的入站时间和正文长度，核对响应是否随开关出现或消失、是否存在其他入站格式。
4. 如果正文文件只有启动标记、普通日志也无入站数据，结论仍是“本轮未观测到 EA 数据”。若有提交而无入站，先记录实际发送次数与观察窗口，不凭空加请求或改 Ping 格式。只归档脱敏时间线和字段摘要，原始日志、完整正文及地址不入库。
