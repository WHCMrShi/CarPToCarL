# 2026-11-14：上游控制能力的 Q4 适配、自审与回滚

## 范围与证据等级

用户要求分项实施并单独提交。起点为 Q4 `02dcc70`，上游参考为 v1.2.42：
[播放控制 956307c](https://github.com/jjuugk/CarPToCarL/commit/956307ce04664d6f023224aa26979ad817f4c64b) 与
[无线/资源仲裁 c12d30e9](https://github.com/jjuugk/CarPToCarL/commit/c12d30e9b971a4534e9b00d69749ccbe5eff93a8)。
此处记录代码事实和本机验证；没有新增手机安装或 Q4 实车结果。协议响应接受、输出队列放行均不代表车机已发声。
倒车借屏沿用现有 ResourceModes / Q4ScreenHandover；热点记忆、默认网络、认证挑战及 disableBluetooth 门禁未调整。

## 一、推流中邀请只观察（85ec290）

删除 onCtrlInviteWhileStreaming 中退役会话、启动后台重连、修改 setup/connection 工作和邀请重建防抖的动作。
推流中邀请只记录次数、间隔、控制连接存活/就绪状态；随即返回，不放入空闲邀请缓存。
空闲时仍按现有有效邀请启动连接，真实控制/视频故障仍交给原故障恢复路径。

独立自审：

- 从 onCtrlInvite 入口跟踪推流分支到 return；目标识别、连接意愿和取消门禁仍在入口。
- 函数内只修改诊断计数，静态检查未发现 retireSession、onCarPlayEndpoint、Bg.run、pendingCtrlInvite、connectRetry、setupWork、connectionWork 调用。
- 删除防抖字段及旧注释，避免仍将 ID7 的时间相关性解释为“邀请必然要求重建”。
- CarLife 先启动、CarPlay 先连接都使用相同分支：视频发送器已运行时，重复邀请不能打断当前流；此状态不宣称接收端已解码。
- 未新增按邀请触发的替代恢复动作。完整 build-local 门禁通过；未新增实车邀请证据。

## 二、触控局部重连（d4536a1）

保留 TouchForwarder 的有界队列、MOVE 合并、手势边界和输入退役。写入失败由同一发送线程关闭旧 9340 socket、重连该端口。
不重发失败帧：TCP 写异常可能发生在部分写入或 flush 后，无法证明对端未执行。
新通道先发送 Android ACTION_CANCEL，丢弃排队的旧输入，从下一次全新 DOWN 开始接收手势。
初始触控端口不可用也可在首次输入时尝试局部接入；重连或 CANCEL 写入失败才走现有整轮 CarLife 重建。

自审和回归覆盖：局部恢复后 HU 仍存活；失败 DOWN/旧 MOVE/UP 不重放；下一次点击正常编码；停止期间返回的新 socket 立即关闭；取消失败及重连失败均回退。
Q4 尚需确认引擎对 CANCEL 的实际行为。`local=ready` 只表示取消帧写成功，不是手机处理确认。

## 三、播放控制（6b4a25f）

适配上游 CarLifeMediaPlayback：模块 3 焦点、INIT/PCM、STOP/PAUSE/RESUME 组成播放周期。
STOP(0x30002) 作为格式/周期边界，不单独授权播放；PAUSE=0x30003，RESUME=0x30004。
这是对旧 Q4 “0x30002 后出现 PCM 所以是 START”推断的修正；保留原始类型日志，需用 OPPO 实测检验。

车机 PLAY/PAUSE/STOP/PLAY_PAUSE 转换成明确的播放或暂停意图，走 MediaSession 与 CarLife MEDIA_START/MEDIA_STOP 硬键，暂停另发兼容 PAUSE_MEDIA。
明确命令可重复执行而不会像 toggle 一样反转；无活跃 CarLife 时保留原系统路由。
暂停后拒绝新媒体 PCM、清除尚未消费的媒体输入，独立 TTS 输入保留。已经进入发送的块不能撤回，日志明确区分。
本分支没有上游 AudioMixer，使用既有 AudioIngressQueue，并未替换 Q4 的 PCM 封包路径。

自审修正：旧 PLAYING 快照不能撤销车机暂停；显式 PLAY 能越过旧 PAUSED 快照。焦点释放先于尾随 PAUSE 不锁死下一轮；格式重建/残留 PCM 不自动抢回原车音源。
回归包括上述状态序列、协议常量、暂停清队列保留 TTS；完整 build-local 门禁通过。

## 四、音频资源仲裁（b08d2a7）

只适配主音频资源（resourceID=2），不引入上游的屏幕 TEARDOWN/SETUP 或整套认证/重连重写。
保留 Q4 已协商的音频流，通过最终发包及同步包门禁控制输出，恢复时继续该流；这项取舍仍需实车验证归还后的声音恢复。

- 媒体新播放意图按用户优先级申请永久持有；导航 TTS 在车机允许后台借用时临时借用，播报与待发送 TTS 排空后归还原车音源。
- 显式 TTS STOP 标记源结束；漏 STOP 时使用末次 PCM 后 1500ms 的有界宽限，再结合输入队列是否排空判断归还。
- 车机借用期间不覆盖它的借用，支持具名重复、嵌套及匿名借用；超过有界容量后保持禁止输出，等待新会话重置。
- 永久切到原车音源：暂停手机媒体，不自动恢复。临时借用：仅记住借用前确实在播的状态；全部归还且来源、曲目、用户操作代次仍匹配时才恢复。
- 用户经本应用媒体键再次操作、来源更换、曲目改变或期间永久切源都会取消旧自动恢复资格。系统不能提供的“同状态重复暂停”不被假装成可观测事件。
- 申请前暂存有界输入（HOLD），获准后 ALLOW；借用、拒绝、退役或异常进入 DROP 并清理积压。车机借用时导航也不会越过静音/通话约束。
- 既检查 RTSP 状态，也检查响应正文 status。200 + 非零/畸形 status 不当作获准。
- 发布前与响应后检查会话归属，资源版本变化会使迟到响应失效；拒绝不会每 250ms 重发，新的意图或车机状态可再尝试。
- 单一工作线程处理仲裁，事件线程只更新状态、门禁和唤醒。回调及新音频发送器发布受现有 audioEpoch 保护；发送器启动后发布时再次读取资源/暂停状态。
- 最终 PCM/AAC/采集发包入口和同步包均检查门禁；已交给底层的单个发送不能撤回。屏幕借用状态有独立回归，音频申请不改屏幕资源。

两种启动顺序：CarLife 先起时读取当前源意图；CarPlay 先连时保留既有初始音频归属，晚到源触发同一仲裁器。没有为等待源而改动认证/视频建立顺序。

### 日志判读

| 前缀/字段 | 用途 |
| --- | --- |
| `CTRL-INVITE ... action=observe` | 邀请到达但本机未因此拆流 |
| `TOUCH-RECOVERY ... local=start/ready/failed` | 9340 局部恢复、清手势或整轮回退 |
| `MEDIA-CONTROL ... wire/event/playing/forwardPcm` | 原始音频控制类型与播放状态转换 |
| `MEDIA-CONTROL ... session/hardKey/pauseCommand` | 暂停/播放各命令出口的投递结果 |
| `AUDIO-RESOURCE ... policy=q4-audio-v1` | 本轮启用仲裁，可对应代码版本 |
| `car-change before/after` | 资源版本、永久归属、借用数与约束 |
| `request/result/publish ... bodyAccepted/committed` | 区分本机申请、协议接受、版本有效提交 |
| `output=ALLOW/HOLD/DROP` | 实际发送门禁，关联待发送 TTS |
| `autoResume=armed/cancelled`、`reason` | 自动恢复依据及取消原因 |
| `worker=failed ... reconnect-required` | 仲裁线程异常，禁止继续输出而非静默假装成功 |

### 本机验证与实车待验

四项业务提交前均执行 build-local.ps1：JVM 测试、lint、Debug APK。最终 1084 项测试通过（0 failures / 0 errors / 0 skipped），lint 0 错误、105 警告、2 信息，Debug APK 构建成功。
新增测试覆盖音频资源申请/归还、嵌套与匿名借用、约束、拒绝/畸形正文、迟到响应、取消自动恢复、两种启动顺序、TTS 暂存/丢弃，以及真实 AudioSender 发包出口。
测试不等同于 Q4 扬声器、触控响应或接收端解码验证。没有改默认网络选择，也未安装手机。

下一轮分别记录 CarLife 先启动和 CarPlay 先连接，检查音乐暂停/恢复、静音前在播/未播、静音期间手动暂停/换歌、原车音源与导航借用、来电归还、重复邀请及触控端口故障。倒车只做既有行为回归，不启用新屏幕策略。

### 单独回滚

若新音频仲裁导致无声、错误抢音源或归还失败，导出含 `AUDIO-RESOURCE` / `MEDIA-CONTROL` 的时间连续日志后，单独 `git revert b08d2a7` 并重新构建安装。
该提交回滚后保留前三项改动与既有 disableBluetooth 门禁；不需要回滚播放控制、触控恢复或邀请修复。回滚后依然需要手机安装与实车确认，Git 回滚本身不改变已装 APK。
