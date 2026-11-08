# 1715CR 核验后的修复与测试包

本轮基于 `5c58686`，按用户要求实现 Review 核验后的修改，各问题独立提交。运行时诊断标识为 `schema=20261108-v5`；应用版本仍为 `1.2.30-q4-debug`，默认 48K PCM、跳过启动免责声明保持不变。

## 分项改动

| 问题 | 提交 | 最终行为 |
| --- | --- | --- |
| H1 / L2：正常大音频块与诊断 | `2a6ad93` | 合法输入块完整复制，不再截尾；媒体仅在真实积压/过期时丢旧块，TTS 有独立的 512KiB / 10 秒积压预算；总块数受限。允许一块超出时长预算的完整合法输入，输入上限 256KiB。区分收到与入队成功计数，保活时钟只在入队成功后更新。 |
| M5：异步音频重建竞态 | `da9486b` | 初始 SETUP、重建、通话换流均捕获会话代次，协商返回后校验再发布；失效资源释放，watcher 和延迟任务绑定原会话。网络协商不持代次锁。 |
| H3：局部故障与终态 | `b2dad28` | 保活写失败与数据帧写失败均先走有界数据口恢复，耗尽才结束；恢复所有权防止两个线程重复重连，并补齐数据帧失败的结束通知。保留 ProjectionOwner 对旧回调的保护。 |
| H2 / M6：播放证据 | `65bda3a` | 系统会话需匹配当前 CarLife 曲目且状态明确；比较 START/STOP、进度和系统状态时间，缓冲态为未知。停止把全局音乐活动直接当作恢复证据。无 START 时仍可用增长进度恢复；保留宽限和重试限制。 |
| M1：封面重试 | `66f947d`、`11b85bf` | 成功复用引用；失败、取消或超时后允许两次显式重订阅重试，新 ID 隔离旧响应。新链路重置缓存假设；不同曲目即使图片摘要相同也有独立预算。 |
| M2 / M3：封面流控 | `d62a774` | 单包在途，先消费 PAUSE/START 再推进；兼容 peer window=1，窗口 0 明确禁用并诊断。保留控制消息优先和链路重传。 |
| M4：媒体解析 | `d1cd5de` | count 不符仅诊断；完整外层条目可保留，坏子条目跳过。单条截断仍拒绝，媒体列表字段上限提高且受命令总长度约束，HUD 上限不变。 |
| L3：HUD 请求失败 | `55ca837` | SUBSCRIBE 发送失败不继续 START。控制写异常可能留下半个报文，因此退役 HU、等待新连接，不在原流盲目重发；日志明确说明放弃原因。 |
| L4：透明图片与 Android 验证 | `7305d73` | 实际有透明像素时使用 PNG 并缩放至预算内，避免隐式转成黑底 JPEG；不透明图片保持有界 JPEG。新增真实 Android Bitmap 测试夹具。 |

上述属于本机验证后的修复，不是 Q4 实车验证。仍无法从旧日志确定首次无声的手机内部路由根因，也不能保证网络阻塞完全消失；本次改动改善恢复判据、资源归属与可观测性。

## 本机验证

每笔代码提交前均运行固定工具链的 `scripts/build-local.ps1`：单元测试、lint、Debug APK。最终另外编译 Android 测试源码：

```powershell
& ./scripts/build-local.ps1 -Tasks ':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebug',':app:compileDebugAndroidTestKotlin' -NoDaemon -Offline -GradleArguments '--max-workers=2'
```

最终 JVM 单测 734 项通过、0 失败，lint 0 errors / 95 warnings。Debug APK 构建通过。Android 测试源码编译通过，三个真实 Bitmap 夹具尚未在设备运行，不能视作编码器或车机显示验证。

Android 编码夹具覆盖：噪声大图尺寸/体积、半透明 PNG 的透明度、小 JPEG 原样复用。开发者有设备时可构建安装测试包后运行：

```text
adb shell am instrument -w io.github.shaonialife.carptocar.test/io.github.shaonialife.carptocar.media.ArtworkEncodingInstrumentation
```

成功结果应包含 `3 Android Bitmap fixtures passed`；不能只根据命令退出码判断。

## 车测关注点

交付包：`CarPToCarL-1.2.30-q4-review-fixes-20261108.apk`（位于忽略的 `dist/`）。代码对应 `11b85bf`，包含此前所有 Q4 修复。SHA-256：`E9FD673A0AF05AE6F8D915B4018F54624CBBB81AD43F6F439B5B025D45D79343`。签名校验通过，与既有本机测试包使用同一 Debug 证书；没有安装或路测。

1. 冷启动首播、先连 CarLife 和先连 CarPlay 两种顺序；无声时导出 silent-input 的来源和时间信息。
2. 同曲停留、同专辑连续切歌、重连、快速切歌；观察封面出现耗时、是否黑底、失败后是否能重试。
3. 音乐及实际导航播报：记录爆音或吞字的准确时间，对照 admitted/rejected、队列丢弃及 UDP 耗时。
4. 短暂断网、屏幕流重启与彻底断开分别观察；有界恢复期间应保留 HU，终态才清理，新连接能重新建立。
5. 导航 HUD 仍只采集，未映射为媒体。核对 SUBSCRIBE/START、模块状态及 NEXT_TURN 计数，零计数不代表已证明手机不支持。

## 原始证据的归档边界

`CarPToCarL-log-20261108-141059.redacted-excerpt.txt` 是旧 v3 包的关键诊断摘录，非新版本车测。按明确消息前缀白名单选取，整行去重，按时间排序，同时间保留原顺序；保留消息编号、大小、音频统计与连接结果，排除原始二进制载荷、地址、SSID、设备名、曲名和凭据。文件头含原件 SHA-256 供本机核对。

这不是完整脱敏日志，不能用于统计所有触发次数或逐字节重放。原件未入库，构建产物和 APK 也不入库。
