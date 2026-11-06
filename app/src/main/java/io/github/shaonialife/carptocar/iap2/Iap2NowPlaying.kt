package io.github.shaonialife.carptocar.iap2

/**
 * iAP2「正在播放」信息（0x5001 NowPlayingUpdate）的载荷与编码。
 *
 * 字段布局来自 iAP2 NowPlaying 规范（MFi Accessory Interface Specification；
 * 专辑字段与本仓库 iPhone 空态原始帧及 xcertplay 接收端交叉核对；旧 iap2-rs 专辑映射有误）：
 *
 *   顶层 param 0x0000 = media item 组：
 *     0x0001 title(string) · 0x0004 duration(u32 ms) · 0x000C artist(string) ·
 *     0x0006 album(string)
 *   顶层 param 0x0001 = playback 组：
 *     0x0000 status(u8: 0 停止/1 播放/2 暂停) · 0x0001 elapsed(u32 ms) ·
 *     0x0005 shuffle(u8) · 0x0006 repeat(u8) · 0x0007 appName(string)
 *
 * 数据源可以是 CarLife 内置播放器经 0x10035 推来的媒体信息，或系统媒体会话
 * （MediaSessionsState）——由各链路的 provider 决定。
 */
data class CarPlayNowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Int,
    val status: Int,
    val elapsedMs: Int,
    val shuffleMode: Int = 0,
    val repeatMode: Int = 0,
    val appName: String?,
)

object Iap2NowPlaying {
    /** 播放状态枚举（与 iap2-rs PlaybackStatus 一致）。 */
    const val STATUS_STOPPED = 0
    const val STATUS_PLAYING = 1
    const val STATUS_PAUSED = 2

    /**
     * 构造 0x5001 NowPlayingUpdate 完整帧（带真实歌名/歌手/专辑/播放状态）。
     * 空字符串字段仍给一个空串 param，保持结构与空态常量同构。
     */
    fun encode(np: CarPlayNowPlaying): ByteArray = Iap2Csm.encode(Iap2MessageIds.NOW_PLAYING_UPDATE, body(np))

    /**
     * 只返回**参数体**（不含 `0x4040 | len | msgId` 帧头）。
     *
     * 走 `send(...)/sendFireAndForget(...)` 这类「按消息 ID 发体」的链路时必须用它：
     * 那些方法会自己补帧头，把 [encode] 的完整帧当体再封一次，对端解析出来就是
     * `0x4040 0x5001 ...` 这种非法参数组。WinPlay 1.2.4 实测：收到双重封包的 0x5001
     * 后其「接收进程异常」直接退出 → 蓝牙桥接被拆 → 车机不停重连（2026-10-25）。
     */
    fun body(np: CarPlayNowPlaying): ByteArray {
        val mediaItem =
            Iap2Csm.string(0x0001, np.title.orEmpty()) +
                Iap2Csm.u32(0x0004, np.durationMs.toLong()) +
                Iap2Csm.string(0x000C, np.artist.orEmpty()) +
                Iap2Csm.string(0x0006, np.album.orEmpty())
        val playback =
            Iap2Csm.u8(0x0000, np.status) +
                Iap2Csm.u32(0x0001, np.elapsedMs.toLong()) +
                Iap2Csm.u8(0x0005, np.shuffleMode) +
                Iap2Csm.u8(0x0006, np.repeatMode) +
                Iap2Csm.string(0x0007, np.appName.orEmpty())
        return Iap2Csm.param(0x0000, mediaItem) + Iap2Csm.param(0x0001, playback)
    }
}
