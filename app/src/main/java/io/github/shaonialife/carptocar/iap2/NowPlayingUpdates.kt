package io.github.shaonialife.carptocar.iap2

/** 每条链路独立去重；状态锁不跨越发送 IO，退订/退役不能等待阻塞写。 */
internal class NowPlayingUpdates {
    private var subscribed = false
    private var generation = 0L
    private var sending: Long? = null
    private var last: CarPlayNowPlaying? = null

    @Synchronized
    fun subscribe() {
        generation++
        subscribed = true
        last = null
    }

    @Synchronized
    fun unsubscribe() {
        generation++
        subscribed = false
        last = null
    }

    fun push(value: CarPlayNowPlaying?, send: (CarPlayNowPlaying) -> Boolean): Boolean {
        val version = synchronized(this) {
            if (!subscribed || value == null || value == last || sending == generation) return false
            generation.also { sending = it }
        }
        var success = false
        try {
            success = send(value!!)
            return success
        } finally {
            synchronized(this) {
                if (sending == version) sending = null
                // 旧代发送即使稍后完成，也不得污染新订阅的缓存。
                if (generation == version && success) last = value
            }
        }
    }
}
