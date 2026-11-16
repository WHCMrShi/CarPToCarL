# CarWith 4.0.9：源端尺寸传递与上游复核

2026-11-16。用户要求继续定位原因、不改业务代码，并将源端排查置于 CarPlay 摆放之前。分析工作树 `f973e57`，实车日志仍为 `0e363ad07e46`。本轮没有改变协议、默认值、版本、连接时序或手机配置。

## 结论与证据等级

1. **确认应用存在错误的“特征确认”判据。** 65570（0x10022）在公开车机 SDK 与指南中是麦克风唤醒开始。分析时本分支及上游均用它设置 `featureConfigAcked`；不能据日志“引擎已接受特征配置”证明分辨率特征已经生效。此前将 13:37:03 当作真正确认后的降档反例，现撤回该解释。后续本分支已按用户授权移除此状态，见[纠错与建议](fix-and-recommendations.md)。
2. **确认同版本 CarWith 存在独立于 98311 的持久分辨率覆盖。** 厂商 `carlife_encode_format` 回调会优先选保存的当前分辨率，写回返回 Bundle 的 width/height；只有没有保存值才使用此次传入的 real_width/real_height。这是本轮找到的最具体源端候选，足以解释“新申请未成为最终编码尺寸”的一种机制，尚不能证明车友这台手机实际保存了 1280×720。
3. **确认虚拟屏创建消费引擎回传的 CastConfig 尺寸及 Surface。** 已跟到普通投屏分支的 VirtualDisplayConfig.Builder(width,height).setSurface(surface)。未发现这条普通分支把 1412×654 强制改为 720p 的逻辑；其他显示分支不能据此一并排除。
4. **本轮主包分析时尚未取得独立引擎。** 后续已取得 `com.baidu.carlife.xiaomi` 8.8.4（273），追到特征赋值及 MediaCodec.configure，见[引擎尺寸链路](engine-size-chain.md)。主包版本仍不等于车友实际引擎版本。新增证据显示重复98311可只回已有档位，以及直通标志true时厂商覆盖会造成65544与实际编码格式不同；因此“确认和SPS都为720p”的实车样本应优先核对旧初始化状态及首次分档，不能只凭主包覆盖逻辑宣布已找到唯一原因。

## 材料与复核方式

用户确认版本 `4.0.9-20260629`。公开同版本包来自[镜像版本页](https://mobileosupdates.com/apps/com.miui.carlink/104000009)，下载后只在 `.tools/carwith-display-20261116/` 静态解包，不安装、不运行、不入库。不是从车友手机导出的包，不能宣称逐字节等同其安装包。

用本机固定 SDK 的 aapt、apksigner、dexdump 检查：

| 项 | 本地结果 |
| --- | --- |
| 包名 / 版本码 | com.miui.carlink / 104000009 |
| 版本名 | 4.0.9-20260629 |
| 文件大小 | 63,934,080 bytes |
| APK SHA-256 | 67f8d1d31d4f44e83238009c672cc9fd0dc67772bdd6cff5169a1695a3fc4617 |
| 签名校验 | apksigner verify 通过，证书 DN 为 Xiaomi / MIUI |
| 证书 SHA-256 | c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025 |

文件与证书哈希均与[另一镜像的同版本页面](https://www.apkmirror.com/apk/xiaomi-inc/xiaomi-carwith/xiaomi-carwith-4-0-9-20260629-release/xiaomi-carwith-4-0-9-20260629-android-apk-download/)公布值一致。结论依据本地字节码控制流，网页仅作包来源和交叉校验；不依据下载页的“安全”文案判断产品行为。原始 APK、dex、反汇编及第三方 PDF 均不提交。

## 98311 与特征配置：我方出口核对

在 `f973e57` 的 `CarLifeHijack` 创建 `CarLifeHuClient` 时，首轮尺寸是 1412×654；收到真实 /info 后也用该尺寸更新申请。`setRequestedResolution` 不将确认的 1280×720 写回申请。`buildEncoderInfo` 使用 requestedWidth/Height，调用 `videoEncoderInfo`，字段 1/2/3 为申请宽/高/帧率，字段 9 的 needAdapterScreen 默认 true；调用处未传 saveArea，故字段 4–7 未声明，drawUIOutsideSafeArea=false 时字段 8 也省略。“9 字段版本”不等于实际九字段全发。

`featureConfig` 的列表包含 DISABLE_RESOLUTION_OPTION=1，键、value 的 protobuf 类型及外层 cnt/list 与公开 SDK 格式相符。没有静态证据显示本次漏发送该键或把申请写成 720p；日志不是原始封包抓取，不将这项静态核对升级为目标引擎逐键采纳证明。

### 65570 判据错误的直接证据

- [公开 SDK 消息枚举](https://github.com/qiyunjzz/carlifeLIB/blob/fdf8310bf1b6f0c4c247d848793832c016607561/carlifLib/carlifelibsourcecode/trunkbin/include/CTranRecvPackageProcess.h)把 0x10022 定义为 `MSG_CMD_MIC_RECORD_WAKEUP_START`；0x10051 是 MD 特征请求，0x18052 是 HU 特征响应，后两者不构成“再等 0x10022 确认”的协议定义。
- [SDK 分发实现](https://github.com/qiyunjzz/carlifeLIB/blob/fdf8310bf1b6f0c4c247d848793832c016607561/carlifLib/carlifelibsourcecode/trunkbin/core/CTranRecvPackageProcess.cc)把该事件分发到麦克风唤醒回调。
- [百度署名旧版接入指南镜像](https://github.com/qiyunjzz/carlifeLIB/blob/fdf8310bf1b6f0c4c247d848793832c016607561/carlifLib/百度CarLife车机手机互联方案接入详细指南new.pdf)第 10.5.2 表及 10.5.3.1 唤醒时序一致；PDF 文件页 126 为唤醒时序。它不是现行官方门户或目标引擎源码，但两份协议材料与本仓库最早笔记的麦克风解释一致。
- 我方 `MD_FEATURE_CONFIG_DONE=65570` 来自“发配置后约 0.7s 收到”的时序推断，处理函数没有验证特征键值或其他确认内容。11月16日投屏稳定运行后，13:40:33.459、13:41:25.547、13:42:18.177 又出现同一句“接受特征配置”，其附近有媒体活动；这与专用配置 ACK 的解释不相称，但也不能仅凭相邻时间断言用户做了语音操作。

可确认的是“该消息不能作特征 ACK”；尚不能仅由此宣布它造成本车 720p，或者简单删除等待就能修好。尤其不能把 65617（特征请求）改名当作新的 ACK。

该判据修正也适用于历史 Q4 文档中同一日志文案：过去的 SPS 尺寸、源首帧及用户视觉反馈仍各自有效，但“看到该文案所以该特征已生效”的因果链需重新审视，不能拿历史出画反证错误消息定义。

## CarWith 厂商回调：具体尺寸覆盖点

以下均是上述同版本 APK 的符号与 dex code offset，用于复核第三方制品，不是本仓库基线 bcef70f 的源码行号。

`classes6.dex` 的 `com.xiaomi.ucar.carlife.c.j(Intent):Bundle`（code offset 0x09bd88，日志标签 CarlifeController）处理 `carlife_encode_format`：准备返回 Bundle，应用帧率/profile/level 等参数，随后调用 `c.i(isPortrait,intent,bundle)`，最后返回结果。横屏同样会走 i，不能因其前面存在竖屏专用逻辑便认定横屏不会覆盖尺寸。

`c.i(Z,Intent,Bundle)`（0x09c990，日志标记 handleCustomResolution）的顺序为：

1. 读取 JSON params 的 default_width/default_height 和 real_width/real_height。
2. 把 default_* 保存到默认分辨率键。特定车辆 channel 另有默认 1024×576 分支，但本次没有命中该 channel 的证据，不能当作 720p 来源。
3. 读取 SharedPreferenceUtils `h2.i()` 的当前分辨率。若非 null，直接使用；仅 null 时以本次 real_* 组成字符串并保存。
4. 成功解析正的宽高后，横屏把它写入返回 Bundle 的 width/height；竖屏按 min/max 排列写入。这里没有用本次 real_* 与旧值是否相等作为覆盖门禁。

`classes.dex` 的 `com.carwith.common.utils.h2` 明确显示读取优先级：通道专属 `<channel>_current_resolution_key` 优先，缺失时回落全局 `car__current_resolution_key`；默认值另存在 `<channel>_default_resolution_key`。所以“更新默认车机尺寸”并不等于“更新当前选用尺寸”。这包含用户自选分辨率保留的设计，不能只凭持久化存在就把它定性为 bug，也不能宣称冷启动自然清除旧值。

**候选解释**：如果曾按默认/首次协商生成 1280×720，或保存了该档位，即便本次 real_* 已为 1412×654，回调仍可能返回旧 1280×720。相反，若回调第一次收到的 real_* 已是 1280×720，它可能只是在保存引擎先前降档的结果。现有导出没有 callback params、所选设置或返回值，两种情况必须保留。

## Surface 与虚拟屏：已追到哪里

主包 `CarlifeKeepAliveService` 明确绑定独立包 `com.baidu.carlife.xiaomi` 的连接服务。`CarlifeConnService.CarLifeCastManagerImpl.prepareCast(CastConfig)` 从回调对象取出宽、高、密度及 Surface，装为 `c2.b`，由 `c2.f.t` 发给监听者；`CarlinkService.b` 交给 `CarlinkStateMachine.o1/q1`。状态机对应分支从同一个对象取宽、高、Surface，传给 `CastController.displayConnected`，普通投屏分支最终用这些宽高建立 VirtualDisplayConfig 并 setSurface。

这是“引擎把已准备的 Surface 交给 CarWith 创建虚拟屏”的方向；不能误写成我方 `VirtualLauncher` 创建该屏后决定源端编码尺寸。也不能拿主包另一套 MediaCodec 编码器（其他连接方式）代替独立 CarLife 引擎的 configure 调用证据。

主包会查询独立引擎的版本并对部分参数走兼容分支，但本次日志没有返回版本。后续取得的8.8.4引擎已补足静态处理链；仍不能冒充为实车同版或运行时赋值。用户难以提供时保留这项边界，不继续要求上传。

## 上游线索及采纳边界

已 fetch 并只读对照上游 `main=4e42a0d23ee411a1ac4d2ffa44feef9d9fca726d`（2026-11-16，1.2.53）。

- [PR #25](https://github.com/jjuugk/CarPToCarL/pull/25) / [提交 4d9fbde](https://github.com/jjuugk/CarPToCarL/commit/4d9fbde9fdd528b6f73553f78b8d833934f1d180)：将各入口的 98311 统一延后到 versionMatched 与 featureConfigAcked，并由事件回调续发；修复说明主要是宝马源重启、保活、PTS。它仍把 65570 当 ACK，不能直接移植为小米分辨率修复。若该事件不来，新增等待还可能影响起播。
- `CarLifeProtocol.videoEncoderInfo` 的尺寸与可选字段写法没有针对本问题的新修复；`DISABLE_RESOLUTION_OPTION=1` 继续发送。与本地的相关差异包括上游新增显式 CONTENT_ENCRYPTION=0，但不能据此推定与尺寸覆盖有关，不引入无关加密实验。
- 上游 `Recorder.useVehicleResolution`、VideoResolution/reset2Default、MediaFormatUtils 的说明沿用早期荣耀逆向。CarWith 4.0.9 确有同名 `carlife_encode_format` 厂商回调入口，支持继续检查覆盖，但不证明引擎内部实现完全相同。
- 已查看当时可访问的上游 issues/PR 列表及相关 PR #25；未找到能直接证明本版本 CarWith 1412×654 已修复的报告。未向上游发评论或消息，未合并或 cherry-pick。

## 后续取证优先级

先记录真实特征处理，再对账回调输入 real_*、当前分辨率/全局及通道键、回调返回 width/height；随后对账 prepareCast 与 VirtualDisplay 的宽高、引擎 MediaCodec 格式和 SPS。这样可区分特征未生效、引擎预先降档、厂商设置覆盖及屏幕/Surface 不一致。

现有用户导出不足以确定旧值或具体屏幕尺寸。后续若能取得系统日志，应限定到 CarlifeController、handleCustomResolution、prepareCast 与虚拟屏信息，并脱敏；不能只反复导出同样的发送端日志期待出现未采集的内部值。两个启动顺序分别保留样本。源完整性确认之后，才轮到 CarPlay RAW/SPLIT 对照，不篡改解码尺寸或触摸缩放。

## 验证范围

已完成静态包身份/签名/哈希、字节码分支与调用链、公开协议定义、上游 diff/提交核对；没有安装、运行目标包、读取车友私有设置或实车复现。不将文档修正或静态候选当作问题已修复。

提交前执行 `build-local.ps1 -NoDaemon -GradleArguments @('--continue')`，诊断标识 f973e57596e9-dirty（本轮仅文档）。1068 项单测，2 failures、0 errors、0 skipped；仍是 NavigationMusicCompositionTest 两项断言预期“500米”而实际“500m”，未改这些测试。`--continue` 使 lintDebug 与 assembleDebug 仍完成：lint 0 errors、105 warnings、2 information（报告任务复用缓存），Debug APK 打包完成。总命令因单测返回失败，不表述为默认门禁全通过；APK 仅留在忽略目录，不作为修复包交付。`git diff --check` 通过。
