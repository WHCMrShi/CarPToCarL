package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException

class VideoConfigStateTest {
    private val sps = byteArrayOf(0,0,0,1,0x67,66,0,30,1)
    private val pps = byteArrayOf(0,0,0,1,0x68,1)
    private val idr = byteArrayOf(0,0,0,1,0x65,1)
    private class Output : ByteArrayOutputStream() {
        var configs = 0
        var fail = false
        var onFlush: (() -> Unit)? = null
        override fun write(bytes: ByteArray, off: Int, len: Int) {
            if (len >= 128 && bytes[off + 4] == 1.toByte()) {
                if (fail) throw IOException("config write")
                configs++
            }
            super.write(bytes, off, len)
        }
        override fun flush() { onFlush?.invoke() }
    }
    private fun sender(out: Output): VideoSender {
        val s = VideoSender("127.0.0.1", 5000, StreamFrameCipher.ChaCha(ByteArray(32)),1412,654,log={},uptimeMillis={1_000L})
        val socket = object : java.net.Socket() { override fun getOutputStream() = out }
        VideoSender::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(s,socket)
        assertTrue(s.startPassthrough())
        return s
    }
    private fun field(s: VideoSender, name: String): Any? =
        VideoSender::class.java.getDeclaredField(name).apply { isAccessible = true }.get(s)
    private fun config(s: VideoSender, bytes: ByteArray, out: Output) {
        VideoSender::class.java.getDeclaredMethod("sendConfigFromNals", List::class.java, java.io.OutputStream::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(s,AnnexBNals.split(bytes),out,"test",true)
    }
    @Test fun repeatedIdenticalParameterSetsOnlyWriteOneConfig() {
        val out = Output(); val s = sender(out)
        try {
            repeat(10) { assertTrue(s.submitSourceH264(sps+pps+idr,true)) }
            assertEquals(1,out.configs)
            assertNotNull(field(s,"cachedCsd"))
        } finally { s.stop() }
    }
    @Test fun changedSpsOrPpsWritesNewConfiguration() {
        val out = Output(); val s = sender(out)
        try {
            assertTrue(s.submitSourceH264(sps+pps+idr,true))
            val newPps = pps.copyOf().also { it[it.lastIndex]=2 }
            assertTrue(s.submitSourceH264(sps+newPps+idr,true))
            val newSps = sps.copyOf().also { it[it.lastIndex]=2 }
            assertTrue(s.submitSourceH264(newSps+newPps+idr,true))
            assertEquals(3,out.configs)
        } finally { s.stop() }
    }
    @Test fun configurationFailureDoesNotCommitFingerprintOrCache() {
        val out = Output(); val s = sender(out)
        try {
            out.fail = true
            try { config(s,sps+pps,out); fail("write should fail") }
            catch (error: InvocationTargetException) { assertTrue(error.cause is IOException) }
            assertNull(field(s,"sentSps")); assertNull(field(s,"sentPps"))
            assertNull(field(s,"cachedCsd")); assertEquals(false,field(s,"configSent"))
            out.fail=false
            assertTrue(s.submitSourceH264(sps+pps+idr,true))
            assertEquals(1,out.configs)
        } finally { s.stop() }
    }
    @Test fun newConfigInvalidatesOldIdleIdrAndOwnsInputBytes() {
        val out = Output(); val s = sender(out)
        try {
            val data=sps+pps+idr
            assertTrue(s.submitSourceH264(data,true))
            assertNotNull(field(s,"lastIdrNals"))
            data.fill(0)
            assertTrue(s.submitSourceH264(sps+pps+idr,true))
            assertEquals(1,out.configs)
            config(s,sps+pps,out)
            assertNull(field(s,"lastIdrNals"))
        } finally { s.stop() }
    }
    @Test fun sourceRetirementDuringFlushCannotPublishOldConfigState() {
        val out = Output(); val s = sender(out)
        try {
            out.onFlush = { out.onFlush=null; s.onSourceRestart("during-config") }
            config(s,sps+pps,out)
            assertNull(field(s,"sentSps")); assertNull(field(s,"cachedCsd"))
            assertEquals(false,field(s,"configSent"))
            assertTrue(s.submitSourceH264(sps+pps+idr,true))
            assertEquals(2,out.configs)
        } finally { s.stop() }
    }
    @Test fun sourceRestartStillResendsConfigForIdenticalParameters() {
        val out = Output(); val s = sender(out)
        try {
            assertTrue(s.submitSourceH264(sps+pps+idr,true))
            s.onSourceRestart("test")
            assertTrue(s.submitSourceH264(sps+pps+idr,true))
            assertEquals(2,out.configs)
        } finally { s.stop() }
    }
}
