package io.github.shaonialife.carptocar.net

/** 单个用户连接轮次的邀请；记录/消费/失效在同一把锁内，旧轮次不能清除新信号。 */
internal class PendingCtrlInvite(
    private val clock: () -> Long,
    private val ttlMs: Long = 30_000L,
) {
    private var generation = 0L
    private var active = false
    private var invitedAt: Long? = null
    private var recentAt: Long? = null

    @Synchronized
    fun begin() {
        // startConnection 本身幂等：重复点击不能使正在等待的交接失效。
        if (active) return
        generation++
        active = true
        invitedAt = null
        recentAt = null
    }

    @Synchronized
    fun end() {
        generation++
        active = false
        invitedAt = null
        recentAt = null
    }

    @Synchronized
    fun currentGeneration(): Long? = generation.takeIf { active }

    @Synchronized
    fun record(expectedGeneration: Long, verifiedTarget: Boolean = true): Boolean {
        if (!active || generation != expectedGeneration) return false
        invitedAt = clock()
        recentAt = if (verifiedTarget) invitedAt else null
        return true
    }

    @Synchronized fun invalidate(expectedGeneration: Long): Boolean {
        if (!active || generation != expectedGeneration) return false
        invitedAt = null
        recentAt = null
        return true
    }

    /** Recent evidence survives consumption, but not explicit invalidation or a user round change. */
    @Synchronized fun recentAge(expectedGeneration: Long): Long? {
        if (!active || generation != expectedGeneration) return null
        return recentAt?.let { (clock() - it).takeIf { age -> age in 0..ttlMs } }
    }

    /** Diagnostics only: same TTL/generation checks as consume, without consuming or refreshing. */
    @Synchronized fun peekAge(expectedGeneration: Long): Long? {
        if (!active || generation != expectedGeneration) return null
        val at = invitedAt ?: return null
        return (clock() - at).takeIf { it in 0..ttlMs }
    }

    /** 返回本轮有效邀请的年龄；无邀请、过期和旧轮次均返回 null。 */
    @Synchronized
    fun consume(expectedGeneration: Long): Long? {
        if (!active || generation != expectedGeneration) return null
        val at = invitedAt ?: return null
        invitedAt = null
        return (clock() - at).takeIf { it in 0..ttlMs }
    }
}
