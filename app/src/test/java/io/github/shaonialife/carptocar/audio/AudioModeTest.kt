package io.github.shaonialife.carptocar.audio

import org.junit.Assert.*
import org.junit.Test

class AudioModeTest {
    private val pcm = AudioProfile("media", 1L shl 15, 48000, 250)
    private val aac = AudioProfile("media", 1L shl 23, 48000, 250, 102, AudioCodec.AAC_LC)
    @Test fun defaultsAndUserChoiceOverrideOppositeWorkingProfile() {
        assertEquals(AudioMode.QUALITY, AudioMode.decode(null))
        assertEquals(AudioMode.QUALITY, AudioMode.decode("unknown"))
        assertEquals(AudioMode.STABILITY, AudioMode.decode("STABILITY"))
        assertEquals(pcm, AudioMode.QUALITY.order(listOf(aac, pcm)).first())
        assertEquals(aac, AudioMode.STABILITY.order(listOf(pcm, aac)).first())
    }
    @Test fun absentFormatNeverCreatesUndeclaredProfilesOrChangesCallInput() {
        val input = aac.copy(isInput = true)
        val other = aac.copy(sampleRate = 44100)
        assertEquals(emptyList<AudioProfile>(), AudioMode.STABILITY.order(listOf(pcm, other, input)))
        assertEquals(emptyList<AudioProfile>(), AudioMode.STABILITY.order(emptyList()))
        assertEquals(listOf(aac), AudioMode.STABILITY.order(listOf(pcm, aac, aac)))
    }
}
