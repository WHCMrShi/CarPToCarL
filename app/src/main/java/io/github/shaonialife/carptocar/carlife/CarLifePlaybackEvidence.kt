package io.github.shaonialife.carptocar.carlife

/** 带来源与时间的系统会话快照；未知/缓冲状态不伪装成暂停。时间使用 uptimeMillis。 */
data class CarLifePlaybackSnapshot(
    val playing: Boolean?, val observedAt: Long, val changedAt: Long,
    val matchesTrack: Boolean, val source: String,
)

internal class CarLifePlaybackEvidence {
    private var previous: Int? = null
    private var advancedAt: Long? = null
    private var engineAt: Long? = null
    private var engineActive = false

    @Synchronized fun progress(now: Long, value: Int) {
        previous?.let { old ->
            if (value > old) advancedAt = now
            else if (value < old) advancedAt = null
        }
        previous = value
    }
    @Synchronized fun engine(now: Long, active: Boolean) { engineAt = now; engineActive = active }
    @Synchronized fun expected(now: Long, external: CarLifePlaybackSnapshot?): Boolean {
        val scoped = external?.takeIf { it.matchesTrack && now - it.observedAt in 0..5_000 }
        val progressAt = advancedAt?.takeIf { now - it in 0..5_000 }
        val latestNative = maxOf(engineAt ?: Long.MIN_VALUE, progressAt ?: Long.MIN_VALUE)
        if (scoped?.playing == false && scoped.changedAt > latestNative) return false
        if (engineActive) return true
        if (progressAt != null && progressAt > (engineAt ?: Long.MIN_VALUE)) return true
        return scoped?.playing == true && scoped.changedAt >= (engineAt ?: Long.MIN_VALUE)
    }
    @Synchronized fun reset() { previous = null; advancedAt = null }
}
