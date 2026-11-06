package io.github.shaonialife.carptocar.iap2

/** 每条链路独立的订阅与去重；只有成功提交的完整状态才进入缓存。 */
internal class NowPlayingUpdates {
    private var subscribed = false
    private var last: CarPlayNowPlaying? = null

    @Synchronized
    fun subscribe() {
        subscribed = true
        last = null
    }

    @Synchronized
    fun unsubscribe() {
        subscribed = false
        last = null
    }

    @Synchronized
    fun push(value: CarPlayNowPlaying?, send: (CarPlayNowPlaying) -> Boolean): Boolean {
        if (!subscribed || value == null || value == last) return false
        if (!send(value)) return false
        last = value
        return true
    }
}
