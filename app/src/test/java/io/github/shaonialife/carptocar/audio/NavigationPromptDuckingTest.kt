package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class NavigationPromptDuckingTest {
    @Test
    fun continuousSpeechSendsOneDuckAndRestoresOnClose() {
        val commands = LinkedBlockingQueue<Boolean>()
        NavigationPromptDucking(250) { commands.add(it); true }.use { ducking ->
            ducking.begin()
            assertEquals(true, commands.poll(2, TimeUnit.SECONDS))
            ducking.end(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
            ducking.begin()
            assertNull(commands.poll())
        }
        assertEquals(false, commands.poll(2, TimeUnit.SECONDS))
        assertNull(commands.poll())
    }

    @Test
    fun missingAcknowledgementStillGetsOneRestore() {
        val commands = LinkedBlockingQueue<Boolean>()
        NavigationPromptDucking(250) { commands.add(it); if (it) null else true }.use { ducking ->
            ducking.begin()
            assertEquals(true, commands.poll(2, TimeUnit.SECONDS))
            ducking.end(null)
            assertEquals(false, commands.poll(2, TimeUnit.SECONDS))
        }
        assertNull(commands.poll())
    }
}
