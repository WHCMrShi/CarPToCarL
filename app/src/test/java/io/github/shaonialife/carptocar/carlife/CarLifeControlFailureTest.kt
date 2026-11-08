package io.github.shaonialife.carptocar.carlife

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class CarLifeControlFailureTest {
    @Test fun partialControlWriteRetiresHuAndDoesNotReplayFrame() {
        val lines = mutableListOf<String>()
        val hu = CarLifeHuClient(log = lines::add, onVideoFrame = { _, _, _ -> })
        val running = CarLifeHuClient::class.java.getDeclaredField("running").apply { isAccessible = true }.get(hu) as AtomicBoolean
        running.set(true)
        var writes = 0
        val output = object : OutputStream() {
            override fun write(value: Int) { writes++; throw IOException("partial write") }
        }
        CarLifeHuClient::class.java.getDeclaredField("cmdOut").apply { isAccessible = true }.set(hu, output)
        val send = CarLifeHuClient::class.java.getDeclaredMethod("send", Int::class.javaPrimitiveType, ByteArray::class.java).apply { isAccessible = true }
        assertEquals(false, send.invoke(hu, CarLifeHudProtocol.START, byteArrayOf(8, 1)))
        assertFalse(hu.isRunning)
        assertEquals(false, send.invoke(hu, CarLifeHudProtocol.START, byteArrayOf(8, 1)))
        assertEquals(1, writes)
        assertTrue(lines.any { it.contains("frame boundary uncertain") })
    }
}
