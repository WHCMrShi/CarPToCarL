package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class VideoFrameWorkspaceTest {
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }

    /** 独立保留旧拆分逻辑作为线格式回归基准。 */
    private fun legacySplit(bytes: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) return if (bytes.isEmpty()) emptyList() else listOf(bytes)
        return starts.mapIndexedNotNull { i, start ->
            val begin = start + if (bytes[start + 2] == 1.toByte()) 3 else 4
            val end = if (i + 1 < starts.size) starts[i + 1] else bytes.size
            if (end > begin) bytes.copyOfRange(begin, end) else null
        }
    }

    private fun packed(units: List<ByteArray>): ByteArray =
        ByteArrayOutputStream()
            .apply {
                for (unit in units) {
                    for (i in 0..3) write((unit.size ushr (24 - i * 8)) and 255)
                    write(unit)
                }
            }.toByteArray()

    private fun annex(size: Int): ByteArray = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(size) { ((it % 200) + 20).toByte() }

    private fun header(body: Int): ByteArray =
        ByteArray(128) { (it * 13).toByte() }.also {
            for (i in 0..3) it[i] = (body ushr (i * 8)).toByte()
        }

    @Test
    fun slicesAndAvccMatchOldParserForPrefixesEmptyNalsAndRandomInputs() {
        val random = Random(71)
        val edgeCases =
            listOf(
                byteArrayOf(),
                byteArrayOf(0, 0, 1),
                byteArrayOf(0, 0, 0, 1),
                byteArrayOf(0, 0, 0, 1, 0, 0, 1, 0x65, 0, 0, 1),
                byteArrayOf(9, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68, 0, 0),
            )
        val samples =
            edgeCases +
                List(200) {
                    ByteArrayOutputStream()
                        .apply {
                            repeat(1 + random.nextInt(8)) {
                                write(if (random.nextBoolean()) byteArrayOf(0, 0, 1) else byteArrayOf(0, 0, 0, 1))
                                write(ByteArray(random.nextInt(1000)).also { random.nextBytes(it) })
                            }
                        }.toByteArray()
                }
        for (source in samples) {
            val expected = legacySplit(source)
            val slices = AnnexBNals.split(source)
            assertEquals(expected.size, slices.size)
            for (i in slices.indices) {
                assertSame(source, slices[i].data)
                assertArrayEquals(expected[i], slices[i].copyBytes())
            }
            val output = ByteArray(AnnexBNals.avccSize(slices))
            AnnexBNals.pack(slices, output, 0)
            assertArrayEquals(packed(expected), output)
        }
    }

    @Test
    fun chaChaMatchesOldWireAcrossGrowthShrinkSourceRestartAndChannelReset() {
        val workspace = VideoFrameWorkspace(StreamFrameCipher.ChaCha(key), null)
        var previous: ByteArray? = null
        for ((counter, size) in listOf(0L to 20, 1L to 70_000, 2L to 2, 3L to 20, 0L to 20)) {
            val input = annex(size)
            val unchanged = input.copyOf()
            val plain = packed(legacySplit(input))
            val head = header(plain.size + 16)
            val wire = workspace.seal(AnnexBNals.split(input), head, counter)
            assertEquals(head.size + plain.size + 16, workspace.size)
            assertArrayEquals(
                head + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, head),
                wire.copyOf(workspace.size),
            )
            assertArrayEquals(
                plain,
                AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), wire.copyOfRange(128, workspace.size), head),
            )
            assertArrayEquals(unchanged, input)
            if (counter >= 2 || (counter == 0L && previous != null)) assertSame(previous, wire)
            previous = wire
        }
    }

    @Test
    fun aesRetainsContinuousKeystreamAcrossVariableLengthFrames() {
        val aesKey = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it * 3).toByte() }
        val workspace = VideoFrameWorkspace(StreamFrameCipher.AesCtr(aesKey, iv), AirPlayCrypto.Aes128CtrStream(aesKey, iv))
        val reference = AirPlayCrypto.Aes128CtrStream(aesKey, iv)
        for ((counter, size) in listOf(0L to 1, 1L to 4096, 2L to 3, 0L to 51)) {
            val source = annex(size)
            val plain = packed(legacySplit(source))
            val expected = plain.copyOf().also { reference.applyKeystream(it) }
            val head = header(plain.size)
            val wire = workspace.seal(AnnexBNals.split(source), head, counter)
            assertArrayEquals(head + expected, wire.copyOf(workspace.size))
        }
    }

    @Test
    fun ownedCachedIdrAndSeededCsdSurviveInputMutationAndWorkspaceReuse() {
        val input = annex(100)
        val slices = AnnexBNals.split(input).map { it.ownedCopy() }
        val expected = slices.map { it.copyBytes() }
        input.fill(0)
        val workspace = VideoFrameWorkspace(StreamFrameCipher.ChaCha(key), null)
        repeat(3) { i ->
            val head = header(AnnexBNals.avccSize(slices) + 16)
            workspace.seal(slices, head, i.toLong())
            assertArrayEquals(expected.single(), slices.single().copyBytes())
        }
        val sender = VideoSender("127.0.0.1", 5000, StreamFrameCipher.ChaCha(key), 1412, 654, log = {})
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 30, 0, 0, 0, 1, 0x68, 1)
        sender.seedCsd(csd)
        csd.fill(0)
        @Suppress("UNCHECKED_CAST")
        val cached =
            VideoSender::class.java
                .getDeclaredField("cachedCsd")
                .apply { isAccessible = true }
                .get(sender) as List<NalSlice>
        assertArrayEquals(byteArrayOf(0x67, 66, 0, 30), cached[0].copyBytes())
        assertArrayEquals(byteArrayOf(0x68, 1), cached[1].copyBytes())
    }

    @Test
    fun largeFrameRemainsSupportedWithoutPinningOversizedWorkspace() {
        val workspace = VideoFrameWorkspace(StreamFrameCipher.ChaCha(key), null)
        val small = AnnexBNals.split(annex(16))
        val retained = workspace.seal(small, header(AnnexBNals.avccSize(small) + 16), 0)
        val large = AnnexBNals.split(annex(8 * 1024 * 1024 + 1))
        val encrypted = workspace.seal(large, header(AnnexBNals.avccSize(large) + 16), 1)
        assertEquals(AnnexBNals.avccSize(large) + 144, workspace.size)
        assertEquals(workspace.size, encrypted.size)
        assertSame(retained, workspace.seal(small, header(AnnexBNals.avccSize(small) + 16), 2))
    }

    @Test fun productionWriterRejectsCapturedOutputFromRetiredSocket() {
        val sender = VideoSender("127.0.0.1", 5000, StreamFrameCipher.ChaCha(key), 1412, 654, log = {})
        val ownerField = VideoSender::class.java.getDeclaredField("socket").apply { isAccessible = true }
        val config = VideoSender::class.java.getDeclaredField("configSent").apply { isAccessible = true }
        val counter = VideoSender::class.java.getDeclaredField("frameCounter").apply { isAccessible = true }
        val capture = VideoSender::class.java.getDeclaredMethod("captureOutput").apply { isAccessible = true }
        val send = VideoSender::class.java.getDeclaredMethod("sendFrameFromNals", List::class.java,
            Boolean::class.javaPrimitiveType, java.io.OutputStream::class.java).apply { isAccessible = true }
        val bytes = java.io.ByteArrayOutputStream()
        val original = object : java.net.Socket() {
            override fun getOutputStream(): java.io.OutputStream = bytes
        }
        ownerField.set(sender, original)
        val captured = capture.invoke(sender)
        java.net.Socket().use { replacement ->
            ownerField.set(sender, replacement)
            config.setBoolean(sender, true)
            try {
                send.invoke(sender, AnnexBNals.split(annex(20)), true, captured)
                fail("retired output must be rejected before consuming a nonce")
            } catch (error: java.lang.reflect.InvocationTargetException) {
                assertTrue(error.cause is java.io.IOException)
            }
            assertEquals(0, bytes.size())
            assertEquals(0L, counter.getLong(sender))
        }
    }

    @Test
    fun productionWriterSendsOneWholeFrameAndControlFramesDoNotConsumeNonce() {
        val sender = VideoSender("127.0.0.1", 5000, StreamFrameCipher.ChaCha(key), 1412, 654, log = {})
        VideoSender::class.java
            .getDeclaredField("configSent")
            .apply { isAccessible = true }
            .setBoolean(sender, true)
        val send =
            VideoSender::class.java
                .getDeclaredMethod(
                    "sendFrameFromNals",
                    List::class.java,
                    Boolean::class.javaPrimitiveType,
                    java.io.OutputStream::class.java,
                ).apply { isAccessible = true }
        val control =
            VideoSender::class.java
                .getDeclaredMethod(
                    "writeMessage",
                    Int::class.javaPrimitiveType,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                    java.io.OutputStream::class.java,
                ).apply { isAccessible = true }
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            java.net.Socket(server.inetAddress, server.localPort).use { client ->
                server.accept().use { receiver ->
                    receiver.soTimeout = 2_000
                    val input = java.io.DataInputStream(receiver.getInputStream())
                    var writes = 0
                    val raw = client.getOutputStream()
                    val output =
                        object : java.io.OutputStream() {
                            override fun write(value: Int) {
                                writes++
                                raw.write(value)
                            }

                            override fun write(
                                bytes: ByteArray,
                                offset: Int,
                                length: Int,
                            ) {
                                writes++
                                raw.write(bytes, offset, length)
                            }
                        }
                    val ownedSocket = object : java.net.Socket() {
                        // A socket API need not return the same stream object on every call.
                        override fun getOutputStream(): java.io.OutputStream = object : java.io.OutputStream() {
                            override fun write(value: Int) = output.write(value)
                            override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
                        }
                    }
                    VideoSender::class.java.getDeclaredField("socket").apply { isAccessible = true }
                        .set(sender, ownedSocket)
                    val capture = VideoSender::class.java.getDeclaredMethod("captureOutput").apply { isAccessible = true }
                    val captured = capture.invoke(sender) as java.io.OutputStream
                    assertNotSame(ownedSocket.getOutputStream(), ownedSocket.getOutputStream())
                    for ((counter, size) in listOf(0L to 200, 1L to 6000, 2L to 2)) {
                        val source = annex(size)
                        val before = writes
                        send.invoke(sender, AnnexBNals.split(source), counter == 0L, captured)
                        assertEquals(before + 1, writes)
                        val head = ByteArray(128).also { input.readFully(it) }
                        val length =
                            java.nio.ByteBuffer
                                .wrap(head)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .int
                        val body = ByteArray(length).also { input.readFully(it) }
                        assertArrayEquals(
                            AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), packed(legacySplit(source)), head),
                            body,
                        )
                        control.invoke(sender, 2, ByteArray(0), 0, captured)
                        val keepAlive = ByteArray(128).also { input.readFully(it) }
                        assertEquals(2, keepAlive[4].toInt())
                    }
                }
            }
        }
    }
}
