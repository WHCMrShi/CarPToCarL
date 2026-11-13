# 2026-11-13：disableBluetooth 参数封装修正

开发基线 `8566180e1808`，业务修复提交 `2e1540b`。用户要求参照 DiPlay 等接收端实现继续解决422，本轮只修正有外部证据的命令参数层级。文档单独提交；没有新增Q4实车日志。

## 新证据与前次结论更正

前次[协议核查](protocol-and-system-review.md)确认了binary plist可解析、地址匹配和发送阶段，但据2015年规范排除层级问题的结论不充分。**能解码plist不等于接收端能从约定位置取得参数。** 原来的单测也直接断言了扁平结构，没有独立核对接收端读取位置。

| 来源 | 可核实内容 | 证据边界 |
| --- | --- | --- |
| 用户提供的2015-12-18规范 §23.3.7，印刷页477；表23-66，479 | 通用章节的hidSendReport示例是扁平结构；deviceID定义为手机蓝牙MAC字符串 | 旧文档确实有扁平示例，不能改写为“原文明示params”；但不能据此排除现有接收端封装差异 |
| [TROOPERS24 / TU Darmstadt / SEEMOO报告，第32页](https://troopers.de/downloads/troopers24/TR24_Apple_CarPlay-What%27s_Under_the_Hood_8MCYKG.pdf#page=32) | 手机方向的POST /command RTSP/1.0，binary plist内type=disableBluetooth，地址位于params.deviceID | 已核对PDF原页；公开报文分析未展示此命令的响应，也不是本辆Q4的成功样本。示例长度115字节不作为必须匹配的常量 |
| [DiPlay AirPlaySession.handleCommand，固定提交615b32c](https://github.com/shihabal3amri/DiPlay/blob/615b32ce0b40cd0b99ce94ba23a512001553684f/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt#L759) | 从根字典读取type，仅从body["params"]读取参数，再传给listener | 扁平deviceID会丢失。但此处理器仍返回200，不能声称“DiPlay对旧报文返回422”，也不能把它当成严格协议校验器 |
| [DiPlay CarPlayController.onCommand，同一提交](https://github.com/shihabal3amri/DiPlay/blob/615b32ce0b40cd0b99ce94ba23a512001553684f/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt) | 无线会话收到命令类型后进入蓝牙交接流程，检查当前会话归属；此入口未验证params.deviceID | 单靠DiPlay触发交接或200不能证明参数正确，更不能代替Q4验证 |
| [DiPlay issue #715的用户日志](https://github.com/shihabal3amri/DiPlay/issues/715) | 08:20:27.167收到disableBluetooth，参数键含deviceID；27.170上层再次记录该参数键 | 用户自报iPhone 14 Plus / iOS17.4，日志实际为USB路径；只作真实手机参数读取的旁证，不用于推导Q4无线时序 |
| 本车[脱敏报文核查](disableBluetooth.inspection.log) | 午间103字节，顶层只有type、deviceID，CSeq24返回422空响应 | 与外部报文及接收端读取方式存在明确层级差异；Q4内部422分支仍不可见 |

更正结论：**存在有依据的封装兼容性缺口，应先把deviceID放入params；缺参导致422与现象一致，但尚未由Q4重测闭环证明。** 手机地址的含义、来源及本次值的匹配结果不变，不能换成车机bluetoothIDs。当前项目其他已获200的命令（如modesChanged、iAPSendMessage）也采用params封装。

## 最小修复与自审

示意结构（发送仍为binary plist，以下不是原始负载）：

```text
原：{ type: "disableBluetooth", deviceID: "<手机蓝牙MAC>" }
新：{ type: "disableBluetooth", params: { deviceID: "<手机蓝牙MAC>" } }
```

只改变AirPlayClient.disableBluetoothAfterActivation中这一层结构。保留手机当前SETUP身份、POST路径、控制通道、编码、自动长度计算、RECORD与Wi-Fi iAP2就绪条件、单次发送和旧会话隔离。没有加入地址变体、大小写转换、延迟、重试、CarLife门禁或新的状态兜底；不修改Wi-Fi修复、默认配置或版本号。

构建前自审：新参数保留字符串类型；顶层不重复保留deviceID；既有request计算长度及派发CSeq，未硬编码115；scope/generation检查及CAS只发一次仍在；422仍记录accepted=false及实际断开未确认。未发现需要扩大修改范围的问题。

## 本机验证与实车边界

- 先修改socket测试，使其按接收端路径读取params，检查顶层键、地址和类型。旧业务代码下6项中2项失败，分别为命令结构和新会话身份读取；其余4项通过，确认测试能识别旧封装。
- 拒绝测试改用本次真实出现的422，并检查不重试、accepted=false及断开未确认日志。保留就绪条件、缺少能力/地址、旧会话隔离等既有测试。
- 未运行完整DiPlay APK，也未模拟其蓝牙交接成功；测试验证本机socket报文和状态行为，不是Q4接受情况。
- 初次沙箱内运行被Gradle回环连接限制阻断；授权运行后定向测试正常执行，其2项失败是预期的旧实现回归结果。
- 自审后运行 `build-local.ps1 -Offline -NoDaemon`，175个套件、1024项JVM测试全部通过（0失败/错误/跳过）；其中DisableBluetoothTest的6项socket测试通过。本轮修正既有测试，没有新增测试数量。lint为0错误、105项既有警告、2项信息；Debug APK构建成功。
- 构建内置标识 `8566180e1808-dirty`，对应随后提交的业务代码 `2e1540b`；构建后未再改业务代码或测试。APK SHA-256：`e38f89a70304022cb4f437e9e61be75870ecd4fc419f4268b2419e6c679793c6`。未安装、未路测；未因后续文档更新重跑单测/构建。

实车仍需先按用户安排让CarLife连接，再观察修正后BT-DISABLE响应，以及CarLife控制/画面/音频能否持续。即使200，也要区分命令接受与实际profile/ACL断开；此前“422之后CarLife继续”不能证明成功断蓝牙后继续。CarPlay先连接路径仍直接发送，未增加等待CarLife条件；该顺序能否随后拉起CarLife尚未验证。网络修复与本改动可在同一版本测试，但分别记录默认网络和命令响应，不将两者认定为同一根因。
