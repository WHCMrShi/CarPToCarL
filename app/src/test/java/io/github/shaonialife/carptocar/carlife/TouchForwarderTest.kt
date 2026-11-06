package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TouchForwarderTest {
    private val down = CarLifeProtocol.TOUCH_DOWN
    private val move = CarLifeProtocol.TOUCH_MOVE
    private val up = CarLifeProtocol.TOUCH_UP
    private val compound = CarLifeProtocol.TOUCH_ACTION

    private class RecordingOutput(
        private val blockAt: Int = 0,
    ) : OutputStream() {
        val frames = LinkedBlockingQueue<ByteArray>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var writes = 0

        override fun write(value: Int) {
            error("must write complete frame")
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writes++
            if (writes == blockAt) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            frames.add(bytes.copyOfRange(offset, offset + length))
        }

        fun next(): ByteArray = checkNotNull(frames.poll(3, TimeUnit.SECONDS)) { "missing touch frame" }
    }

    private fun expected(
        service: Int,
        x: Int,
        y: Int,
        action: Int = -1,
    ): ByteArray =
        CarLifeProtocol.packCommand(
            service,
            if (service == compound) {
                CarLifeProtocol.touchAction(action, x, y)
            } else {
                CarLifeProtocol.touchPoint(x, y)
            },
        )

    @Test fun coalescesMovesButPreservesDownUpAndLatestCoordinates() {
        val out = RecordingOutput()
        val sender = TouchForwarder(out)
        try {
            sender.enqueue(down, 1, 2)
            repeat(1000) { sender.enqueue(move, it, it + 1) }
            sender.enqueue(up, 999, 1000)
            sender.start()
            assertArrayEquals(expected(down, 1, 2), out.next())
            assertArrayEquals(expected(move, 999, 1000), out.next())
            assertArrayEquals(expected(up, 999, 1000), out.next())
        } finally {
            sender.stop()
        }
    }

    @Test fun doesNotMergeDifferentWireFormats() {
        val out = RecordingOutput()
        val sender = TouchForwarder(out)
        try {
            sender.enqueue(move, 1, 2)
            sender.enqueue(compound, 3, 4, 2)
            sender.enqueue(up, 3, 4)
            sender.start()
            assertArrayEquals(expected(move, 1, 2), out.next())
            assertArrayEquals(expected(compound, 3, 4, 2), out.next())
            assertArrayEquals(expected(up, 3, 4), out.next())
        } finally {
            sender.stop()
        }
    }

    @Test fun clampsCoordinatesExactlyAsPreviousSender() {
        val out = RecordingOutput()
        val sender = TouchForwarder(out)
        try {
            sender.enqueue(down, -1, 9000)
            sender.start()
            assertArrayEquals(expected(down, 0, 8191), out.next())
        } finally {
            sender.stop()
        }
    }

    @Test fun fullQueueEvictsMoveBeforeAnyBoundary() {
        val out = RecordingOutput()
        val sender = TouchForwarder(out)
        try {
            sender.enqueue(down, 1, 2)
            sender.enqueue(move, 2, 3)
            sender.enqueue(up, 3, 4)
            repeat(3) {
                sender.enqueue(down, 1, 2)
                if (it < 2) sender.enqueue(up, 3, 4)
            }
            sender.enqueue(up, 3, 4)
            sender.start()
            repeat(4) {
                assertArrayEquals(expected(down, 1, 2), out.next())
                assertArrayEquals(expected(up, 3, 4), out.next())
            }
        } finally {
            sender.stop()
        }
    }

    @Test fun overflowDuringBlockedWriteReleasesSentGestureBeforeNextDown() {
        val out = RecordingOutput(1)
        val overflow = AtomicInteger()
        val sender = TouchForwarder(out, onOverflow = { overflow.incrementAndGet() })
        try {
            sender.start()
            sender.enqueue(down, 10, 20)
            assertTrue(out.entered.await(3, TimeUnit.SECONDS))
            repeat(4) {
                sender.enqueue(up, 1, 2)
                sender.enqueue(down, 3, 4)
            }
            sender.enqueue(up, 3, 4) // full boundary queue: reset, physical UP already observed
            sender.enqueue(down, 30, 40)
            sender.enqueue(up, 30, 40)
            out.release.countDown()
            assertArrayEquals(expected(down, 10, 20), out.next())
            assertArrayEquals(expected(up, 10, 20), out.next())
            assertArrayEquals(expected(down, 30, 40), out.next())
            assertArrayEquals(expected(up, 30, 40), out.next())
            assertEquals(1, overflow.get())
        } finally {
            out.release.countDown()
            sender.stop()
        }
    }

    @Test fun compoundOverflowLiftsSecondPointerBeforePrimaryAndWaitsForPhysicalUp() {
        val out = RecordingOutput(2)
        val sender = TouchForwarder(out)
        try {
            sender.start()
            sender.enqueue(down, 10, 20)
            sender.enqueue(compound, 30, 40, 261)
            assertTrue(out.entered.await(3, TimeUnit.SECONDS))
            repeat(4) {
                sender.enqueue(up, 1, 2)
                sender.enqueue(down, 3, 4)
            }
            sender.enqueue(move, 100, 100) // overflow without physical release
            sender.enqueue(down, 200, 200) // ignored until primary UP
            sender.enqueue(up, 200, 200)
            sender.enqueue(down, 50, 60)
            sender.enqueue(up, 50, 60)
            out.release.countDown()
            assertArrayEquals(expected(down, 10, 20), out.next())
            assertArrayEquals(expected(compound, 30, 40, 261), out.next())
            assertArrayEquals(expected(compound, 30, 40, 262), out.next())
            assertArrayEquals(expected(compound, 10, 20, 1), out.next())
            assertArrayEquals(expected(down, 50, 60), out.next())
            assertArrayEquals(expected(up, 50, 60), out.next())
        } finally {
            out.release.countDown()
            sender.stop()
        }
    }

    @Test fun stoppedWorkerCannotSendQueuedEventsThroughReplacementConnection() {
        val old = RecordingOutput(1)
        val fresh = RecordingOutput()
        val oldSender = TouchForwarder(old)
        val newSender = TouchForwarder(fresh)
        try {
            oldSender.start()
            oldSender.enqueue(down, 1, 2)
            assertTrue(old.entered.await(3, TimeUnit.SECONDS))
            oldSender.enqueue(move, 3, 4)
            oldSender.stop()
            oldSender.enqueue(up, 3, 4)
            newSender.start()
            newSender.enqueue(down, 50, 60)
            newSender.enqueue(up, 50, 60)
            old.release.countDown()
            assertArrayEquals(expected(down, 50, 60), fresh.next())
            assertArrayEquals(expected(up, 50, 60), fresh.next())
            assertArrayEquals(expected(down, 1, 2), old.next())
            assertTrue(old.frames.isEmpty())
        } finally {
            old.release.countDown()
            oldSender.stop()
            newSender.stop()
        }
    }

    @Test fun writeFailureStopsWorkerAndReportsFailure() {
        val failed = CountDownLatch(1)
        val writes = AtomicInteger()
        val out =
            object : OutputStream() {
                override fun write(value: Int) {
                    error("unexpected single-byte write")
                }

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    writes.incrementAndGet()
                    throw IOException("closed")
                }
            }
        val sender = TouchForwarder(out, onFailure = { failed.countDown() })
        try {
            sender.enqueue(down, 1, 2)
            sender.enqueue(up, 1, 2)
            sender.start()
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertEquals(1, writes.get())
        } finally {
            sender.stop()
        }
    }

    @Test fun retireDiscardsWholeQueuedClicksEvenWhenFrontendAlreadyReleased() {
        val out = RecordingOutput()
        val sender = TouchForwarder(out)
        try {
            sender.enqueue(down, 1, 2)
            sender.enqueue(up, 1, 2)
            sender.retireInput()
            sender.enqueue(down, 50, 60)
            sender.enqueue(up, 50, 60)
            sender.start()
            assertArrayEquals(expected(down, 50, 60), out.next())
            assertArrayEquals(expected(up, 50, 60), out.next())
            assertTrue(out.frames.isEmpty())
        } finally {
            sender.stop()
        }
    }

    @Test fun retireOnSameConnectionLiftsInFlightDownBeforeNewInputWithoutReplayingQueue() {
        val out = RecordingOutput(1)
        val sender = TouchForwarder(out)
        try {
            sender.start()
            sender.enqueue(down, 10, 20)
            assertTrue(out.entered.await(3, TimeUnit.SECONDS))
            sender.enqueue(move, 30, 40)
            sender.enqueue(up, 30, 40)
            sender.retireInput()
            sender.enqueue(down, 50, 60)
            sender.enqueue(up, 50, 60)
            out.release.countDown()
            assertArrayEquals(expected(down, 10, 20), out.next())
            assertArrayEquals(expected(up, 10, 20), out.next())
            assertArrayEquals(expected(down, 50, 60), out.next())
            assertArrayEquals(expected(up, 50, 60), out.next())
            assertTrue(out.frames.isEmpty())
        } finally {
            out.release.countDown()
            sender.stop()
        }
    }

    @Test fun repeatedRetirementDiscardsInterveningQueuedInput() {
        val out = RecordingOutput(1)
        val sender = TouchForwarder(out)
        try {
            sender.start()
            sender.enqueue(down, 10, 20)
            assertTrue(out.entered.await(3, TimeUnit.SECONDS))
            sender.retireInput()
            sender.enqueue(down, 30, 40)
            sender.enqueue(up, 30, 40)
            sender.retireInput()
            sender.enqueue(down, 50, 60)
            sender.enqueue(up, 50, 60)
            out.release.countDown()
            assertArrayEquals(expected(down, 10, 20), out.next())
            assertArrayEquals(expected(up, 10, 20), out.next())
            assertArrayEquals(expected(down, 50, 60), out.next())
            assertArrayEquals(expected(up, 50, 60), out.next())
        } finally {
            out.release.countDown()
            sender.stop()
        }
    }

    @Test fun retireClearsOverflowDropModeWithoutWaitingForOldPhysicalUp() {
        val out = RecordingOutput(1)
        val sender = TouchForwarder(out)
        try {
            sender.start()
            sender.enqueue(down, 10, 20)
            assertTrue(out.entered.await(3, TimeUnit.SECONDS))
            repeat(4) {
                sender.enqueue(up, 1, 2)
                sender.enqueue(down, 3, 4)
            }
            sender.enqueue(move, 30, 40)
            sender.retireInput()
            sender.enqueue(down, 50, 60)
            sender.enqueue(up, 50, 60)
            out.release.countDown()
            assertArrayEquals(expected(down, 10, 20), out.next())
            assertArrayEquals(expected(up, 10, 20), out.next())
            assertArrayEquals(expected(down, 50, 60), out.next())
            assertArrayEquals(expected(up, 50, 60), out.next())
        } finally {
            out.release.countDown()
            sender.stop()
        }
    }

    @Test fun retireOnSameCompoundConnectionLiftsPointersBeforeNewDown() {
        val out = RecordingOutput(2)
        val sender = TouchForwarder(out)
        try {
            sender.start()
            sender.enqueue(down, 10, 20)
            sender.enqueue(compound, 30, 40, 261)
            assertTrue(out.entered.await(3, TimeUnit.SECONDS))
            sender.enqueue(compound, 100, 100, 2)
            sender.enqueue(compound, 100, 100, 1)
            sender.retireInput()
            sender.enqueue(down, 50, 60)
            sender.enqueue(up, 50, 60)
            out.release.countDown()
            assertArrayEquals(expected(down, 10, 20), out.next())
            assertArrayEquals(expected(compound, 30, 40, 261), out.next())
            assertArrayEquals(expected(compound, 30, 40, 262), out.next())
            assertArrayEquals(expected(compound, 10, 20, 1), out.next())
            assertArrayEquals(expected(down, 50, 60), out.next())
            assertArrayEquals(expected(up, 50, 60), out.next())
        } finally {
            out.release.countDown()
            sender.stop()
        }
    }
}
