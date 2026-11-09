# CPC200-CCPA 研究对 CarPToCarL 交互模型的启发

日期：2026-11-09。范围：文档、反编译源码和本项目代码的静态对照；没有新增实车实验，没有修改业务逻辑。

## 1. 结论与适用范围

最有价值的借鉴是将连接、资源所有权、视频可解码性、音频用途和故障恢复分别建模，而不是复制一套延时和重发序列。优先调查本项目的直通关键帧请求与资源仲裁；其次审查静态画面保活和音频用途切换。

需要修正最初的范围假设：参考仓库并非只有有线 CarPlay。`carplay_handshake.md` 明确记录 2026-05-21 无线首配，使用 BT HCI、WiFi pcap 和固件日志；`video_protocol.md` 也分别记录有线与无线实验。不过，“VERIFIED”是作者对其材料的标注，本次没有独立重放抓包或验证全部二进制反汇编，不能升级为 Apple 规范或 Q4 实测。

最重要的角色区别：

| 系统 | 手机侧 | 接收侧 | 额外接口 |
| --- | --- | --- | --- |
| 参考系统 | 真 iPhone，负责编码与 CarPlay 发送 | CPC200，承担附件认证、AirPlay 接收 | CPC200 经私有 USB 协议向 AutoKit/其他 host 转交媒体和控制 |
| 本项目 | Android 模拟 CarPlay 发送端，视频来自 OPPO Car+ 的 CarLife 通道 | Q4 MIB3 | CarLife 输入桥与无线 CarPlay 输出桥 |

CPC 的 USB `0x55AA55AA`、命令 12、500/508/509、音频 0x07 和导航 0x2C 都不是能直接发给 Q4 的 AirPlay 消息。USB 接到 host 也不意味着 iPhone 侧是有线 CarPlay。无线发现、配对、WiFi 身份、事件通道等仍需使用本项目自己的协议实现。

## 2. 证据基线与入口

参考仓库固定提交：`a5831314037a221f0110359a53d87d6b7356bc95`。本项目代码基线：`253eee74374c1e263fa7da7d96b7e680e66cd54a`。本次源码位置均对应这个新基线，不沿用旧文档的 bcef70f 行号。

参考资料（以下链接固定版本，避免后续修订改变结论）：

- [R1 无线握手现场研究](https://github.com/lvalen91/CPC200-CCPA_resources/blob/a5831314037a221f0110359a53d87d6b7356bc95/documentation/02_Protocol_Reference/carplay_handshake.md)
- [R2 host 与适配器会话时序](https://github.com/lvalen91/CPC200-CCPA_resources/blob/a5831314037a221f0110359a53d87d6b7356bc95/documentation/02_Protocol_Reference/inbound_session_sequence.md)
- [R3 视频、IDR、副屏及有线/无线对照](https://github.com/lvalen91/CPC200-CCPA_resources/blob/a5831314037a221f0110359a53d87d6b7356bc95/documentation/02_Protocol_Reference/video_protocol.md)
- [R4 音频控制与实验更正](https://github.com/lvalen91/CPC200-CCPA_resources/blob/a5831314037a221f0110359a53d87d6b7356bc95/documentation/02_Protocol_Reference/audio_protocol.md)
- [R5 USB 命令语义](https://github.com/lvalen91/CPC200-CCPA_resources/blob/a5831314037a221f0110359a53d87d6b7356bc95/documentation/02_Protocol_Reference/command_details.md)
- [R6 USB 心跳](https://github.com/lvalen91/CPC200-CCPA_resources/blob/a5831314037a221f0110359a53d87d6b7356bc95/documentation/01_Firmware_Architecture/heartbeat_analysis.md)
- [R7 无线综述及其未验证段落](https://github.com/lvalen91/CPC200-CCPA_resources/blob/a5831314037a221f0110359a53d87d6b7356bc95/documentation/02_Protocol_Reference/wireless_carplay.md)
- [R8 AutoKit 解码器反编译代码](https://github.com/lvalen91/CPC200-CCPA_resources/blob/a5831314037a221f0110359a53d87d6b7356bc95/carlinkit/autokit/original/decompiled/cn/manstep/phonemirrorBox/AndroidH264view.java#L552)

本项目路径均位于 `app/src/main/java/io/github/shaonialife/carptocar/`：SessionController.kt、airplay/AirPlayClient.kt、video/VideoSender.kt。实车事实继续以 [Q4 交互模型](q4-carplay-interaction-model.md) 的 E1/E2/E3 为准；本研究不改变 H1/H2/H3 的证据等级。

证据使用规则：直接代码分支可证明实现；作者抓包摘要可支持该设备/版本下的行为；跨角色、跨车型的应用属于建议或假设。反编译 Java 不是固件源码，AutoKit 解码逻辑也不能证明 AirPlay 接收固件的全部行为。

## 3. 主要差异与优化调查项

### P0：关键帧请求必须追踪到真实来源

R3 的分日期抓包记录中，主视频可能启动后长期只有 P 帧；作者要求解码器丢失参考状态后主动请求 IDR。不能把该频率直接套给 OPPO，但它否定了“CarPlay 一定每秒出关键帧”的通用假定。

本项目 SessionController.kt:4560 收到 `forceKeyFrame` 后调用 VideoSender.requestKeyFrame，返回 true 即记录“已强制编码 I 帧”。VideoSender.kt:862 的直通分支只是 `if (passthrough) return true`；该分支没有调用上游请求接口。本地 MediaCodec 分支才真正设置同步帧请求。这是可证明的请求/日志语义缺口，不证明整个系统永远没有其他 IDR 请求途径。

建议未来将“收到请求、转交当前 CarLife 源、源确认/无法请求、同源 IDR 到达、配置与 IDR 写完”分别计数。调查 Car+ 是否接受现有 CarLife IDR 请求；若不支持，明确记录能力缺失，再研究有预算的源级恢复。不能把缓存旧 IDR 重发等同于编码器生成当前画面的新 IDR。

验证：在早起缓存、晚起真实源、源重建三种场景记录请求至同源 IDR 的延迟和结果。先做观测，再评估策略；不能依据 CPC USB cmd=12 为 Q4 新增同编号命令。

### P0：资源仲裁应表达请求内容，而非一律接管

R1 区分会话启动、模式变化与 host 视频焦点；R5 分开请求/释放视频、音频及导航焦点。它们提示“流已连接”和“用户界面应该显示”是独立状态，但没有给出可直接移植的 Q4 modesChanged 字典。

本项目 SessionController.kt:4570 对所有 `changeModes` 都调用 assertModes。AirPlayClient.kt:986 固定声明 resourceID 1、2 的 entity/permanentEntity 为 1，并发送固定 appStates，没有根据此次请求的资源分别计算结果。requestUI 在该事件分派中落入通用日志分支。

风险假设：MMI 回到 native、来电或临时借用时，这种统一声明可能与合法的车机资源变化互相覆盖。现有 E3 的 screen native 只能证明出现资源请求，不能据此确认争抢，更不能确认黑屏根因。

建议建立期望所有者、对端请求、实际确认三个状态；主屏、主音频独立；临时借用与永久归属独立。保留晚到源一次性恢复契约，但只在当轮源和会话有效、用户意图允许时考虑重新声明。需要先从当前 Q4 事件参数确认枚举含义，不能借 CPC USB 焦点编号推导 AirPlay 枚举。

验证：MMI 主动离开/返回投屏、通话接听/挂断、语音助手、倒车显示（有条件时）分别记录请求参数与本地响应，检查是否重复抢占、抢音频或误判断线。

### P1：静态画面、源失活、下游背压需要不同判据

R3 记录内容驱动的帧率和稀疏关键帧，静态界面长间隔不一定是故障。其开头“晚帧应立即丢弃”的建议不能机械照搬：压缩流有参考依赖，丢 P 帧后继续发送依赖它的 P 帧可能破坏解码；晚到本身也不等于码流损坏。

本项目 VideoSender.kt:601 的空闲路径重发缓存 IDR；:224 起有源重启等待 IDR、:261 有结构性堆积门禁，已经比任意逐帧丢弃更谨慎。应保留依赖链意识。可调查缓存 IDR 是否落后于最后可见画面，以及静态源停帧与失活是否可区分；不要仅凭参考设备静态画面正常就删除 Q4 保活。

R8 的 AutoKit 代码实际使用 MediaCodec(video/avc)，停止时 flush/stop，创建 Vector(320)，按 p.F 条件启动工作线程。320 是初始容量，不是已证明的有界延迟预算；这段旧代码也不支持将文档中的“100–200ms 缓冲”认定为所有实现的统一事实。成熟产品仍存在版本和实现差异。

验证：静态主页、动态地图、主动停止源、网络受限分别比较输入帧龄、最近源 IDR 年龄、输出队列、写完成、事件通道和可见画面。不能只用 fps=0 或 write 成功判定会话状态。

### P1：音频用途、流格式、资源所有权应分开

R4 区分媒体、导航 duck/restore、Siri、通话与麦克风；STOP 可重复，导航首包的格式标签也可能与后续不同。USB PCM 格式不必等于 AirPlay 线上格式，参考记录同时出现 AirPlay AAC-LC 与 USB 解码后 PCM。

本项目 Q4 当前以 48kHz PCM 下行为已观察基线；交互模型说明麦克风上行模块未接入。不能因 CPC 的语音 16kHz 或默认媒体 48kHz 就修改本项目 SETUP。优先检查 assertModes 同时声明主音频对通话返回的影响，并将音频用途生命周期与数据有无分开。

未来停止事件应幂等，Siri 结束不能关闭仍由通话持有的麦克风；导航结束应恢复此前媒体状态。它们是设计要求，不代表本次已查明每个音频分支缺陷。测试应包含媒体+导航、媒体+来电、语音与通话交叠、重复结束事件。

### P1：分层活性与恢复预算

R6 的 USB 心跳在某版本仅 host→adapter，抓包无 echo；二进制常量 15s 与现场约 10s 也不同。R2 的 Plugged、Phase7、Phase8 表示不同阶段，首次用户确认可能耗时很长。这说明不能用一个“连接成功”布尔值或任意消息当端到端健康。

本项目 AirPlayClient.kt:622 起发送 /feedback，:727 起解析接收统计，并已有异步 CSeq 派发避免长请求饿死心跳（:3621）。可借鉴的是分层监测，而不是把 USB 的 2s/10s 套给 Q4。反馈缺少屏幕统计时保持“未知”，不能补成 0fps 或成功。蓝牙退出也不自动等于 WiFi CarPlay 失效。

建议故障记录明确：发现/身份、入网、配对、控制会话、资源、真实源、数据写入、接收端统计、用户可见结果。优先修复失败层；源问题不先重配对，资源让出不先断 WiFi，终态清理则作废所有旧代次回调。

### P2：画布、副屏与 HUD 不可混为一层

R2 记录 viewArea 超出 display 可引起协商失败；R3 的导航副屏需要能力/配置和焦点握手，且无线与有线的发起者行为不同。可借鉴“能力→配置→资源→数据”的门禁，不能复制 AdvancedFeatures 或 508 指令。

本项目 RAW/SPLIT、CarLife VideoConfig、真实 SPS、AirPlay viewAreas 是不同契约。CPC 的 host HU_VIEWAREA_INFO 也不是 CarLife 头字段。应继续按 [画布契约](videoconfig-canvas-contract.md) 独立验证。

CPC 的 0x2C 视频副屏、0x2A 导航 JSON 与我们的 CarLife TBT/HUD 订阅是不同数据源。参考系统有导航数据不能证明 OPPO Car+ 一定导出；支持应答和发送 START 也不能证明数据已激活。

## 4. 建议采用的交互状态模型

这是从证据归纳的目标模型，不声称逐字复原了 CPC 固件：

1. 当前目标身份与连接代次成立；开始蓝牙/网络发现。
2. 链路、认证、WiFi、AirPlay 配对分别完成；新配对与复连分开记账。
3. SETUP 和事件/数据通道建立；资源所有权独立等待与更新。
4. 有当前源的配置和可解码 IDR，才进入真实视频发送；提示源不能充当真实源证明。
5. 接收统计（如有）和用户可见结果确认运行；普通静态间隔不自动触发重连。
6. 资源临时让出保持控制活性；源重建使旧配置、旧票据失效；恢复需要新源 IDR。
7. 分层预算耗尽或用户终止后进入终态，撤销本轮全部异步工作；新轮不继承旧请求。

本项目已经有源代次、缓存 GOP、一次性接管和工作归属门禁。下一步最有价值的是补全“为何请求、谁持有资源、请求是否实际执行”的状态证据，而不是增加更多固定延时。

## 5. 资料中的矛盾与不能直接采用的结论

- R7 一面列出无线 SETUP/RECORD，一面比较表称无线建立为 implicit；端点表又明确多数仅是通用示例。以 R1 的实测范围和本项目实际 RTSP 记录为准，不能取消 SETUP/RECORD。
- R7 对密码学、报文长度、SDP 的多处内容明确写 unverified/generic；本次不以其示意报文修改认证、加密或 nonce。
- R3 的 encoderState 历史解释、后续更正和频率表并不完全一致。共同可靠启发是解析码流而不是硬编码该字段；本项目没有理由增加这个 USB 字段。
- R3 部分引用的深度分析路径不是本次实际审阅材料；不把这些引用当成已独立核验的证据。主视频 IDR 频率只是作者特定采样结果。
- R6 曾将心跳描述为双向，后续抓包更正为单向；由此应优先使用注明版本、日期、方向的记录。
- R1 的单次 20B 挑战、端口、无线信道、窗口值均不是 Q4 通用常量。BT 清理的精确先后也不能当跨固件硬性时序。

## 6. 后续最小实验与判定

| 优先级 | 实验 | 要收集的证据 | 可推翻假设的结果 |
| --- | --- | --- | --- |
| P0 | forceKeyFrame 到达，直通源持续运行 | 源代次、请求转交结果、新 IDR 到达/写完时间 | 不触发任何请求仍稳定周期出 IDR，降低该缺口对当前故障的重要性 |
| P0 | MMI 离开/返回，通话借用 | 资源请求参数、响应所有者、画面与音频反馈 | 车机始终明确要求手机持有，降低固定声明导致争抢的假设 |
| P1 | 源早起、同步、晚 5/30/60s | 提示配置、真实 SPS/IDR、一次性恢复结果、持续显示 | 控制请求成功但持续黑屏，排除“仅补 RECORD 即充分” |
| P1 | 静态画面与主动停源对照 | 源活性、IDR 年龄、背压、反馈 | 静态状态也真实断流，重新评估 Q4 保活需求 |
| P1 | 媒体/导航/通话交叠 | 流用途、格式、资源与重复停止事件 | 格式与资源均正常而仍无声，转查车机排播/路由 |

每轮固定源码提交、启动顺序、车辆/手机版本与冷/热启动；一次只改变一个策略。成功至少包含可见持续画面/可听声音及控制存活；没有接收统计就明确缺失。脱敏保留时间和消息类型，不提交原始身份、凭据或完整协议负载。

## 7. 本次交付与验证边界

只增加本调查文档。参考仓库完整浅克隆已完成；研究期间另以过滤克隆优先取得文档及 AutoKit 反编译代码，未执行其程序或固件。未复现 CPC 硬件实验、未安装 APK、未做 Q4 实车验证。本机执行 scripts/build-local.ps1 -NoDaemon 成功，828 项单元测试通过（0 failures / 0 errors），lintDebug 与 assembleDebug 完成，沿用仓库既有 lint baseline；git diff --check 通过。构建通过不提高上述跨设备假设的可信等级。
