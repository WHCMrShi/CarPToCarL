package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConnectionResourcesTest {
    @Test fun closeIsIdempotentAndLateRegistrationClosesImmediately() {
        val scope = ConnectionResources()
        val calls = AtomicInteger()
        assertTrue(scope.own(AutoCloseable { calls.incrementAndGet() }))
        scope.close()
        scope.close()
        assertEquals(1, calls.get())
        assertFalse(scope.own(AutoCloseable { calls.incrementAndGet() }))
        assertEquals(2, calls.get())
        assertNull(scope.start("closed") { throw AssertionError("must not start") })
    }

    @Test fun releasedResourceIsNotRetainedOrClosedByOldScope() {
        val scope = ConnectionResources()
        val calls = AtomicInteger()
        val resource = AutoCloseable { calls.incrementAndGet() }
        scope.own(resource)
        scope.release(resource)
        scope.close()
        assertEquals(0, calls.get())
    }

    @Test fun closureInterruptsWaitingWorkersEvenIfAResourceThrows() {
        val scope = ConnectionResources()
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val closed = AtomicInteger()
        scope.own(AutoCloseable { throw IOException("close failure") })
        scope.own(AutoCloseable { closed.incrementAndGet() })
        scope.start("waiter") {
            try {
                entered.countDown()
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
            } finally {
                exited.countDown()
            }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        scope.close()
        assertTrue(exited.await(3, TimeUnit.SECONDS))
        assertEquals(1, closed.get())
    }

    @Test fun ownedSocketClosureUnblocksRealBlockingReadWithoutIdleTimeout() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { source ->
                server.accept().use { input ->
                    val scope = ConnectionResources()
                    val entered = CountDownLatch(1)
                    val exited = CountDownLatch(1)
                    scope.own(input)
                    scope.start("reader") {
                        try {
                            entered.countDown()
                            input.getInputStream().read()
                        } catch (_: IOException) {
                        } finally {
                            exited.countDown()
                        }
                    }
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    scope.close()
                    assertTrue(exited.await(3, TimeUnit.SECONDS))
                    assertTrue(input.isClosed)
                    source.soTimeout = 2000
                    assertEquals(-1, source.getInputStream().read())
                }
            }
        }
    }

    @Test fun uniqueLoopsStartOnceWithinScopeAndNewScopeRemainsIndependent() {
        val old = ConnectionResources()
        val fresh = ConnectionResources()
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val calls = AtomicInteger()
        val first =
            fresh.start("heartbeat", unique = true) {
                calls.incrementAndGet()
                try {
                    entered.countDown()
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                } finally {
                    exited.countDown()
                }
            }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertSame(first, fresh.start("heartbeat", unique = true) { calls.incrementAndGet() })
            old.close()
            assertFalse(fresh.isClosed)
            assertTrue(first!!.isAlive)
            assertEquals(1, calls.get())
        } finally {
            fresh.close()
        }
        assertTrue(exited.await(3, TimeUnit.SECONDS))
    }

    @Test fun registerRacingCloseNeverLeavesResourceUnclosed() {
        repeat(30) {
            val scope = ConnectionResources()
            val go = CountDownLatch(1)
            val closed = AtomicInteger()
            val own =
                Thread {
                    go.await()
                    scope.own(AutoCloseable { closed.incrementAndGet() })
                }
            val stop =
                Thread {
                    go.await()
                    scope.close()
                }
            own.start()
            stop.start()
            go.countDown()
            own.join(3000)
            stop.join(3000)
            assertFalse(own.isAlive)
            assertFalse(stop.isAlive)
            assertEquals(1, closed.get())
        }
    }
}
