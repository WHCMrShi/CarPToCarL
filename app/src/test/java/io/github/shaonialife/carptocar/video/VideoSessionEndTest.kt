package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import org.junit.Assert.*
import org.junit.Test

class VideoSessionEndTest {
    @Test fun endCallbackCarriesItsOwnerAndCannotBeDeliveredTwice() {
        val ended = mutableListOf<VideoSender>()
        val sender = VideoSender("127.0.0.1", 5000, StreamFrameCipher.ChaCha(ByteArray(32)),
            1412, 654, log = {}, onEnded = { ended += it })
        val end = VideoSender::class.java.getDeclaredMethod("endOnce").apply { isAccessible = true }
        end.invoke(sender)
        end.invoke(sender)
        assertEquals(1, ended.size)
        assertSame(sender, ended.single())
    }

    @Test fun oldOwnerCompletionCannotBeMistakenForNewOwner() {
        val ended = mutableListOf<VideoSender>()
        fun sender() = VideoSender("127.0.0.1", 5000, StreamFrameCipher.ChaCha(ByteArray(32)),
            1412, 654, log = {}, onEnded = { ended += it })
        val old = sender()
        val current = sender()
        val end = VideoSender::class.java.getDeclaredMethod("endOnce").apply { isAccessible = true }
        end.invoke(old)
        assertNotSame(current, ended.single())
        end.invoke(current)
        assertSame(current, ended.last())
    }
}
