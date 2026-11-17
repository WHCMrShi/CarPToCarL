# PASCD 车速与挡位接入（2026-11-17）

用户明确要求将车速、挡位分别提交，统一由“使用车机 GPS”开关控制；数据处理必须有证据，不猜测 CarLife 的解析方式。开发基线 f88a606；实施期间并入其他任务的独立文案提交 f1c9c37，车速提交为 ca91135。默认开关值仍为关闭，已保存选择不变。业务提交与本文档分开。

## 协议证据与边界

| 项目 | 已复核的依据 | 实现采用的范围 |
| --- | --- | --- |
| PASCD 布局 | [DiPlay 发送方说明](https://github.com/Boring206/DiPlay-T8/blob/t8-api27/docs/BYD_NAVIGATION.md)，Wheel speed for tunnels 节；Q4 脱敏归档中同形态样本 | 起始秒数、P/R/N/D、样本数、成对的相对时间和速度 m/s；不解释 C 和其后的未知整数字段 |
| 速度单位 | 百度署名接入指南第12章 12.1.1.1，PDF页144 | 整数 km/h；m/s ×3.6 后取最近整数，这是本应用的量化策略，不宣称是引擎内部算法 |
| 速度 protobuf | OPPO 8.6.8 的 CarlifeCarSpeed.dynamicMethod 字节码：tag 8 调 readInt32，tag 16 调 readUInt64 | field1=速度；field2 时间戳可选，本次省略，不把 PASCD 单调秒数冒充日历时间 |
| 挡位 | 指南12.1.1.6，PDF页146–147；OPPO CarlifeGearInfo 字节码 tag8/readInt32 | CarLife N/P/D/L/R=1/2/3/4/5；仅映射源端有证据的 P/R/N/D，其他符号不猜测为 D 或 P |
| 传输通道 | SDK CTranRecvPackageProcess::sendCmdCarVelocity / cmdCarDataGear 都调用 writeCmdData；OPPO 常量及接收分支一致 | 0x1800F 与 0x18029 均走现有命令通道，不在9440双发 |
| 能力响应 | 指南12.1.1.8及 OPPO CarlifeVehicleInfoList 字节码 | field1=数量，field2=重复 VehicleInfo；moduleID=1，flag=2，frequency=3；只声明观察到的运动模块 |
| 订阅启停 | 指南12.1.1.9/10是列表；OPPO CarDataSubscribeManager.sendCarDataControlMsgToHU 实际发送单个 VehicleInfo | 仅采用目标实际发送的单模块载荷，不兼容旧列表或把 cnt 当作 moduleID；模块速度=1、挡位=4，与 HUD 模块无关 |

指南和 SDK 固定来源：[qiyunjzz/carlifeLIB fdf8310bf1b6f0c4c247d848793832c016607561](https://github.com/qiyunjzz/carlifeLIB/tree/fdf8310bf1b6f0c4c247d848793832c016607561)。这是历史百度材料的公开镜像，不作为现行官方门户版本声明。指南部分表格把 STOP 写作0x18034/HU→MD；目标 OPPO 字节码实际发送0x10034，采用目标实现，不照搬表格笔误。旧 proto 将 field2 命名为 bool supportFlag；目标引擎 field2 是 int flag，另有 field4 supportFlag；本次使用共同编码值1，不擅自赋予其他 flag 值含义。

目标分析制品：本地 `com.baidu.carlife.oppo` 8.6.8 / versionCode260，APK SHA-256 `34ba512ff2b5d88cb8a8870f3e1c86771d7452a3354bfec62aae69ec2348c781`。用固定 Build Tools34.0.0 dexdump 核对生成的 protobuf 与 CarDataSubscribeManager，避免仅依赖反编译伪代码。APK、字节码及厂商原文只保留于忽略目录 `.tools/`，不入库。

按用户进一步要求，冲突时以 OPPO 的实际发送/解析为标准，不另行核验小米和 VIVO。OPPO CarlifeConnectHsgHandler.handleCarDrivingStatus 字节码也明确分支到1/2与3/4/5两组；其他整数走错误日志。未发现与上述标准枚举冲突的接收分支。未知输入符号不能按枚举顺序、其他品牌SDK整数或驾驶状态反推为某个挡位。

OPPO 接收0x1800F后解析车速并更新内部状态；`isMixSubscribeInfo(2)` 且未禁用订阅时，会把 `velocityData` 交给 MixActionDispatcher。该分支不存在“只有经纬度有效才能解析车速”的门禁。但是否有上层订阅、是否到达高德、是否用于定位融合，均未由本次静态分析证明。该制品也不自动等于用户手机当前安装版本。

PASCD 字段依据来自发送方实现及同形态车辆样本，不是 Apple 对 Q4 字段的官方确认。本次采用该发送方说明作为源侧解释依据；按用户要求，不再寻求Q4对照材料。未知字段不转换；旧 NmeaLocation 将未知字段当卫星数的行为删除。日志中的 fix=0 是本地无坐标标记，不能解释为车辆报告GPS失效。

## 开关、时序及失效策略

- 开关开启后，下一条有效新 PASCD 经原有有界命令工作队列下发；不依赖GPS坐标，不生成(0,0)，也不借用旧坐标。
- 没有收到车辆订阅控制时，沿SDK独立发送接口转发新样本；收到明确STOP则暂停相应模块，START恢复。目标引擎接收速度并更新内部状态的分支不要求先订阅；上层转交仍取决于上层订阅，不以发送成功代替消费证明。
- 能力查询先于车辆样本时先答空列表，有有效样本后更新答复；关闭开关清空已观察能力。只申报速度和已识别挡位，不由PASCD推断GPS、陀螺仪或加速度能力。
- 关闭开关、快速关开、CarPlay源更换、CarLife目标更换都会阻止旧样本写出。数据写锁内再次检查，避免等锁期间失效后仍写。
- 每条PASCD取批内最后一个速度，按m/s ×3.6转为km/h后取最近整数；不平滑、不平均。末项选择是将批量输入适配为单个当前速度的应用策略，不宣称为引擎算法。
- 只校验完整校验和、样本数与成对字段结构、所选速度能解析为有限数且量化结果可由int32表示。无可编码值时拒绝，不替换成0。未使用的时间、偏移及未知字段不参与过滤。
- 不设车辆速度上限、符号限制、样本数上限、时延阈值、去重或限频。相同速度和挡位也随来报发送；STOP/START分别控制速度和挡位。未知挡位不影响有效速度，也不重复旧挡位；不定时补采样。
- 不做跨时钟推算，省略目标可选时间戳。停止发送不等于引擎清空内部缓存，不发送假0车速或假P挡来清除缓存。
- 车辆先连接：CarLife未就绪时丢弃样本，目标就绪后从下一条新样本开始。CarLife先启动：可先处理能力查询，车辆样本到达后更新能力并发送。两条路径均不回放缓存运动样本，不改变原认证/SETUP/RECORD时序。

## 最小化自审

原实现中的100m/s、32样本、批内10秒、1500ms过期、900ms节流、非负限制和强制C/0，没有协议规定或Q4测量支撑。标作“保护策略”不足以构成实现依据，本次删除；时间去重/倒退/跨时钟迟到估计与旧版订阅列表兼容一并移除，不引入替代阈值或兜底。

保留处理依据：源侧说明m/s及目标指南km/h决定×3.6；OPPO实际readInt32确定整数类型；最近整数使绝对量化误差不超过0.5km/h，是数学选择，并非协议指定算法。NMEA校验和及计数校验来自报文格式，有限值和int32范围来自目标表示能力。开关与会话归属检查依据用户要求及异步队列生命周期，STOP/START依据目标实际控制消息。

源侧单位依据DiPlay发送方说明；Q4同形态样本不作为单位确认依据。按用户要求不再寻求Q4参考材料，也不以此阻塞实现。量化误差不包含源传感器误差。

## 本机验证与实车待验

车速提交 ca91135：1101项单测通过（0失败、0错误、0跳过），lint 0 errors/106 warnings，Debug APK通过。挡位提交 cd6745b：最终1105项单测通过（0失败、0错误、0跳过），lint仍为0 errors/106 warnings，Debug APK通过。以上为自审前的历史验证，不能作为保留原布局限制和限频策略的依据。本轮没有设备安装或路测。

验证使用仓库 `build-local.ps1` 的默认三个任务。发现另一个任务同时构建相同工作区后，用忽略目录中的init脚本将输出隔离至 `.tools/pascd-build/`，项目缓存隔离至 `.tools/pascd-gradle-project-cache/`，关闭Kotlin增量编译以排除受干扰产物。未修改仓库构建默认配置；实际验证APK在 `.tools/pascd-build/app/outputs/apk/debug/app-debug.apk`，不是脚本末尾固定打印的默认APK路径。早期共享输出构建失败不计为通过，以上结果来自隔离后的完整成功构建。

本次没有安装或路测，未验证上层地图采用。按用户要求，不再安排Q4对照取证。日志中的本地写入仅表示命令已提交，不能描述为地图已经消费。

用户最终限定范围：停止扩展规范研究，仅处理Q4日志已收到的PASCD车速和挡位，沿用已列明的单位与编码依据，不添加车型推断、额外过滤或兜底，也不索要新的对照材料。

自审修正提交82b24c0：1104项单测通过（0失败/错误/跳过），lint按既有baseline报告0 errors/106 warnings，Debug APK构建通过；未安装、未路测。覆盖重复值随来报发送、无任意速度/数量/未知字段限制，以及模块独立启停和开关/会话失效。
