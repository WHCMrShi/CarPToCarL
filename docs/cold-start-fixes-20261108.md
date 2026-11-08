# 2026-11-08 冷启动问题修复

依据诊断包 `64de66b` 的 `CarPToCarL-log-20261108-124637.txt`，各问题分别提交。
日志原件不入库。协议参数、语音和爆音不在第一项修复中试改。

## 1. CarLife 握手成功但始终零帧

12:39:00 握手已完成，12:41:24 CarPlay 建立时仍无缓存帧，重复初始化未恢复，完整 CarLife 重连后才在 12:41:49 收到首帧。

区分真实视频时间、HU 会话起点、初始化重试时间；重试与重新打开 socket 不再刷新真实帧时间。音频恢复的“视频活跃”条件也只认真实帧。
从未出帧、等待至少 30 秒且初始化至少重试两次后，仅在 CarPlay 发送器已接入时允许完整关闭 HU 会话，让已有 worker 重建。预算由上层跨 HU 实例保留，最多两次，真实视频恢复或新的 prewarm 才复位。已经出过帧的暂时停摆不触发此升级，不主动重连 CarPlay。

完整 `build-local.ps1 -NoDaemon -Offline -GradleArguments --max-workers=2` 通过：682 项单测、lint 0 errors / 95 warnings、Debug APK 构建通过。未修改 lint baseline，未进行实车验证，原生引擎是否接受自动重建仍待车测。

## 2. 首次播放被历史静默时长误判

12:42:44.413 开始播放，仅 522ms 后看门狗因此前 58 秒静默关闭音频 socket，随后整个 CarLife 重置。
媒体期望状态从 false 变为 true 后独立等待 20 秒；有效格式宣告后也重新给 20 秒宽限。原有 PCM 静默、socket 打开宽限、真实视频存活和重连次数限制继续生效。真实断链仍走既有恢复，TTS 不按静默重连，不改变正常 PCM 内容或发送策略。

完整构建通过：685 项单测、lint 0 errors / 95 warnings、assembleDebug 通过；未实车验证。

## 3. 仪表封面：协商文件会话 v1

日志中的 Wi-Fi 表为 `1/k0/v2, 2/k1/v1, 3/k2/v1`，并订阅字段 26；旧代码只接受文件 v2，因此未读取图片或发送文件。
现在仅接受唯一有效的 kind=1、version=1/2，会话 ID 不重复；未知版本和歧义会话仍禁用。按协商版本编码：v1 SETUP 为 id、命令和 8 字节大小（10 字节），v2 另带 2 字节文件类型（12 字节）。不修改对端会话表，BT 和 Wi-Fi 均把版本传到发送器。

格式交叉核对：[独立 iAP2 dissector](https://gist.github.com/JJTech0130/78527c600f7b4d0a7aeb294eab06d8ba) 区分基础 10 字节与 v2 类型字段；[xcertplay 接收端](https://github.com/shilapi/xcertplay/blob/master/shared/src/main/java/com/shilapi/xcertplay/iap2/session/Iap2FileTransferReceiver.kt)核对原有 v2。没有导入外部源码。
保留订阅门禁、大小限制、引用先行、单文件在途、ACK/EAK/RTO、START/SUCCESS、取消和超时。新增字节级格式测试及模拟 v1 接收端的 Wi-Fi 完整图片往返。不是仅将 v2 报文强发给 v1。

完整构建通过：689 项单测、lint 0 errors / 95 warnings、assembleDebug 通过。真实 Q4 是否接受并显示，仍需车测。
