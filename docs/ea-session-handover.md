# EA 会话申请研究交接

2026-11-20 · `codex/q4-ea-research` · 代码基线 `401de96`

当前 Android 版本只具备 EA 入站诊断。分支中没有主动发送 `0xEA00` 的实现，也没有一条由该版本发起、经 Q4 确认的 EA 会话记录。下面的验收判据适用于能够独立观察到申请和响应的会话。

## 发送侧已有证据

| 已核实的事实 | 证据与适用范围 |
| --- | --- |
| myAudi 调用 Apple 初始化器时传入配件对象和协议名称。初始化器可能返回空值。 | myAudi 5.9 静态代码与 [Apple 的 EASession 初始化器](https://developer.apple.com/documentation/externalaccessory/easession/init%28accessory%3Aforprotocol%3A%29) |
| 对于使用 iAP2 EA 的配件，手机可以发送 `StartExternalAccessoryProtocolSession`（`0xEA00`）发起会话。 | [Emlid Reach RX 产品文档](https://docs.emlid.com/reachrx/developer-resources/nmea-specification/) 给出了 iPhone 发起申请、接收机随后发送定位数据的实例。该记录的对端是 GNSS 接收机。 |
| 配件声明的协议、某次申请的会话分别有独立标识。旧代码曾把协议名称写在会话标识的位置。 | 对照了历史规范、Simulator 消息定义的第三方导出及历史源码。本轮研究没有取得 Apple 分发的原始归档并复算其哈希。 |

代码审阅已追到 myAudi 主程序的 MMI 后台路径，以及 PDICarKit 两个管理器把各自协议名交给初始化器的路径。只能确定各路径内部有记录的先后关系。Q4 上哪条路径最先创建成功，以及业务层的第一条消息由谁发起，仍无运行记录。[IPA 研究记录](q4-ea-research.md)

## 如何判断一次申请的结果

先确认同一次连接有可归属的发送事件，再按 EA **会话标识**关联入站记录。蓝牙和 Wi-Fi 的应用日志均可搜索 `EA:`。

| 观察到的现象 | 可写入实验结论的内容 |
| --- | --- |
| 只有本地 TX 或链路 ACK | 有发送或链路层活动的证据，配件的会话状态未知。 |
| 收到同一会话的 `0xEA03`，状态为 `SessionStatusOK` | 配件明确报告该会话状态正常。 |
| 收到同一会话的 `0xEA03`，状态为 `SessionClose` | 配件报告关闭该会话。关闭原因需要另有证据。 |
| 同一会话有正长度的 EA 入站数据 | 该会话的数据已到手机。要确认车辆数据，还需识别应用层消息。 |
| 只有 `0xEA02 RequestAppLaunch` | 配件请求启动应用；该事件本身不能确认任何一次会话申请的结果。 |

同一会话已有数据时，即使缺少状态消息，也可据此确认入站通路。单凭等待超时，无法区分配件未接受、未报告状态和没有应用数据可发。

## 现有日志的边界

控制消息日志显示通道、消息类型、长度和报文体。状态消息另显示会话标识和状态值；遇到未知状态、异常字段或截断参数时，保留原始证据。超过 4096 字节的控制报文仅显示头尾并标明省略长度。EA 数据帧日志显示链路会话号、帧序号、EA 会话标识和数据长度，不包含应用数据正文。[控制消息诊断](../app/src/main/java/io/github/shaonialife/carptocar/iap2/Iap2EaDiagnostics.kt) · [数据帧诊断](../app/src/main/java/io/github/shaonialife/carptocar/iap2/Iap2LinkEngine.kt)
