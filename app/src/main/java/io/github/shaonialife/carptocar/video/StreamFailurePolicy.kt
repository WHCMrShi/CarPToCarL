package io.github.shaonialife.carptocar.video

/** 局部写失败先尝试有界恢复；穷尽后才向会话所有者发送终态通知。 */
internal class StreamFailurePolicy(private val recover: () -> Boolean, private val finish: (String) -> Unit) {
    fun failed(reason: String): Boolean {
        if (recover()) return true
        finish(reason)
        return false
    }
}
