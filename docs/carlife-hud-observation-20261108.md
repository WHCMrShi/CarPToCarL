# CarLife HUD 请求与观测（2026-11-08）

## 调研核验与看法

已阅读用户提供的 `investigate_carlife_hud.txt`，将其作为调研材料核对，没有采用其中“暂不实施/默认关闭”等行动建议。按用户本次要求主动尝试订阅，范围仅限采集日志。

核验来源：

- [官方接入指南 v2.3.0 的公开镜像](https://github.com/pitate7777777/CarLifeWirelessBox/blob/main/docs/CarLife%E6%8E%A5%E5%85%A5%E6%96%87%E6%A1%A3%E9%9B%86%E5%90%88/CarLife(DuerOS%20Classic)%E8%BD%A6%E6%9C%BA%E6%89%8B%E6%9C%BA%E4%BA%92%E8%81%94%E6%96%B9%E6%A1%88%E6%8E%A5%E5%85%A5%E8%AF%A6%E7%BB%86%E6%8C%87%E5%8D%97v2.3.0.pdf)，文档页码 98–100、149–153；已下载并抽取文本核对。
- [官方 SDK 保留仓库的 proto 目录](https://github.com/674809/carlife/tree/master/CarLife-Vehicle-Lib/LibSource/protobuf/proto)，核对下一转向、辅助诱导、订阅列表及子项定义。
- [SDK 控制消息实现](https://github.com/674809/carlife/blob/master/CarLife-Vehicle-Lib/LibSource/core/CTranRecvPackageProcess.cpp)，核对 SUBSCRIBE、START、STOP 的列表序列化和消息编号。

结论：调研的主要协议判断可信。`0x10030` 是结构化 TBT，`0x10047` 是辅助诱导，均可由本项目已有的 7240 控制通道截获。订阅模块编号为 0/1，与模块状态的 NAVI=2、EDOG=8 不同。旧 SDK 的转向字段 6 是 varint 时间；v2.3.0 改为 bytes 图标，必须按 wire type 区分。指南确实将辅助诱导订阅标为暂不支持，并将 cameraSpeed 单位写作米/秒；本次仍记录原始数值，不做显示单位换算。

需要补充：START/STOP 也携带订阅列表，不能仅发送空载荷。老 SDK 将 supportFlag 定义为 optional，且其发送实现没有填充；v2.3.0 指南则标为 required。本次 SUBSCRIBE 和 START 都显式发送 `moduleID + supportFlag=true`，兼容这两种声明。

“未见消息即手机不支持”“一定只有某个地图能发”不能仅凭旧日志证明。手机版本、导航是否真正启动、订阅应答、实际消息都需要分别观察。本次不调查奥迪原生导航能力，也不实现媒体替换。后续将导航信息映射到“正在播放”界面时，可依据本轮采集决定路名/距离/转向/图标如何组合及何时恢复音乐信息。

## 本次实现

- 每个 HU 会话收到版本匹配后，由现有心跳线程在下一拍请求 `0x18043`，模块为 `[0,1]`，各一次。
- 对 `0x10044` 检查列表数量、模块编号、支持标志和完整性；只 START 明确支持的目标模块。明确拒绝或畸形应答不会触发兜底。
- 若 5 秒完全没有应答，则尝试一次带完整列表的 `0x18045`，日志标注 `confirmed=false`。这是兼容性探测，不代表手机支持，更不代表已收到导航数据。不重试、不改变握手成功条件。会话关闭即结束该连接上的订阅，不在关闭路径增加阻塞发送。
- 不论订阅状态，始终接收并记录两类消息。记录 action、原始转向枚举、路名、总距离/剩余距离、旧版时间、辅助诱导类型和原始速度值；缺字段显示 null。
- 图标不解码、不保存、不写整段二进制；记录字节数、SHA-256、前 12 字节，便于识别格式和变化。路名最多 512 字符，控制字符转义，避免换行污染日志。
- 解析器检查命令大小、长度边界、varint 溢出、字段数量，跳过未知 varint/fixed32/fixed64/bytes 字段。畸形包记录 `parseError` 和已解析字段，不把解析异常抛给控制通道。`fields` 记录字段号/wire type/bytes 长度，用于定位版本差异；不是可重放报文。
- `HUD-DIAG` 进入独立早期/近期诊断归档，仍受容量轮转限制。每 30 秒打印接收计数，包括零条。模块状态另外记录 NAVI/EDOG 旁证。
- 诊断标识 `schema=20261108-v3`。没有改媒体发布、视频、导航控制或 PCM 策略；应用版本号不变。

## 车测与日志判读

1. 安装本次 APK，正常建立投影连接；确认日志包含 v3 标识。
2. 在被投影的 CarLife 环境内启动真实导航，保留路线规划、开始、转向更新和结束几个阶段，至少观察一分钟。若 CarLife 设置提供 HUD/仪表信息开关，可打开后比较。
3. 导出应用日志，搜索 `HUD-DIAG`：先看 `SUBSCRIBE ... sent=true`，再看 `SUBSCRIBE_DONE` 的模块和支持标志、`START ... confirmed=...`，最后看 `NEXT_TURN` / `ASSISTANT_GUIDE`。请求发送成功不等于手机支持；手机确认不等于有实际导航输出。
4. 若计数仍为 0，请保留完整导出：模块状态和超时应答同样有价值。无数据时暂不认定手机不支持；图标存在也暂不认定是 PNG，可用 head 特征核对。

新增测试覆盖请求报文字节、UTF-8/控制字符、图标与旧版时间、未知字段、畸形长度/varint、应答校验、订阅状态及单次超时兜底；诊断归档筛选已覆盖 HUD 标记。完整 `build-local.ps1 -NoDaemon -Offline -GradleArguments --max-workers=2` 通过：701 项单测、0 失败，lint 0 errors / 95 warnings，Debug APK 构建通过。未安装设备、未做 Q4 实车验证。

## 首次启动声明

另一个独立提交将 `BuildConfig.SKIP_STARTUP_DISCLAIMER` 默认设为 true，对 Debug/Release 及以后构建均生效。启动、恢复和自动连接的声明闸门统一跳过，不伪造用户接受记录；保留原有声明内容和查看代码。
