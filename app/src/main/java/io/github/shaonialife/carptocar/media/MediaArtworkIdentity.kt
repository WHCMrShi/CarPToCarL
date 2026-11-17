package io.github.shaonialife.carptocar.media

/**
 * 封面归属不是显示标题：QQ 音乐等会用逐行歌词替换 title。
 * 缺少 mediaId 且歌手/专辑/时长也完全相同的歌曲无法可靠区分；此时沿用归属，
 * 由元数据 revision 和真实图片内容更新封面，不把标题变化猜成换歌。
 */
internal data class MediaArtworkIdentity(
    private val session: String, private val pkg: String, private val mediaId: String?,
    private val artist: String?, private val album: String?, private val durationMs: Long?,
) {
    companion object {
        fun of(session: String, pkg: String, mediaId: String?, artist: String?, album: String?, durationMs: Long): MediaArtworkIdentity {
            val id = mediaId?.takeIf { it.isNotBlank() }
            return MediaArtworkIdentity(session, pkg, id,
                if (id == null) artist.orEmpty() else null,
                if (id == null) album.orEmpty() else null,
                if (id == null) durationMs else null)
        }
    }
}
