package io.github.shaonialife.carptocar.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Iap2ChannelCloseTest {
    @Test fun closeReleasesBlockedWriteBeforeTakingChannelLockAndClosesOwnedWorker() {
        val pair = InMemoryStreamPair()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val ioClosed = AtomicBoolean()
        val ownedClosed = AtomicBoolean()
        val stream = object : DuplexStream {
            override fun read(buffer: ByteArray): Int = pair.sideA.read(buffer)
            override fun write(bytes: ByteArray) {
                if (Thread.currentThread().name == "blocked-writer") {
                    entered.countDown()
                    release.await()
                    if (ioClosed.get()) throw IOException("closed")
                }
                pair.sideA.write(bytes)
            }
            override fun close() {
                closes.incrementAndGet()
                ioClosed.set(true)
                release.countDown()
                pair.sideA.close()
            }
        }
        val phone = Iap2LinkChannel(stream, "close-phone")
        val car = Iap2LinkChannel(pair.sideB, "close-car")
        var writer: Thread? = null
        var closer: Thread? = null
        try {
            assertTrue(phone.awaitReady(3000))
            assertTrue(car.awaitReady(3000))
            phone.own(AutoCloseable { ownedClosed.set(true) })
            writer = Thread({ phone.sendControl(byteArrayOf(1), awaitAck = false) }, "blocked-writer").apply { start() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            closer = Thread { phone.close() }.apply { start() }
            closer.join(3000)
            writer.join(3000)
            assertFalse("关闭不得等待阻塞写持有的锁", closer.isAlive)
            assertFalse(writer.isAlive)
            assertTrue(ownedClosed.get())
            assertFalse(phone.isAlive)
            phone.close()
            assertEquals(1, closes.get())
        } finally {
            release.countDown()
            phone.close(); car.close()
            writer?.join(3000); closer?.join(3000)
        }
    }
}
