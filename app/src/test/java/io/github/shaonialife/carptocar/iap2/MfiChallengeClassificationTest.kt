package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.transport.InMemoryStreamPair
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production challenge loop through real in-memory link framing; no new production timing hooks. */
class MfiChallengeClassificationTest {
    @Test fun transportLossPreservesSuccessfulAndOtherLengthEvidence() {
        val identity = "q4-mfi-transport-known"
        MfiLengthMemory.forget(null, identity)
        MfiLengthMemory.recordSuccess(null, identity, 20)
        MfiLengthMemory.recordSilent(null, identity, 32)
        MfiLengthMemory.recordRejected(null, identity, 16)
        val before = MfiLengthMemory.load(null, identity)
        Fixture(identity).use { f ->
            f.start()
            assertEquals(20, f.challenge().size)
            f.disconnect()
            f.finish(expectFailure = true)
            assertEquals(before, MfiLengthMemory.load(null, identity))
        }
    }

    @Test fun transportLossWithEmptyMemoryDoesNotCreateLengthEvidence() {
        val identity = "q4-mfi-transport-empty"
        MfiLengthMemory.forget(null, identity)
        Fixture(identity).use { f ->
            f.start()
            assertEquals(20, f.challenge().size)
            f.disconnect()
            f.finish(expectFailure = true)
            assertTrue(MfiLengthMemory.load(null, identity).isEmpty)
        }
    }

    @Test fun cachedOtherLengthCannotOverrideQ4TwentyBytes() {
        val identity = "q4-mfi-fixed-with-old-cache"
        MfiLengthMemory.forget(null, identity)
        MfiLengthMemory.recordSuccess(null, identity, 32)
        Fixture(identity).use { f ->
            f.start()
            assertEquals(20, f.challenge().size)
            assertEquals(32, MfiLengthMemory.load(null, identity).succeeded)
            f.reply(Iap2MessageIds.MFI_RESPONSE)
            f.finish()
            assertEquals(20, MfiLengthMemory.load(null, identity).succeeded)
        }
    }

    @Test fun coldRejectionRetriesSameChallengeAndCanRecover() {
        val identity = "q4-mfi-cold-rejection"
        MfiLengthMemory.forget(null, identity)
        MfiLengthMemory.recordSuccess(null, identity, 20)
        Fixture(identity).use { f ->
            f.start()
            val first = f.challenge()
            f.reply(Iap2MessageIds.MFI_AUTHENTICATION_FAILED)
            assertArrayEquals(first, f.challenge())
            assertEquals(20, first.size)
            assertEquals(20, MfiLengthMemory.load(null, identity).succeeded)
            f.reply(Iap2MessageIds.MFI_RESPONSE)
            f.finish()
            assertEquals(MfiLengthMemory.Entry(succeeded = 20), MfiLengthMemory.load(null, identity))
        }
    }

    @Test fun timeoutRetransmitsSameChallengeSoLateSignatureHasUniqueOwnership() {
        val identity = "q4-mfi-late-signature"
        MfiLengthMemory.forget(null, identity)
        Fixture(identity).use { f ->
            f.start()
            val first = f.challenge()
            assertArrayEquals(first, f.challenge()) // real production 6s timeout, no timing hook
            f.reply(Iap2MessageIds.MFI_RESPONSE) // may answer either send: both have identical bytes
            f.finish()
            assertEquals(20, MfiLengthMemory.load(null, identity).succeeded)
        }
    }

    @Test fun repeatedRejectionIsBoundedAndKeepsSuccessfulMemory() {
        val identity = "q4-mfi-cold-exhausted"
        MfiLengthMemory.forget(null, identity)
        MfiLengthMemory.recordSuccess(null, identity, 20)
        Fixture(identity).use { f ->
            f.start()
            val first = f.challenge()
            f.reply(Iap2MessageIds.MFI_AUTHENTICATION_FAILED)
            assertArrayEquals(first, f.challenge())
            f.reply(Iap2MessageIds.MFI_AUTHENTICATION_FAILED)
            f.finish(expectFailure = true)
            assertNull(f.car.recvControl(200))
            assertEquals(MfiLengthMemory.Entry(succeeded = 20), MfiLengthMemory.load(null, identity))
        }
    }

    @Test fun overallDeadlineStopsRetryWithoutLearningFailure() {
        val identity = "q4-mfi-deadline"
        MfiLengthMemory.forget(null, identity)
        Fixture(identity).use { f ->
            f.start(budgetMillis = 300)
            assertEquals(20, f.challenge().size)
            f.finish(expectFailure = true)
            assertTrue(MfiLengthMemory.load(null, identity).isEmpty)
        }
    }

    private class Fixture(private val identity: String) : AutoCloseable {
        private val pair = InMemoryStreamPair()
        private val phone = Iap2LinkChannel(pair.sideA, name = "mfi-classification-phone")
        val car = Iap2LinkChannel(pair.sideB, name = "mfi-classification-car")
        val lines = CopyOnWriteArrayList<String>()
        private val failure = AtomicReference<Throwable?>()
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()
        private var worker: Thread? = null

        fun start(budgetMillis: Long = 20_000) {
            assertTrue(phone.awaitReady(5_000))
            assertTrue(car.awaitReady(5_000))
            val session = Iap2SenderSession(phone, lines::add)
            // Keep the challenge method private and avoid changing the production session API for tests.
            val method = Iap2SenderSession::class.java.getDeclaredMethod(
                "runMfiChallenge", java.lang.Long.TYPE, String::class.java,
            ).apply { isAccessible = true }
            worker = Thread({
                try {
                    method.invoke(session, System.nanoTime() + budgetMillis * 1_000_000L, identity)
                } catch (error: InvocationTargetException) {
                    failure.set(error.targetException)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }, "mfi-classification-challenge").apply { isDaemon = true; start() }
        }

        fun challenge(): ByteArray {
            val deadline = System.nanoTime() + 12_000_000_000L
            while (pending.isEmpty() && System.nanoTime() < deadline) {
                car.recvControl(200)?.let { pending.addAll(parser.offer(it)) }
                failure.get()?.let { throw AssertionError("challenge loop failed", it) }
            }
            assertTrue("challenge not received", pending.isNotEmpty())
            val frame = pending.removeFirst()
            assertEquals(Iap2MessageIds.MFI_REQUEST_CHALLENGE, frame.messageId)
            return Iap2Csm.parseParams(frame.body).single { it.id == 0 }.value
        }

        fun reply(id: Int) {
            val body = if (id == Iap2MessageIds.MFI_RESPONSE) Iap2Csm.param(0, ByteArray(128) { 7 }) else ByteArray(0)
            assertTrue(car.sendControl(Iap2Csm.encode(id, body)))
        }

        fun disconnect() { phone.close(); car.close() }

        fun finish(expectFailure: Boolean = false) {
            worker!!.join(5_000)
            assertFalse("challenge loop did not finish", worker!!.isAlive)
            if (expectFailure) assertTrue("expected bounded MFi failure: ${failure.get()}", failure.get() is java.io.IOException)
            else failure.get()?.let { throw AssertionError("challenge loop failed", it) }
        }

        override fun close() {
            phone.close()
            car.close()
            worker?.join(3_000)
        }
    }
}
