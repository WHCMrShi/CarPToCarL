# 2026-11-15：6b4a25f 播放控制重审

审查对象为 `6b4a25f7b961f0645750c2e4813da8dd95f0f7fd` 的全部 10 个变更文件，父提交为 `d4536a1`。
先按用户要求以 `de6a8c4` 完整撤除音频资源仲裁 `b08d2a7`，再以 `10decb3` 收缩播放控制，并核对最终源码。
依据是本仓库 [PCM 路径约束](../../q4-pcm-performance.md) 和用户确认的 CarLife 合并输出方式：9240 中可以同时包含音乐、导航和提示音，不能把它等同于纯音乐。
以下为代码审查结果，不是新增实车故障证据；引用文件/函数和提交，不使用当前源码行号冒充 `bcef70f` 行号。

## 已确认的问题与处理

### P1：暂停音乐会阻断同一路导航/提示音

`CarLifeMediaPlayback.canForwardPcm` 在音乐会话 PAUSED、车机暂停或音乐焦点释放后为 false。
`CarLifeHijack.onAudioPcm` 据此拒绝整个 9240 输入；`AudioSender.setMediaPaused` / `AudioIngressQueue.setMediaPaused` 又清除所有 `speech=false` 的待处理块，并拒收后续块。
在合并输出模式下，暂停音乐之后到达的导航与提示音也满足这些丢弃条件；独立保留 9241 的 `speech=true` 无法保护已经混进 9240 的声音。
`SessionController.installAudio` 还会把这一门禁施加到新发送器，使暂停快照影响后续建链。

处理：删除播放资格状态机、关联回调、发送器安装门禁、暂停清队列和拒收逻辑。音频包读取和看门狗回到 `d4536a1` 的路径。
同时发现父提交已有 `CarLifeAudioSink` 的 15 秒暂停丢弃窗口及媒体状态监听；这不是 `6b4a25f` 新增的问题，但保留它仍会误丢混合音频，因此一并移除。
保留原有实时队列容量与过期保护，不承诺网络积压时绝不丢包。

### P1：新命令 socket 写可以阻塞车机收帧

`SessionController` 的 iAP2 媒体键回调和事件 HID 回调直接调用 `dispatchMediaKey`，原 `6b4a25f` 又同步执行 `CarLifeHijack.dispatchCarLifePlayback`。
其 `sendHardKeyCode` / `sendPauseMedia` 最终进入 `CarLifeHuClient.send` 的锁内 socket write/flush。写入阻塞或等写锁时，回调不能及时返回；这与既有 GPS/语音键明确绕开收帧线程的约束冲突。
这是可由调用链确认的阻塞风险，尚无本次实车卡死复现。

处理：播放命令使用独立单线程、容量为 8 的有界队列，满队列拒绝入队并走系统媒体键路径，不在调用线程执行 socket 写。
任务捕获原 CarLife client，执行前及各发送出口检查会话身份；已退役的排队任务记录 `skipped=source-retired`，不发送给后继会话。
已开始的写入不能撤回；这里只隔离阻塞和旧任务，不改变原有 socket 生命周期。

### P2：命令全部发送失败仍被报告为已处理

原 `CarLifeHijack.handleMediaKey` 只要发现 CarLife client 正在运行，对支持的键就无条件返回 true。
即使 MediaSession、CarLife 硬键和兼容暂停三个出口都失败，`SessionController` 也不会再尝试原系统路由。

处理：逐个记录命令投递结果；全部失败且仍属原会话时执行原系统媒体键兜底。无 CarLife、不支持的键、队列满均保留原系统入口。
投递成功只表示调用/写入成功，不等同于播放器已暂停。日志继续包含 `delivery-not-playback-confirmation`，失败回退另记 `delivery=failed fallback=system`。

## 逐文件去留

| 原提交文件 | 最终处理 |
| --- | --- |
| `SessionController.kt` | 保留播放控制入口并提供系统兜底；移除新发送器的暂停门禁。 |
| `audio/AudioIngressQueue.kt` | 撤回本提交全部修改，不再按播放状态清队列或拒收。 |
| `audio/AudioSender.kt` | 撤回本提交全部修改，不再公开暂停/清媒体入口。 |
| `carlife/CarLifeAudioSink.kt` | 撤回日志类型映射修改，并删除父提交遗留的暂停丢弃窗口；仅保留格式和原端口预算标记。 |
| `carlife/CarLifeHijack.kt` | 删除播放周期、播放状态监听和 PCM 门禁；保留明确播放/暂停命令，增加串行异步发送、会话身份检查及失败回退。 |
| `carlife/CarLifeHuClient.kt` | 撤回播放周期事件、看门狗替代条件和音频信号改写；仅保留兼容 `sendPauseMedia`。 |
| `carlife/CarLifeProtocol.kt` | 仅保留 `MEDIA_START=0x1F`、`MEDIA_STOP=0x20`、`HU_PAUSE_MEDIA=98318`；撤除本次未用常量及音频信号重命名。 |
| `carlife/CarLifeMediaPlayback.kt` | 删除。 |
| `audio/AudioIngressQueueTest.kt` | 删除假定导航必为独立 TTS 的新增暂停清队列测试，保留原队列回归。 |
| `carlife/CarLifeMediaPlaybackTest.kt` | 随已删除状态机移除；以命令行为、线程边界和真实 sink/输入队列回归替代。 |

新增 `CarLifeMediaCommands` 只封装命令和排队，不保存播放意图、音频所有权或自动恢复资格。
PLAY/PAUSE/STOP 映射为明确命令；PLAY_PAUSE 在执行时优先读取当前 MediaController 状态，其次读取媒体快照/CarLife 状态，然后为各出口确定同一个意图。
无可用状态时沿用“视作未播放”的回退，不能据此宣称已确认播放器状态；收到的新 PCM 始终不参与这一判断。

本次撤销了将 `0x30002/0x30003/0x30004` 改为播放资格依据的适配，并恢复原 Q4 同步锚点/看门狗路径。
这不证明旧 START/STOP 命名就是正式协议语义；旧命名来自本车历史时序推断。后续若要更正映射，应单独取证并验证，不能顺带据此丢 PCM。

## 最终复核与验证

- 按原提交逐文件比对；`AudioSender`、`AudioIngressQueue` 与 `d4536a1` 一致，原邀请修复和触控局部重连保持。
- 生产源码中已无 `Q4AudioArbitrator`、`Q4AudioResources`、`AudioOutputMode`、`CarLifeMediaPlayback`、`mediaPlaybackExpected`、`setMediaPaused`、`setPlaybackPaused`、`clearPendingMedia` 残留。
- 命令回归覆盖重复暂停、toggle 展开、单出口异常、全部失败、不拦截下一曲、阻塞时调用方可返回、命令顺序、旧会话排队任务跳过及队列满拒绝。
- 音频回归使用不同样本序列，检查暂停前、暂停后立即及 16 秒后的原样转发；真实输入队列消费阻塞时，暂停前的待处理样本和暂停后的新样本仍按序到达；独立 TTS 的格式与端口标记保留。
- 完整 build-local.ps1 -NoDaemon 通过：1068 项 JVM 测试（0 失败、0 错误、0 跳过），lint 0 错误、105 警告、2 信息，Debug APK 构建成功。

原 `6b4a25f` 不应原样保留在当前合并音频模式下；本次已移除错误的数据路径控制并修复上述命令路径问题。
没有安装手机或新增 Q4 路测。尚需分别在 CarLife 先启动和 CarPlay 先连接时确认播放/暂停、暂停后的导航/提示音、残留音乐与连续操作效果；本机验证不能代替这些结果。
