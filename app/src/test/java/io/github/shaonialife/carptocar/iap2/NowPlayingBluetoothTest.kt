package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.transport.InMemoryStreamPair
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NowPlayingBluetoothTest {
    @Test fun subscribedBluetoothDeliversProgressAndHonorsStopAndResubscribe() {
        val pair = InMemoryStreamPair()
        val phone = Iap2LinkChannel(pair.sideA, name = "np-phone")
        val car = Iap2LinkChannel(pair.sideB, name = "np-car")
        val value = AtomicReference(CarPlayNowPlaying("song", "artist", null, 180_000, 1, 10_000, appName = "player"))
        val stopped = CountDownLatch(1)
        val session = Iap2SenderSession(phone, log = {
            if (it.startsWith("iap2 rx=0x5002")) stopped.countDown()
        }, nowPlayingProvider = { value.get() })
        val pump = Thread { session.pumpIncomingForever() }.apply { isDaemon = true }
        val parser = Iap2Csm.Parser()
        fun receive(): Iap2Csm.Frame {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                val bytes = car.recvControl(100) ?: continue
                val frame = parser.offer(bytes).firstOrNull { it.messageId == Iap2MessageIds.NOW_PLAYING_UPDATE }
                if (frame != null) return frame
            }
            throw AssertionError("未收到 0x5001")
        }
        fun assertPlayback(position: Int, status: Int) {
            val frame = receive()
            val group = Iap2Csm.parseParams(frame.body).single { it.id == 1 }
            val params = Iap2Csm.parseParams(group.value)
            assertEquals(position, java.nio.ByteBuffer.wrap(params.single { it.id == 1 }.value).int)
            assertEquals(status, params.single { it.id == 0 }.value.single().toInt())
        }
        try {
            assertTrue(phone.awaitReady(3000))
            assertTrue(car.awaitReady(3000))
            pump.start()
            session.pushNowPlaying()
            assertNull(car.recvControl(100))
            assertTrue(car.sendControl(Iap2Csm.encode(0x5000)))
            assertPlayback(10_000, 1)
            value.set(value.get().copy(elapsedMs = 11_000))
            session.pushNowPlaying()
            assertPlayback(11_000, 1)
            value.set(value.get().copy(status = 2))
            session.pushNowPlaying()
            assertPlayback(11_000, 2)
            session.pushNowPlaying()
            assertNull(car.recvControl(100))
            assertTrue(car.sendControl(Iap2Csm.encode(0x5002)))
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            value.set(value.get().copy(elapsedMs = 5000))
            session.pushNowPlaying()
            assertNull(car.recvControl(100))
            assertTrue(car.sendControl(Iap2Csm.encode(0x5000)))
            assertPlayback(5000, 2)
        } finally {
            phone.close()
            car.close()
            pump.join(3000)
        }
    }
}
