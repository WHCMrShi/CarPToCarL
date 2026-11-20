# Q4 Wi-Fi EA 复测 · 2026-11-20

手机日志修订号 `e79f865f70ac`（`1.2.31-q4-debug`）。通过 USB 只读检查 `main_debug.log` 与 `ea_payloads.hex.log`。本轮 CarPlay 先连。以下是消息与字段摘要，不包含完整包体，不能当作重放材料。

| 时间（北京时间） | 日志事实 |
| --- | --- |
| 15:23:07.316–15:23:08.026 | AirPlay TCP 建立并完成首个 RECORD。 |
| 15:23:09.120 | Wi-Fi iAP2 SYN 声明 EA v1，链路会话号 3。 |
| 15:23:09.892 / .893 / .894 | 认证后依次提交 Wi-Fi `0xEA00`：协议 ID 1、2、3 对应 EA 会话 ID 257、258、259。 |
| 15:23:10.038 | 收到 Wi-Fi `0xEA02 RequestAppLaunch`，不作为会话状态。 |
| 15:23:10.053 | 收到 Wi-Fi `0xEA03`：会话 ID 257，状态 0（SessionStatusOK），控制体 11 字节。距对应本地提交 161 ms。 |
| 15:23:51.606 / .733 | CarLife 控制端连接并被接管。 |
| 15:23:54.650 | `disableBluetooth` 收到 RTSP 200，实际蓝牙断开不是由该 200 单独证实。 |
| 15:25:36.775 | AirPlay 控制读侧 EOF；本次日志保留至 15:25:49.613。 |

- `de.audi.mmiconnectdatatransfer`（257）收到正常状态。`com.audi.cpp.eadata0`（258）、`com.audi.cpp.eadata1`（259）没有观测到会话状态，不能判定拒绝。
- 正文文件只有该修订号的启动标记（一行），普通日志没有 EA 入站帧摘要或采集失败信息。在本实现和观察窗口内未观测到 EA 数据帧；不能推出车机永不发数据，也未确认任何车辆信息。
- 本次回执早于 CarLife 与 `disableBluetooth`，并且是在首个 RECORD 之后观察到；这不证明 RECORD 是必要条件。上一轮为早期蓝牙请求，不能独立区分 Wi-Fi 通道与发送时机的作用。新版 CarLife 先启动的独立冷启动未观测。
