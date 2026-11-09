package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import io.github.shaonialife.carptocar.carlife.CarCanvasMemory
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Q4NoticeEncodingTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val prefix = byteArrayOf(0,0,0,1)
    private val q4 = prefix + hex("6742801eda016414f9ea6a0a0c0a0da1426a") + prefix + hex("68ce3c80")
    private val hd = prefix + hex("6742801fda014016e9a8283028368509a8") + prefix + hex("68ce3c80")
    private val idr = prefix + hex("6501")
    private class Output : ByteArrayOutputStream() {
        val configs = mutableListOf<Pair<Int,Int>>()
        override fun write(bytes: ByteArray, off: Int, len: Int) {
            if (len >= 128 && bytes[off+4] == 1.toByte()) {
                val b=ByteBuffer.wrap(bytes,off+16,8).order(ByteOrder.LITTLE_ENDIAN)
                configs += b.float.toInt() to b.float.toInt()
                val view=ByteBuffer.wrap(bytes,off+32,16).order(ByteOrder.LITTLE_ENDIAN)
                repeat(4) { assertEquals(0f,view.float,0f) }
            }
            super.write(bytes,off,len)
        }
    }
    private fun sender(out: Output, size: Pair<Int,Int>? = CarCanvasMemory.DEFAULT): VideoSender {
        val s=VideoSender("127.0.0.1",5000,StreamFrameCipher.ChaCha(ByteArray(32)),1280,720,
            log={},noticeSize=size,uptimeMillis={1_000L})
        val socket=object: java.net.Socket() { override fun getOutputStream()=out }
        VideoSender::class.java.getDeclaredField("socket").apply { isAccessible=true }.set(s,socket)
        assertTrue(s.startPassthrough())
        return s
    }
    @Test fun q4NoticeUsesRealEncodingSizeAndSourceStillFollowsItsOwnSps() {
        val out=Output();val s=sender(out)
        var handovers=0;s.onSourceHandover={handovers++}
        try {
            assertTrue(s.pushNotice(listOf("waiting")) { w,h,_ ->
                assertEquals(CarCanvasMemory.DEFAULT,w to h)
                NoticeFrameEncoder.NoticeFrame(q4,idr)
            })
            assertEquals(listOf(1412 to 654),out.configs)
            assertEquals(0,handovers)
            assertTrue(s.submitSourceH264(hd+idr,true))
            assertEquals(listOf(1412 to 654,1280 to 720),out.configs)
            assertEquals(1,handovers)
        } finally { s.stop() }
    }
    @Test fun existingCallersWithoutNoticeOverrideKeepConstructorDimensions() {
        val out=Output();val s=sender(out,null)
        try {
            assertTrue(s.pushNotice(listOf("waiting")) { w,h,_ ->
                assertEquals(1280 to 720,w to h)
                NoticeFrameEncoder.NoticeFrame(hd,idr)
            })
            assertEquals(listOf(1280 to 720),out.configs)
        } finally { s.stop() }
    }
    @Test fun sourceThatArrivesDuringEncodingPreventsLateNoticeWrite() {
        val out=Output();val s=sender(out)
        try {
            assertFalse(s.pushNotice(listOf("waiting")) { _,_,_ ->
                assertTrue(s.submitSourceH264(hd+idr,true))
                NoticeFrameEncoder.NoticeFrame(q4,idr)
            })
            assertEquals(listOf(1280 to 720),out.configs)
        } finally { s.stop() }
    }
    @Test fun existingSourceDoesNotInvokeNoticeEncoder() {
        val out=Output();val s=sender(out)
        try {
            assertTrue(s.submitSourceH264(hd+idr,true))
            assertFalse(s.pushNotice(listOf("waiting")) { _,_,_ -> error("notice cannot preempt source") })
        } finally { s.stop() }
    }
    @Test fun failedNoticeEncodingLeavesRealSourceRecoveryAvailable() {
        val out=Output();val s=sender(out)
        try {
            assertFalse(s.pushNotice(listOf("waiting")) { _,_,_ -> null })
            assertTrue(out.configs.isEmpty())
            assertTrue(s.submitSourceH264(q4+idr,true))
            assertEquals(listOf(1412 to 654),out.configs)
        } finally { s.stop() }
    }
}
