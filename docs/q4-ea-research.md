# Q4 External Accessory 调研：先确认谁能建立会话

2026-11-20。研究基线 `86f4d21`；只用既有脱敏记录、上游提交和公开的一手资料。没有新增发包、安装或实车实验。当前问题是“本车声明的 EA 协议原本服务于哪个客户端，什么证据能证明会话被接受”，不是设计车辆数据面板。

## 本车证据与上游旧实验

| 证据 | 能证明 | 不能证明 |
| --- | --- | --- |
| [Q4 现场记录及时间线](vehicle-tests/2026-11-05-q4/README.md)，16:36:42 | 本车识别信息里有三个协议名：`de.audi.mmiconnectdatatransfer`、`com.audi.cpp.eadata0`、`com.audi.cpp.eadata1`。不必再次实验来证明有声明。 | 每个协议的用途、哪个被第三方接受、实际业务字段。 |
| 同轮 16:36:43 至 17:21 | 本地尝试会话后，未记录预期的 EA 状态确认；虚拟 Wi-Fi 链路收到含 `cn.audi.connect` 的应用启动请求，当时未处理。面板只读刷新未实际发出业务 GET。 | 不能说车机明确返回了拒绝码，不能断言是授权、链路选择、请求路径或编码导致，更不能把应用启动请求当作已建链。 |
| 上游 `1b08b25`、`eb834c4`、`618af5a`、`fdbbd98`、`d318dd1`（仓库 [jjuugk/CarPToCarL](https://github.com/jjuugk/CarPToCarL)） | 上游做过面板、链路选择、状态观察与会话收尾；最后按要求移除面板和 EA 会话实现，区分链路层确认与应用层会话。 | 上游旧实现不是经 Audi 验证的参考客户端；删除提交也不证明 Q4 不支持 EA。不同车机、固件或轮次不能拼成一次成功交互。 |

## 有实现依据的部分

- Apple 在 [WWDC17 车厂 App 专节](https://developer.apple.com/videos/play/wwdc2017/719/?time=1095) 说明了车厂 iPhone App 与车辆直接用 External Accessory 通信，并解释车辆声明与签名 entitlement 的匹配如何决定车厂 App 的 CarPlay 展示资格。这不是 Audi 私有业务的规范，也不说明旧实验具体因何失败。
- [Apple External Accessory](https://developer.apple.com/documentation/externalaccessory) 和 [Apple EADemo](https://developer.apple.com/library/archive/samplecode/EADemo/Introduction/Intro.html) 给出的是 **iOS 应用端** 的参考路径：识别配件与协议，建立 EASession，使用其输入输出流。EADemo 是归档示例，不是 Android 可调用的框架、Audi 专用实现或 Q4 已验证样例。厂商协议的授权和内容须另有依据。
- [Audi Q4 官方车型资料](https://www.audi.com/en/electric-suvs-in-the-premium-compact-segment-the-audi-q4-e-tron-and-the-q4-sportback-e-tron-until-2026-13887/facts-and-figures-13891) 提供车型背景。普通 CarPlay 支持、手机应用联网可查看车辆信息，均不能证明本地 EA 已通。本轮未找到可核验的 Q4 Android 端实现或 Audi 公开的这三个协议的会话契约。

## 独立评估和下一步

第二轮已核对中国区客户端身份。Apple [按产品 ID 查询](https://itunes.apple.com/lookup?country=cn&id=1119081688) 返回一条「一汽奥迪」记录，Bundle ID 是 `com.fawvw.audisuper`；[按车机请求标识查询](https://itunes.apple.com/lookup?country=cn&bundleId=cn.audi.connect) 返回零条。主审通过 Apple 接口独立复查了两个结果。它们只反映本次公开上架查询，不证明老应用从未存在，也不能认定两个标识是同一应用或后继关系。[Audi 中国互联说明](https://www.audi.cn/zh/audi_brand/technology/Audi_connect_technology.html) 提供官方 App 入口；研究员在该说明和[中国区产品页](https://apps.apple.com/cn/app/id1119081688)未找到针对这组协议的本地 EA 兼容声明。

优先核对本车点名的应用、市场和 MMI 适配，而非猜测三个协议的分工。可接受的进展证据是 Audi 对该配置和官方客户端的明确说明，或车主在停车状态使用厂商支持的客户端所得的脱敏正例。记录客户端/系统/MMI 版本、连接方式、事件次序；分开记录“提示打开 App”“会话被接受”“应用显示了可归属于本地链路的新数据”。仅有界面或云端数值不满足后两项。

在没有受支持的接入方式或正例前，不恢复旧模块，不新增超时重试、业务端点或 UI，不把其他客户端的身份当作本应用身份。下一阶段的门槛是确认会话层，而不是实现最终的电量和里程需求。

## 跨市场、跨年代的应用侧核查

不以中国区当前上架或仍在维护作为入选条件。下面按**数据方向、实际承载、可迁移证据**筛选，而非靠 App 名称推断。

| 候选 | 可核验事实 | 对本项目的意义 |
| --- | --- | --- |
| Audi Sport Performance，2016 TT RS | [Audi 发布资料](https://www.audi.com/en/audi-tt-rs-coupe-and-tt-rs-roadster-until-2023-the-sporty-vanguard-of-the-model-series-6673/infotainment-and-audi-connect-6686) 在 Performance app 一节写明 iOS 和 Android 版通过 Wi-Fi 读取车载电子系统的遥测，同节单独介绍 USB 手机投屏。 | 是“车到手机”的强正例；不是 Q4、不是 EA 协议证明，也不能推导消息格式。 |
| Audi Sport Performance，2023 R8 GT | [德国 App Store](https://apps.apple.com/de/app/audi-sport-performance/id6445920363) 和 [Google Play](https://play.google.com/store/apps/details?id=de.audi.sport.gmbh.performance) 列出 R8 GT 车型和车辆传感器记录。 | 可跨平台对照功能；未披露承载。不能因为同名就拿 TT RS 的 Wi-Fi 描述补足这一版本。 |
| Audi MMI connect、历史 myAudi | 上述 TT RS 资料另述 MMI connect 的手机到 MMI 的 Wi-Fi 媒体传输；[Audi 2020 RS Q8 配置册](https://uploads.audi-mediacenter.com/system/production/uploaded_files/16506/file/332979ce3a4ee0010c50c1503d08c48299d2861b/Preisliste_RS_Q8_03_20.pdf?disposition=attachment) 将 myAudi 的部分娱乐功能与 WLAN 相联；[Audi 隐私说明](https://www.audi.com/en/data-protection-notice-regarding-audi-connect-17148) 则将远程 Vehicle Status 描述为经 Audi 系统交付。 | App 里同时有本地功能和云数据，不代表状态经本地 EA 取得。优先找历史应用公开声明，不复现媒体共享。 |
| Audi connect plug and play | [Audi 英国](https://www.audi.co.uk/en/owners/audi-connect/plug-and-play/) 和 [Audi 法国](https://www.audi.fr/fr/univers-myaudi/les-fonctionnalites/audi-connect/connect-plug-and-play/) 描述 OBD DataPlug 与手机蓝牙连接。 | 本地车数据正例，但依赖外部硬件；不作为 MMI EA 实现依据。 |

架构评估：跨平台官方实例值得研究，但本地 Wi-Fi、可见的车型数据和 EA 是三个不同命题。目前不对任何候选作“已匹配 Q4 协议”的标记。Android 端先建模**有证据的客户端职责**；进入消息级 mock 的依据应是可核验的应用协议声明和受支持的会话语义，而不是商店 ID、相似品牌名或其他车型的传输方式。

定向复核：用三个完整协议名分别交叉查找 App 属性清单、EASession、发行资料和公开源码。命中项包括车机声明、第三方代码中的字符串，以及同源的[上游说明](https://github.com/jjuugk/CarPToCarL/blob/4a415c2f6b53222ab1613df496f18e39786a8ecd/README.md)，没有取得发行方的「App 版本 → Q4 协议名」声明。旧 MMI connect 商店快照未取得，结论是**映射未知**，而非“不支持”。下一项取证只收能新增这种映射的材料或受支持会话的正例；不把重复转载当独立佐证。

### 历史 MMI connect iOS：已补到商店原页，仍缺包内声明

EA 研究员随后取得同一 Apple 条目 `570608111` 的三个网页归档，补上了上一轮的快照缺口：

| 来源 | 版本锚点 | 证据范围 |
| --- | --- | --- |
| [英区商店快照](https://web.archive.org/web/20140629120527/https://itunes.apple.com/gb/app/mmi-connect/id570608111?mt=8) | 1.2，2014-04-10 | Details 中的版本与更新日期。 |
| [德区商店快照](https://web.archive.org/web/20150602172807/https://itunes.apple.com/de/app/audi-mmi-connect/id570608111?mt=8) | 1.4.0，2015-04-28 | Audi 名下的同一商店条目。 |
| [美区商店快照](https://web.archive.org/web/20180711162448/https://itunes.apple.com/us/app/mmi-connect/id570608111) | 2.9.0，2017-12-18；2.9.3，2018-07-08 | Version History 中 2.9.0 明确提到 MMI Wi-Fi；2.9.3 是快照所列最新版本，不能认定为最终版。 |

**版本判定：** 没有取得上述版本主应用的 Info.plist。商店页本身不列出完整的应用协议清单，不能据此断言某版有或没有声明。**会话判定：** 没有找到同一版本、精确协议名、对端接受本地会话三者可对应的公开记录。[Audi of America 技术通告 2039054/1，第 1 页](https://static.nhtsa.gov/odi/tsbs/2014/SB-10066418-2280.pdf) 将 A3 MIB High 的 MMI connect 本地音乐路径明确描述为 Wi-Fi UPnP，因此不能用音乐播放成功代替 EA 成功证据；这也不能排除该 App 的其他功能使用 EA。

**下一步：** 优先取来源可核验的 iOS 2.9.0 或 2.9.3 原始主应用 Info.plist（早期版本亦有价值）。同时记录来源、版本和构建号，逐字核对 UISupportedExternalAccessoryProtocols 数组；无需运行 App 或解密可执行文件。仅命中一个包能证明该构建声明过，不能证明它是首个声明版本，更不能证明该会话在 Q4 被接受。未取得上述材料，本轮没有启动二进制分析或新实车实验。

### 新证据：myAudi 5.9 用户提供样本（不是旧版 MMI connect 或国区一汽奥迪）

离线只读 ZIP、plist 和二进制符号检查。主包 `Payload/OneTouch.app/Info.plist`：标识 `de.audi.myaudimobileassistant`，版本 5.9，构建号 `46783.261023093001`。IPA SHA-256：`1c50dc661210e5c3e426eae9ee27636a18214d4477426e7cdae3a0b3d2602973`。哈希标识该样本，不认证发行来源；未验证签名信任链。没有上传包或二进制、没有运行 App。

| 本车声明 | 主 Info.plist 的 UISupportedExternalAccessoryProtocols | 主可执行文件内 XML entitlement：com.apple.developer.carplay-protocols |
| --- | --- | --- |
| de.audi.mmiconnectdatatransfer | 有 | 未列出 |
| com.audi.cpp.eadata0 | 有 | 有 |
| com.audi.cpp.eadata1 | 有 | 有 |

这修正的是“没有找到任何 App 端映射”的结论：现在已取得一个具体 myAudi 构建的直接声明，**不是** Q4 会话正例。最值得区分的实现线索是：主可执行文件有上述三个名称；内嵌 `Frameworks/PDICarKit.framework/PDICarKit` 同时出现两个 eadata 名称、EAAccessoryManager、EASession 和创建指定协议会话的选择子。这比单靠资源名称更强，但共现不能代替调用图，更不能给两个通道擅自分配读、写或某个业务字段。

另在主程序中检出 `GetVehicleStatusFuelLevelUseCase` 等油量业务符号，英文资源有低油量及低电量提示。它们尚未与 PDICarKit 连接起来。包也含推送服务与远程通知能力；不能把 A4L 画面归因到某个 EA 协议、此特定 App 或后台推送。包内配置 `marketSupportsAutomaker: false` 也不代表全球、所有账号或本车的运行态。接下来优先查明车辆状态业务的数据来源与 EA 接入组件是否实际相连，或取得受支持客户端的本车正例；不根据提示文字设计车端交互。

### 收窄里程碑：真实配件的 EA 输入流第一次返回正字节数

本轮不调查业务字段。样本内 PDICarKit 0.7.1 的选择子和诊断文字可与 [Apple EA 连接生命周期](https://developer.apple.com/library/archive/featuredarticles/ExternalAccessoryPT/Articles/Connecting.html) 交叉核验；它们属于静态程序内容，不是 Q4 上发生过的事件。

| 阶段 | 框架里的证据 | 本车上能接受的证据 |
| --- | --- | --- |
| 看见配件与协议 | connectedAccessories、protocolStrings、协议匹配诊断 | 真实配件在本次连接中向官方客户端显示相应完整协议名。 |
| 客户端申请会话 | EASession 初始化选择子，创建与失败的不同诊断 | 该协议的初始化返回非空或等价成功事件；仅开始尝试不算。 |
| 输入流打开 | 分方向的流打开诊断、流事件处理 | 同一会话输入方向打开完成。输出打开另记，不作为收到数据的条件或证据。 |
| 收到数据 | read:maxLength:、接收计数诊断及无可读字节分支 | 同一真实会话的输入读取实际返回 n > 0。仅可读通知、本地写成功、模拟配件事件都不算。 |

Apple 公开模型是系统使配件可见，客户端为配件的协议请求会话；不能据此还原底层哪个设备先发起。框架还有自动连接与排队任务相关文字，但没有运行记录证明这是唯一触发条件，或证明 eadata0/1 必须按特定顺序启用。对两个协议分别记账，任一协议取得可归属的首个非空输入即完成第一阶段，不要求双协议、双向成功或业务解码。

下一项对照：停车状态下用获支持的 iPhone 和官方 myAudi，记录车型/MMI、iOS/App 版本、CarPlay 就绪与正常进入车厂 App 的时间；仅对照已有诊断，记录匿名会话、协议、方向和字节数，不留负载。若只能看到开流，就报告“开流，未证实首读”；若系统不提供这些事件，应记“不可观测”，不是失败。本轮未启动 App、连接车辆或取得实车字节。

### 代码端核查：队列自动连接不等于已发送请求

对象为该 IPA 的 PDICarKit 0.7.1，框架可执行文件 SHA-256 为 `dde637a16927385c1d2c15daaeb4aa5c4b4fc939e6a07857ab911178def27d4c`。函数名按诊断自标识判读；主审独立复核了日志引用、函数边界及下列三条 ARM64 直接调用（地址为本构建未滑移地址）。

1. 入队处理的一条分支调用任务激活状态评估（`0x1bbff4 → 0x1aff24`）。另有状态更新路径也会触发评估；最初产生任务的外部事件未定。附件连接回调有单独入口，不能据此认定它产生了任务。
2. 目标日志的引用在评估函数内（`0x1b0b90`），该分支调用自动连接评估（`0x1b0bf4 → 0x1b9bec`），立即连接分支才调用会话启动（`0x1ba690 → 0x1b710c`）。日志不是新任务、已建会话或发包凭证。
3. 框架有同时引用两个完整协议名的函数；到会话构造参数的数据流未贯通，不能判断选择者、选择时点或优先级。客户端框架调用 Apple EASession 的带配件与协议参数的初始化器；启动与构造之间有动态分派，并非已证实的无条件同步链。
4. 已定位的会话前片段是本地队列与 API 操作，不是线上结构化数据。不能据此推导系统内部是否另有协商帧。会话对象非空后有取流、开流及分别处理读取和写入的代码；没有把具体序列化对象、某次先行写入及首次非空读取关联至同一任务和协议。首包是否为响应或主动推送仍未知，不以反汇编地址顺序代替事件顺序。

### 从系统调用回查协议实参与输入输出缓冲区

同一 PDICarKit 样本。主审复核两处对象构造调用、EA 初始化选择子及参数传递、缓冲区到流写入的直接调用边、输入读取入口与响应不匹配诊断。以下是分层的调用关系，不是一次实车事件时间线。

- **构造与选择不是同一步。** 两个完整协议名各自进入同一构造函数，结果保存于同一持有者的不同字段。到 Apple 初始化器处，框架转交调用方已传入的协议字符串，未按后缀再次判断。不能把两处初始化调用点各当成一条协议。尚未联通任务调度、动态工厂与这两个对象的具体选择条件。
- **待写的内容已经是字节。** 至少一个生产路径把诊断标为 TLS 加密输出的字节追加到连接的 Data，写处理取其范围调用输出流；返回后按实际写入数消耗前缀。这里的完成是本地字节写入进度，不是业务回应。没发现这次流写本身在等待输入；也没有证明这是该连接的第一次写，或定位到首次写的上层任务与消息对象。
- **输入可独立调度，上层可以匹配响应。** 从输入可读事件能走到 read，亦有定时检查；这条调用边不以某一输出任务刚完成为前提。另一方面，在一个 HTTP 响应处理分支里会核对事务 ID 和活动任务。两者并不矛盾：响应也能由异步可读事件送达。没有证据将某条 eadata 会话的首次非空 read、某次先行 write 和该 HTTP 事务关联，故首批输入不能判为响应，也不能判为主动推送。传输记录亦不能直接当成第一条业务消息。
