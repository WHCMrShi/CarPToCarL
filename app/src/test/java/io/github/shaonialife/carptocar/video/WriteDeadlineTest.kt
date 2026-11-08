package io.github.shaonialife.carptocar.video

import org.junit.Assert.*
import org.junit.Test

class WriteDeadlineTest {
    @Test fun expiresOnceWithoutWriterFinishing() {
        val watch = WriteDeadline<Any>(100)
        val socket = Any()
        val write = watch.begin(socket, "frame", 0)
        assertNull(watch.overdue(99))
        assertSame(write, watch.overdue(100))
        assertSame(socket, write.owner)
        assertNull(watch.overdue(200))
    }

    @Test fun oldCompletionCannotClearNewOwner() {
        val watch = WriteDeadline<Any>(100)
        val old = watch.begin(Any(), "frame", 0)
        val replacement = watch.begin(Any(), "config", 50)
        watch.finish(old)
        assertSame(replacement, watch.overdue(150))
    }

    @Test fun completedWriteDoesNotExpire() {
        val watch = WriteDeadline<Any>(100)
        val write = watch.begin(Any(), "keepalive", 0)
        watch.finish(write)
        assertNull(watch.overdue(1_000))
    }

    @Test fun observerCanUnblockAnUnresponsiveLoopbackPeer() {
        java.net.ServerSocket(0).use { server ->
            java.net.Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use { peer ->
                    peer.receiveBufferSize = 1024
                    client.sendBufferSize = 1024
                    val watch = WriteDeadline<java.net.Socket>(100)
                    val entered = java.util.concurrent.CountDownLatch(1)
                    val finished = java.util.concurrent.CountDownLatch(1)
                    val writer = Thread {
                        val lease = watch.begin(client, "frame", 0)
                        entered.countDown()
                        try {
                            val payload = ByteArray(64 * 1024)
                            while (true) client.getOutputStream().write(payload)
                        } catch (_: java.io.IOException) {
                            // The peer never reads. Closing the captured socket releases write().
                        } finally { watch.finish(lease); finished.countDown() }
                    }.apply { isDaemon = true; start() }
                    try {
                        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        assertFalse(finished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                        watch.overdue(100)!!.owner.close()
                        assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    } finally { client.close(); writer.join(2_000) }
                }
            }
        }
    }
}
