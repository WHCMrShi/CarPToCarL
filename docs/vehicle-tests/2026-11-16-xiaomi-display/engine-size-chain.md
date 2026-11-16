# 小米 CarLife 引擎：98311 到 MediaCodec.configure 的尺寸链路

2026-11-16，工作树 `3196115`。继续用户授权的原因调查，不改业务代码、默认值、版本或手机配置。实车输入仍为 `0e363ad07e46` 的 13:42:39 导出；CarWith 版本由用户确认是 4.0.9-20260629。

## 本轮已确定什么

取得独立 `com.baidu.carlife.xiaomi` 引擎 8.8.4（273），已经追通 98386 特征处理、98311 首次/重复处理、厂商回调、最终 MediaFormat、MediaCodec.configure、输入 Surface 及 CastConfig。**这比上一轮只分析 CarWith 主包更进一步，但还没有证明车友安装的独立引擎就是 8.8.4。** 不把“主包同版”或相似日志当作引擎版本证明。

在该引擎中，有两个明确的尺寸变化点和一个让新申请不再生效的分支：

- 首次初始化且 `useVehicleResolution=false` 时，`Recorder.initMediaCodec50` 按申请宽度分档；1412 落入 1280≤width<1920，非宽屏档得到 1280×720。
- 随后 `MediaFormatUtils.setVendorEncoderParams` 接纳 CarWith 回调的正 width/height，直接覆盖将送入编码器的 MediaFormat。**即使 useVehicleResolution=true，这里也不禁止覆盖。**
- `VideoMsgHandler.mInited=true` 时，再来 98311 仅回已有尺寸/当前帧率并返回，不重新解析申请、不再走上述编码器创建过程。重发本身不能修复既有档位。

**实车归因收窄**：本样本既有 65544=1280×720，又有 SPS=1280×720。若运行的是相同引擎、相同普通初始化路径，它更符合“首次已在旧/默认 720p 档，随后请求被初始化状态短路”，或者“首次特征未实际生效，且/或厂商覆盖在非直通状态下同步了 720p”。不能把它直接归为“特征已生效、全新按 1412×654 初始化，仅厂商回调改为 720p”：后者在此实现通常会留下 65544=1412×654、SPS=1280×720 的差异，见下表。

这定位了代码中改变尺寸和锁住尺寸的位置；缺少实车引擎版本、首次初始化状态、特征赋值及回调输入/输出值，仍不能唯一选定该手机最初落入 720p 的分支。尤其不能宣称已实车证明硬件不支持 1412×654。

## 引擎制品与分析方法

通过[公开版本页](https://mobileosupdates.com/apps/com.baidu.carlife.xiaomi/273)正常下载到 `.tools/carwith-engine-20261116/`。本地 aapt 与 apksigner 结果：

| 项 | 结果 |
| --- | --- |
| 包名 | com.baidu.carlife.xiaomi |
| 版本 | 8.8.4 / versionCode 273 |
| 文件大小 | 43,994,664 bytes |
| APK SHA-256 | 00df4aaacbbc5837f3a0ef17fc55dfabf038596dd27b652cfb23c9722770a994 |
| 签名验证 | apksigner verify 通过，证书 DN 为 Baidu Carlife / Baidu |
| 证书 SHA-256 | 509e03e376b676b25adc41c3dbda6dfa5d0ff12dba1c8c0ca32ad110eea78667 |

签名工具同时给出未保护 META-INF 版本标记的提示；没有因此修改 APK。公开镜像包不是车友设备导出的包，不宣称两者字节相同，也不只凭证书主题文本宣称已从官方渠道验证发布来源。

JADX 1.5.6 来自[工具官方发布](https://github.com/skylot/jadx/releases/tag/v1.5.6)，只在忽略目录生成阅读材料。整包反编译报告 135 处错误，不据其全包结果宣称无误；本次关键分支另用固定 Android Build Tools 34.0.0 的 dexdump 逐条复核，包括 mInited 短路、特征赋值、宽度分档、厂商覆盖和 configure 前的条件回写。相关目标方法未出现无法反编译占位。APK、dex、工具、反汇编及厂商源码均不入库、不安装、不运行。

以下引用采用类/方法及制品内位置，不混作本仓库 bcef70f 的源码行号。

## 98386：键被支持，但没有 65570 ACK

`FeatureConfigManager` 的消息处理器监听 98386，以 `CarlifeFeatureConfigList.parseFrom` 解析列表，然后调用 `excute()`。遇到 `DISABLE_RESOLUTION_OPTION` 时，明确赋值 `Recorder.getInstance().useVehicleResolution = (value == 1)`；未见把该键当作不支持的配置。`uninit()` 会将此标志置回 false。

处理成功后该处理器停止特征请求定时器、根据加密开关派发内部连接事件并记录成功状态；没有在这里发送 65570 作为配置 ACK。目标引擎自身 `CommonParams.MSG_CMD_MIC_RECORD_WAKEUP_START=65570`，直接印证上一轮依据旧 SDK 得出的消息身份纠正。发送端看到该事件仍不能证明特征赋值已经完成。

不同 Handler 的注册与消息队列、实际连接清理时序仍需运行时记录；不能仅按我方 socket 写出时间证明该标志在首次编码器创建前已经是 true，也不能从错误 ACK 推定它一定为 false。

## 98311：先检查初始化状态，再决定是否解析申请

`VideoMsgHandler.VideoHandler.handleMessage`（classes2.dex）对 98311 的顺序是：

1. 如果静态 `mInited` 已为 true，直接用 `Recorder.getWidthEnc()/getHeightEnc()` 和 `Recorder.mDestFrameRate` 发送 65544，随后 return。**本分支没有 parseFrom 新负载，也不调用 initMediaCodec50。**
2. 只有未初始化才处理屏幕比例，并进入 `VideoMsgHandler.initMediaCodec(Message)`：解析宽、高、帧率及 needAdapterScreen/saveArea 等字段，调用 `Recorder.initMediaCodec50(width,height)`。
3. 初始化成功后，65544 的尺寸来自 Recorder 静态编码尺寸，帧率字段却使用刚收到的申请值；不等于硬件实际帧率。

真正的全局断连事件 1002 在 `CarlifeConnectHsgHandler` 中清 `mInited`，并清理特征状态。不能把我方 HU socket 重连、视频口重连、停止/重开本应用自动等同为该事件已经在独立引擎执行。98315 调用的 `Recorder.resetVideoTrans()` 在该制品中是空方法，不应未经验证就推荐它来强制重建。

13:32:16.440 首次申请 1412×654@60，17.150 确认 1280×720@60；24.336 重发同申请，24.641 确认 1280×720@20，后续重发继续为 20。13:36 另一段也呈类似模式。它与“首次回请求帧率、后续回当前帧率”的代码路径吻合，**不构成重复 98311 把编码器从 60fps 改成 20fps 的证据**。本引擎 VideoMsgHandler 对 98316 还限制为 3–30，60 请求在该入口直接返回；不能把荣耀版 Recorder 的 3–60 注释套用为本版本整条链路支持 60。此处不修改帧率策略，仅纠正证据含义。

## 首次分档与最终编码器配置

`Recorder.initMediaCodec50`（classes2.dex code offset 0x1ec9c0）普通路径先保存 `mVehiclWidth/High`，随后：

- 标志为 true：mMediaWidth/Height 直接取申请值。1412 和 654 都为偶数，后续偶数对齐不会将其变为 1280×720。
- 标志为 false：按宽度分档；1412 属于 1280–1919，非宽屏档的 mMedia 值明确为 1280×720（宽屏档为 1280×480），再允许 VideoResolution 的用户/推荐比例覆盖。

`CarlifeScreenUtil.isWideScreen` 的阈值为 2.3。1412/654≈2.159，且我方协议3.0走旧车机路径时默认采用16:9；在没有用户/服务端比例覆盖的普通路径中，不会仅因这是奥迪宽屏就自动进入1280×480分支，1280×720是可直接到达的标准结果。

这里是 `Recorder` 的标准档位选择，不应笼统写为 `VideoResolution.getResolution` 的非标白名单拒绝。后者主要处理用户/推荐设置；`isBoxSupportResolution` 的特定盒子支持列表也不能当作所有小米编码器的硬件能力表。直通标志 true 会绕过此处标准分档及 getEncodeResolution，**不会绕过后面的厂商回调**。

`MediaFormatUtils.getMediaFormatFormat` 先以当前 mMedia 尺寸建立 H.264 MediaFormat，随后在 Xiaomi mix 路径调用 `setVendorEncoderParams`（0x1ea054）。传给 `carlife_encode_format` 的语义分为：width/height=原始车机申请，real_width/height=当前已选编码尺寸，default_*=默认档，res_accuracy=新协议或直通标志，另含安全区和适配字段。回调返回正 width/height 后，直接对 MediaFormat 写入这两个值，并保存 customResolution；随后遍历 Bundle 还可写其他格式项。

上一轮已核对 CarWith 4.0.9 的接收实现：保存的 `<channel>_current_resolution_key` 优先，缺失时回落全局当前分辨率；只有两者均无值才采用此次 real_*。所以“发出了正确申请”与“交给 MediaCodec 的尺寸正确”之间确实存在可以覆盖尺寸的厂商层。

`Recorder.configMediaFormat`（0x1ec22c）取得这个最终 MediaFormat，**仅当 useVehicleResolution=false 时**才将其 width/height 回写到 mMediaWidth/Height 与静态 mWidthEnc/HeightEnc；无论标志真假，随后都以该 MediaFormat 调用 `MediaCodec.configure(..., CONFIGURE_FLAG_ENCODE)`，再创建输入 Surface。true 时若厂商改了尺寸，内部记录和实际编码配置可能不一致。普通分支未见把 configure 失败统一强制降为 720p 的处理，不能把标准档位选择说成已证实的硬件异常回退。

### 静态分支推演（非 APK 执行、非实车验证）

前提：首次普通 Surface 初始化、输入 1412×654、无额外用户/推荐/开发者格式覆盖、非宽屏分档。下表是以上分支的数值推演，不是运行测试：

| 条件 | 分档后 mMedia | 最终 configure 宽高 | 65544 宽高 |
| --- | --- | --- | --- |
| 标志 false，厂商不改尺寸 | 1280×720 | 1280×720 | 1280×720 |
| 标志 false，厂商返回 1280×720 | 1280×720 | 1280×720 | 1280×720 |
| 标志 true，厂商不改尺寸 | 1412×654 | 1412×654 | 1412×654 |
| 标志 true，厂商返回旧 1280×720 | 1412×654 | 1280×720 | 1412×654（内部尺寸未回写） |
| mInited 已为 true、既有档 1280×720 | 不重新分档 | 不重新 configure | 直接回已有 1280×720 |

因此实车的“确认与 SPS 都是 720p”，在相同实现的普通路径下更优先指向既有初始化状态/首次分档，不能只凭主包发现持久覆盖就宣布它是该样本唯一原因。首次 720p 还可能被主包保存，形成下一轮继续覆盖的反馈；是否发生仍需其实际设置值。

## 输入 Surface 到虚拟屏

`handleConnectEstablished` 用 Recorder 的 mMedia 宽高和 encoder.createInputSurface 的结果调用 `CarlifeSurfaceWrapper.bindEncodeSurface`，然后启动 mix 会话及编码器。

`MixPhoneStrategy` 在 OpenGL 类型 0 或 2 下选择 `ScreenRealImpl`：以 Recorder 编码尺寸为初值，经过显示比例与 customResolution 覆盖，构造 CastConfig；类型 0 直接提供编码 Surface，类型 2 则经 Surface 包装层。其他类型走 BaseScreenImpl/ScreenCarlifeImpl。已有主包分析继续接上 CastConfig → CarlinkService → VirtualDisplay 创建。

厂商返回 customResolution 会同时影响上述屏幕尺寸；true 时 Recorder 自己的内部尺寸可能仍旧，包装层又可能使用内部尺寸建立渲染目标。故要分别对账逻辑显示、包装层渲染目标、实际 MediaFormat，不能只比较申请与 SPS，也不能从静态差异直接算出照片裁剪了多少像素。

## 对现有诊断与下一步的修正

现在可以将“CarWith 忽略分辨率”的笼统描述拆开：引擎支持直通键；首次选档由标志及设置决定；厂商层可再次覆盖；初始化后重复 98311 根本不再读新尺寸。不能继续把本分支 `requestVideoReinit` 的名称、sendEncoderInit 的 force=true 或“二次初始化”日志当作目标编码器已重建的证明。

优先核对实车是否同引擎版本，以及全局断连/新源首次 init 时的 mInited、useVehicleResolution、initMediaCodec50 destWidth/Height、回调 real_* 与返回格式、最终 configure 格式。65570 不作完成凭据。已有进程内只重发98311不足以排除旧档；冷重启也不会自动清除 CarWith 当前分辨率或引擎 ratio.json，因此二者应分别核对。先收窄这些节点，不修改协议时序、配置文件、触摸缩放或 CarPlay 视口。

两种源启动先后仍需分别取证。本次已有源先于 CarPlay SETUP 的日志；不能据此确认引擎首次初始化早于特征处理，也不能据其他 Q4 画面成功证明这台设备。未来系统日志应只保留上述语义字段并脱敏，原始身份和完整负载不入库。

## 验证范围

已完成签名/包信息/哈希检查、目标处理链阅读及关键 Dalvik 分支交叉验证；未执行或安装厂商 APK、未读取车友私有配置、未实车复现。不把源码条件推演表述为已还原实际运行时序。

提交前执行 `build-local.ps1 -NoDaemon -GradleArguments @('--continue')`，构建标识31961151e907-dirty（仅本轮文档）。1068项单测、2 failures、0 errors、0 skipped；仍是既有NavigationMusicCompositionTest两处预期“500米”而实际“500m”的断言。lintDebug完成，报告0 errors、105 warnings、2 information（报告任务复用缓存），assembleDebug打包完成；总命令因上述单测返回失败，不表述为全门禁通过。未更改失败测试或交付修复APK；产物仅留在忽略目录。`git diff --check`通过。
