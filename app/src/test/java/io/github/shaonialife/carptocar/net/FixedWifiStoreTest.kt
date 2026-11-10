package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession.WifiConfiguration
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class FixedWifiStoreTest {
    private class Fixture {
        val bytes = mutableMapOf<String, ByteArray>()
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = FixedWifiStore(bytes::containsKey, bytes::get, { target, value ->
            if (value == null) bytes.remove(target) else bytes[target] = value
        }, { key })
    }
    private fun record(password: String, target: String = "AA:BB:CC:DD:EE:FF") =
        VerifiedWifi(target, WifiConfiguration("q4", password, 149, 2), 10L)

    @Test fun rememberedCredentialsNeverChangeWithoutManualReset() {
        val f = Fixture(); val original = record("original-pass")
        assertTrue(f.store.learn(original))
        val ciphertext = f.bytes.values.single().copyOf()
        repeat(100) { assertFalse(f.store.learn(record("different-pass"))) }
        assertEquals(original, f.store.load(original.target))
        assertArrayEquals(ciphertext, f.bytes.values.single())
        f.store.clear(original.target)
        assertTrue(f.store.learn(record("different-pass")))
        assertEquals("different-pass", f.store.load(original.target)!!.wifi.passphrase)
    }
    @Test fun unreadableMemoryCanBeRelearnedAfterSuccessfulConnection() {
        val f = Fixture(); val candidate = record("fresh-password")
        f.bytes[candidate.target] = byteArrayOf(1, 2, 3)
        assertTrue(f.store.hasRecord(candidate.target))
        assertEquals(FixedWifiStore.State(true, null), f.store.readState(candidate.target))
        assertNull(f.store.load(candidate.target))
        assertTrue(f.store.learn(candidate))
        assertEquals(candidate, f.store.load(candidate.target))
    }
    @Test fun concurrentSuccessCallbacksFixExactlyOneRecord() {
        val f = Fixture(); val ready = CountDownLatch(2); val go = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        val jobs = listOf("password-one", "password-two").map { password -> Thread {
            ready.countDown(); go.await()
            results.add(f.store.learn(record(password)))
        }.apply { isDaemon = true; start() } }
        assertTrue(ready.await(2, TimeUnit.SECONDS)); go.countDown()
        jobs.forEach { it.join(3000); assertFalse(it.isAlive) }
        assertEquals(1, results.count { it })
        val snapshot = f.store.readState(record("").target)
        assertTrue(snapshot.occupied)
        assertNotNull(snapshot.value)
    }
    @Test fun clearingOneTargetKeepsAnotherTargetsMemory() {
        val f = Fixture(); val one = record("first-pass"); val two = record("second-pass", "11:22:33:44:55:66")
        assertTrue(f.store.learn(one)); assertTrue(f.store.learn(two))
        f.store.clear(one.target)
        assertNull(f.store.load(one.target))
        assertEquals(two, f.store.load(two.target))
    }
}
