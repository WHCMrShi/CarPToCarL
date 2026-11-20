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
