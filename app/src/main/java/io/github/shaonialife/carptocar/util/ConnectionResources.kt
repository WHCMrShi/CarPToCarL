package io.github.shaonialife.carptocar.util

/** 单代连接的资源所有者。关闭不等待线程，先关 IO 再中断等待；晚注册的资源立即关闭。 */
internal class ConnectionResources : AutoCloseable {
    private val lock = Any()

    @Volatile var isClosed = false
        private set
    private val resources = ArrayList<AutoCloseable>()
    private val threads = HashSet<Thread>()

    fun own(resource: AutoCloseable): Boolean {
        synchronized(lock) {
            if (!isClosed) {
                resources.add(resource)
                return true
            }
        }
        runCatching { resource.close() }
        return false
    }

    fun release(resource: AutoCloseable) {
        synchronized(lock) { resources.remove(resource) }
    }

    fun start(
        name: String,
        unique: Boolean = false,
        body: () -> Unit,
    ): Thread? =
        synchronized(lock) {
            if (isClosed) return null
            if (unique) threads.firstOrNull { it.name == name && it.isAlive }?.let { return it }
            val thread =
                Thread({
                    try {
                        if (!isClosed) body()
                    } finally {
                        synchronized(lock) { threads.remove(Thread.currentThread()) }
                    }
                }, name).apply { isDaemon = true }
            threads.add(thread)
            thread.start()
            thread
        }

    override fun close() {
        val closing: Pair<List<AutoCloseable>, List<Thread>> =
            synchronized(lock) {
                if (isClosed) return
                isClosed = true
                (resources.toList() to threads.toList()).also {
                    resources.clear()
                    threads.clear()
                }
            }
        closing.first.forEach { runCatching { it.close() } }
        closing.second.forEach { it.interrupt() }
    }
}
