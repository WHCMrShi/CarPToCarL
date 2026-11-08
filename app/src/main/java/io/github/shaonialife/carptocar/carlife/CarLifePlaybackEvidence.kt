package io.github.shaonialife.carptocar.carlife

/** 元数据到达不代表正在播放；只认明确会话状态、系统音乐活动、START 或连续增长的进度。 */
internal class CarLifePlaybackEvidence {
    private var previous: Int? = null
    private var advancedAt: Long? = null

    @Synchronized fun progress(now: Long, value: Int) {
        previous?.let { old ->
            if (value > old) advancedAt = now
            else if (value < old) advancedAt = null
        }
        previous = value
    }

    @Synchronized fun expected(now: Long, external: Boolean?, engine: Boolean): Boolean =
        external ?: (engine || advancedAt?.let { now - it in 0..5_000 } == true)

    @Synchronized fun reset() { previous = null; advancedAt = null }

    companion object {
        fun external(sessionPlaying: Boolean?, systemMusicActive: Boolean): Boolean? =
            sessionPlaying ?: true.takeIf { systemMusicActive }
    }
}
