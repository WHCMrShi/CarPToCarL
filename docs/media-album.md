# 媒体专辑名（独立于封面传输）

采集 MediaMetadata.METADATA_KEY_ALBUM，追加默认 null 的快照字段，经纯映射传入已有 NowPlaying 模型。专辑晚到或同名歌曲专辑变化会通知内容监听者，协议仍复用现有异步发送、订阅和完整载荷去重。

原编码器误把专辑 UTF-8 字符串放在 0x001A。仓库已有 iPhone 空态帧（VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE）中专辑位于 0x0006，而 0x001A 是单字节封面传输引用；xcertplay 的 Iap2NowPlayingAccumulator 也采用该映射。此提交改正专辑字段，不添加任何封面引用或文件传输。

参考：https://github.com/shilapi/xcertplay/blob/master/shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2Messages.kt

主源专辑缺失时留空，不把无法确认同曲的 CarLife 信息拼接进来；主源整体不可用时保留原 CarLife 整条兜底。新歌无专辑时清空旧值。没有修改 UI 布局、数据库、音视频或触摸处理，没有增加轮询线程。

测试覆盖协议字段位置、UTF-8、同曲专辑晚到、同名不同专辑、空值清除和播放位置/状态保持。尚未实车确认显示行为；不构建 APK。
