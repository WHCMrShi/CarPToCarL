package io.github.shaonialife.carptocar.net

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class CtrlListenerTest {
    @Test fun announcementRefreshesKeepPortAndAcceptSocketUsable() {
        val listener = CtrlListener()
        try {
            val first = listener.acquire()
            assertTrue(first.fresh)
            repeat(4) {
                val refresh = listener.acquire()
                assertFalse(refresh.fresh)
                assertSame(first.socket, refresh.socket)
                Socket("127.0.0.1", first.socket.localPort).use { phone ->
                    first.socket.accept().use { receiver ->
                        phone.getOutputStream().write(7)
                        assertEquals(7, receiver.getInputStream().read())
                    }
                }
            }
        } finally { listener.close() }
    }
    @Test fun explicitStopRetiresOldListenerAndNextStartOwnsNewSocket() {
        val listener = CtrlListener()
        val old = listener.acquire().socket
        listener.close()
        assertTrue(old.isClosed)
        assertNull(listener.current)
        try {
            val next = listener.acquire()
            assertTrue(next.fresh)
            assertNotSame(old, next.socket)
            assertFalse(next.socket.isClosed)
        } finally { listener.close() }
    }
}
