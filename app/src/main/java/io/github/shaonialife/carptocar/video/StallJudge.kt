package io.github.shaonialife.carptocar.video

/** 仅供停摆观测使用，不触发恢复。 */
internal object StallJudge {
    /** -1 表示本轮尚未收到帧。 */
    fun frameAgeMs(lastFrameAt: Long, now: Long): Long =
        if (lastFrameAt <= 0L) -1L else (now - lastFrameAt).coerceAtLeast(0L)
}
