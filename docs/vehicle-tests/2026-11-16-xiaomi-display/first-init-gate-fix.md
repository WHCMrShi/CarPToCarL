# 统一首轮初始化、65617 响应与兼容性自审

日期：2026-11-16；开发基线 `3573bd218651`。依据[我方方案可行性分析](app-only-feasibility.md)，用户明确授权实施统一首轮入口和65617响应，禁止新增完整会话重建，要求各手机共用逻辑。没有修改默认配置、版本号或厂商APK。

业务与回归提交 `ff2034e`；以下以该提交的符号定位，不混用基线bcef70f行号。文档另行提交。

## 实现行为

`CarLifeHuClient.sendEncoderInit` 是唯一98311写出入口，始终检查运行状态和版本匹配。首条还必须满足“98386本地写成功、唯一首轮任务的本地间隔已结束”。force只绕过已有5秒去重，不能越过首轮条件；被拦的恢复请求不消耗首轮标志或去重窗口，版本与设备信息/特征请求到达后由首轮推进器统一续发。

`scheduleFirstEncoderInit` 只允许当前会话一个首轮任务。设备信息交换可主动触发，不依赖CarPlay就绪、65617或65570，保留CarLife预连接。65617是手机请求：已版本匹配时立即写98386；提前请求在版本匹配后补响应。重复请求仍响应，但不会另启首轮任务、缩短或重置本地间隔。接收线程不sleep等待后续事件。

所有98386收敛到`pushFeatureConfig`，它在版本未匹配时不写，写成功才标记featureConfigSent。直通键及其值保持既有`DISABLE_RESOLUTION_OPTION=1`。首轮成功前，热更新只更新申请；更新与编码快照共用锁，发送签名与报文使用同一份宽高/帧率。Q4已知1412×654仍在HU创建前预置。

有序设置保留2秒本地间隔；既有非有序设置保留1.2秒前段+1.2秒后段，但也必须经过首轮门禁。该设置不再允许恢复入口抢先初始化。没有品牌判断、品牌白名单或独立状态机。首轮任务在停止/新代次时失效；等待在锁外，50ms分段检查，捕获中断并恢复中断标志。

原3秒版本等待后继续发编码申请的旁路被移除，版本协商仍由既有15秒预算处理。后续恢复和稳定后补发保留原策略，本次不新增全局重建，也不因65544或本地写成功就宣称目标编码器已重建或直通已生效。

## 对照上游的自审

重新fetch上游main，仍为`4e42a0d23ee411a1ac4d2ffa44feef9d9fca726d`，重点核对[上游相关提交](https://github.com/jjuugk/CarPToCarL/commit/4d9fbde9fdd528b6f73553f78b8d833934f1d180)中CarLifeHuClient的pending、flushPendingEncoderInit、sendEncoderInit及canSendOrderedEncoderInit。

| 核对项 | 结论与本次处理 |
| --- | --- |
| 所有恢复入口统一拦截 | 采纳。只保留一处98311实际写出；65551、视频重连、无帧恢复、热更新、关键帧请求、探测、稳定后补发都不能绕过首轮 |
| 待条件具备后续发 | 采纳思想。设备信息/65617和版本事件推进唯一首轮任务；首轮统一强制发送，不让多个pending force变成多个首包 |
| 65570设置featureConfigAcked后flush | 不采纳。这是麦克风事件，缺失时可能一直不推进，也会在无关事件到达时错误放行 |
| 非有序模式直接绕过所有前置条件 | 不采纳。保留其等待节奏，版本与配置先于初始化的条件对所有模式生效 |
| 写成功后才更新首轮和去重状态 | 采纳并补失败回归。旧本分支先记录签名/时间再写，现改为仅写成功后记录 |
| 旧任务、重复设备信息和重复请求 | 首轮任务按代次隔离，停止后失效；重复事件不新建任务，不重置间隔；锁不跨sleep持有 |
| 仅缓存“配置已发送”会不会伪装为ACK | 字段、日志与验收文档都明确仅本地写成功；不声明远端应用完成 |

完成实现后按上述入口、锁顺序、失败分支、代次与快照重新阅读完整diff。自审补入停止、中断与编码写失败回归。未发现仍可直接写98311的旁路，也没有将原始厂商源码、APK、身份或完整协议负载入库。

## OPPO 实际引擎只读核对

本机ADB存在已授权OPPO PGU110。仅只读枚举包和复制引擎APK至忽略目录，没有为取证启动、修改或重签引擎。包管理器按包名查询未返回完整引擎信息，版本依据从实际安装路径复制的APK manifest核对，而非假定它与小米版本一致。

| 项目 | 取证值 |
| --- | --- |
| 包名 | com.baidu.carlife.oppo |
| versionName / versionCode | 8.6.8 / 260 |
| 大小 | 82,984,387 bytes |
| APK SHA-256 | 34ba512ff2b5d88cb8a8870f3e1c86771d7452a3354bfec62aae69ec2348c781 |
| apksigner | 验证通过；Baidu Carlife证书，SHA-256为509e03e376b676b25adc41c3dbda6dfa5d0ff12dba1c8c0ca32ad110eea78667 |

签名工具提示部分META-INF版本标记未受保护，不因此修改文件。JADX单类读取未完成，未据其输出作结论；以下直接以固定Build Tools 34.0.0的dexdump核对实际Dalvik指令：

- CommonParams定义98386为HU_FEATURE_CONFIG_RESPONSE、65617为MD_FEATURE_CONFIG_REQUEST、65570为MIC_RECORD_WAKEUP_START。
- FeatureConfigManager.sendFeatureConfigRequest明确构造0x10051即65617；request调用发送和定时器。特征Handler识别0x18052即98386，解析后执行配置；excute在DISABLE_RESOLUTION_OPTION分支按value==1写Recorder.useVehicleResolution。
- VideoMsgHandler的视频Handler先检查mInited，已初始化则回Recorder已有尺寸/当前帧率并返回；未初始化路径调用initMediaCodec50。OPPO同样不能据重复98311宣称重建。

因此，新响应和门禁采用的是实际OPPO也支持的同一协议。旧OPPO起播可由MD_INFO主动推进，仍不等CarPlay、65617或麦克风事件；既有协议3.0、身份、CONNECT_TYPE、音频、蓝牙、Wi-Fi与预连接不变。正常有序路径仍保留配置后2秒预算；若旧起播依赖视频看门狗约0.9秒抢先发申请，则现在会被统一首轮间隔挡住，可能推迟首包，但不把它记为兼容性失败或成功的实车结论。

静态核对只能支持协议兼容判断，不能保证系统调度、实际画面/声音和车机行为无回归。需分别验证CarLife先起和CarPlay先连。已有11月16日早间OPPO/Q4材料属于旧源码，不作为本修复的安装后车测。

## 回归与交付

FeatureDispatchTest覆盖无65617主动起播、首条1412×654、提前/重复/迟到65617、设备信息早于版本、重复设备信息、两种等待设置的恢复/force/65551/关键帧阻挡、热更新快照、配置与编码写失败、停止/中断及旧代次。SourceKeyFrameInitTest继续检查首轮之后的恢复去重、IDR取消和两种设置共用门禁。

第一遍构建的两项新报文断言误用了HU默认20fps，夹具却预期60fps；已让夹具明确请求60，未改业务帧率。随后1082项单测与完整构建通过；自审补入编码写失败回归后，再对最终文件运行完整`build-local.ps1 -NoDaemon`：1083项JVM单测，0 failures、0 errors、0 skipped；FeatureDispatchTest共16项全过。lintDebug通过，0 errors、105 warnings、2 information；assembleDebug成功，`git diff --check`通过。

用户随后明确授权交付APK并直接安装至手机。安装应使用保留数据的更新方式，不自动启动或连接车机；安装成功仍不是OPPO/Q4连接、画面和音频验证。交付APK及厂商制品均不入库。

已交付忽略目录中的`CarPToCarL-1.2.31-q4-first-init-ff2034e-debug.apk`，包名io.github.shaonialife.carptocar，versionName=1.2.31-q4-debug、versionCode=10231，apksigner验证通过。APK内构建标识为`3573bd218651-dirty`，对应在该基线上完成并通过门禁、后来提交为ff2034e的业务源码；文件名中的ff2034e为源码定位，不冒充构建时HEAD。

APK SHA-256：`3d49c3da2fb0d773094eb2779875f144b84f2ee8da12f68ecff182ea9bba03a1`。经用户授权，以ADB install -r更新OPPO PGU110，返回Success；包管理器lastUpdateTime为2026-11-16 15:46:33（手机本地时间），手机已安装base.apk的SHA-256与交付文件完全相同。未卸载、未清除数据、未自动启动应用或连接车机。当前结论为“已安装、尚未连接/路测验证”。
