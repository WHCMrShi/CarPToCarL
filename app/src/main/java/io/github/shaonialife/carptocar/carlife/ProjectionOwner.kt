package io.github.shaonialife.carptocar.carlife

/** 视频线程启动前绑定归属，独立于画面 attach；只消费当前所有者的一次结束。 */
internal class ProjectionOwner<T : Any> {
    private var current: T? = null
    @Synchronized fun bind(owner: T) { current = owner }
    @Synchronized fun isCurrent(owner: T): Boolean = current === owner
    @Synchronized fun end(owner: T): Boolean {
        if (current !== owner) return false
        current = null
        return true
    }
    @Synchronized fun clear() { current = null }
}
