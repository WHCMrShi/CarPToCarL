# 媒体封面（独立提交，可单独回退）

## 范围与启用条件

本提交只依赖前一个专辑名提交。回退本提交即可移除封面采集/编码/文件发送，保留专辑名、播放进度及此前音视频、触摸和通信优化。没有数据库、布局、默认配置或版本号变更，没有构建 APK。

NowPlaying 的媒体组使用订阅中明确请求的封面引用字段（26；兼容请求字段 20 的接收端）。只在对端 SYN 声明 **kind=1/version=2** 且 ID 唯一有效、窗口可保留控制容量、载荷可容纳 SETUP 时启用。实现的是带文件类型的 12 字节 SETUP；不向旧版文件会话猜发该格式，也不修改或扩展对端 SYN 表。没有封面订阅或文件会话时不会加载/编码图片，不添加封面引用，其他媒体信息照常发送。

归档 Q4 SYN 只包含控制会话和 EA 会话，不能据此保证封面在 Q4 显示；若实车仍未声明文件会话，封面会安全跳过。新增 ARTWORK 订阅日志可确认字段和会话。单元测试只能确认模拟接收端的协议往返，尚未实车验证。

## 图片与性能

系统媒体会话优先使用原始 ALBUM_ART/ART 位图，或 ALBUM_ART_URI/ART_URI；不使用现有 UI 缩略图作为协议源。主源整体不可用时保留 CarLife 整条媒体信息兜底，并且只取同一个 CarLifeMediaInfo 对象的 albumArt，不跨源拼接歌曲与图片。

一个后台工作线程、一个最新待处理任务，按媒体源版本缓存，普通的每秒位置采样不重新编码。元数据回调或图片 URI 变化允许重新读取；没有保证频繁变化的元数据只编码一次。已编码数据按摘要判断是否需要重新发送。JPEG/PNG 不超过 1024 像素边长时直接复用原始编码，否则等比缩小后一次 PNG 编码；原始系统位图不回收。输入/编码输出最多 1 MiB，解码先读尺寸并采样，网络连接及单次读取超时各 3 秒。超过限制或读取失败时缺省为无封面，不降级音视频质量。

缓存和任务随媒体服务/CarLife 生命周期清理。切歌丢弃旧任务结果，同曲元数据加载过程中保留已就绪图片；停止、取消和传输超时不重建音视频连接。关闭线程池会中断任务；内容提供者读取若不响应中断，无法承诺立即结束，但关闭后结果不会发布。

## 文件发送与可靠性

先发布单字节文件引用，再发 SETUP，收到 START 后发送 first/data/last，等待 SUCCESS；支持接收端缓存直接 SUCCESS、PAUSE/START 续传、FAILURE/CANCEL、超时及发送窗口暂满时重试。无封面发送独立的空文件引用清除旧图。

每次至多 2048 字节图片，最多一个未确认文件链路包，给控制包保留窗口容量，不进入控制重传看门狗。封面不被确认时终止当前封面，不因为这一可选包主动拆控制连接。底层仍复用现有可靠有序 RFCOMM/TCP 和共享链路序号；这不是对所有接收端丢包行为的实车保证。

为避免按 ID 缓存的接收端错误显示旧图，连接内不复用非空图 ID。设备方向使用 129..255，128 专用于空图；每连接最多发送 127 次不同图片，之后安全清空并等待重连。不会通过强制重连恢复封面。暂停/完成后的普通进度更新不重复发送图片。

蓝牙复用既有媒体发送调度器；WiFi 复用既有 200ms 节拍，图片异步完成时即使暂停且没有额外媒体回调也会重新取值。工作任务不在 UI 线程执行图片 IO 或压缩。

## 验证与参考

本机执行 `build-local.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug') -NoDaemon`：577 项单测通过，lint 0 errors / 109 warnings（保留既有 baseline）；`git diff --check` 通过。未执行 assemble/package APK 任务，脚本末尾打印的 APK 路径是已有文件。

测试覆盖惰性读取、缓存复用、编码字节边界、旧结果/排队淘汰、关闭中断、SETUP/START/分片重组、缓存命中、切歌取消、窗口回退、暂停续传、超时、ID 用尽、字段订阅、无效/缺失文件会话、可选包无 ACK 不触发控制死亡，以及真实蓝牙通道和 WiFi 虚拟链路往返。Android Bitmap 编解码、真实播放器内容提供者和车机 UI 仍需设备验证。

协议交叉核对使用仓库已有 iPhone 空态帧，以及独立接收端实现：

- https://github.com/shilapi/xcertplay/blob/master/shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2Messages.kt
- https://github.com/shilapi/xcertplay/blob/master/shared/src/main/java/com/shilapi/xcertplay/iap2/session/Iap2FileTransferReceiver.kt
- https://developer.android.com/reference/android/media/MediaMetadata

未改动原始握手空态常量；专辑名仍位于 0x0006，封面引用不占用该字段。
