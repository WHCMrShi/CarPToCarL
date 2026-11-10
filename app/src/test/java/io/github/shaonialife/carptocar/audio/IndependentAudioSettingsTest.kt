package io.github.shaonialife.carptocar.audio

import org.junit.Assert.*
import org.junit.Test

class IndependentAudioSettingsTest {
    @Test fun aacTuningAndRecoveryCannotChangeNativePcmSettings() {
        val aac = AacOutputSettings(44_100).copy(ingressNs = 900_000_000, idleFillMs = 10, bitRate = 128_000)
        assertEquals(352, PcmOutputSettings.protocol.spf)
        assertEquals(200_000_000L, PcmOutputSettings.INGRESS_NS)
        assertEquals(1024, aac.protocol.spf)
        assertNotSame(aac.protocol, PcmOutputSettings.protocol)
        val pcmSilence = SilenceFillPolicy()
        val aacSilence = SilenceFillPolicy(aac.idleFillMs, aac.stoppedFillMs)
        aacSilence.engineStopped()
        assertEquals(300L, pcmSilence.thresholdMs())
        assertEquals(50L, aacSilence.thresholdMs())
    }
    @Test fun perModeMemoryCannotOverwriteOrClearPcmEvidenceEvenForLongIdentities() {
        val receiver = "receiver".repeat(20)
        val pcm = AudioMode.QUALITY.memoryIdentity(receiver)
        val aac = AudioMode.STABILITY.memoryIdentity(receiver)
        val comparison = AudioMode.AAC_44K_TEST.memoryIdentity(receiver)
        assertNotEquals(AudioProfileMemory.receiverKey(pcm), AudioProfileMemory.receiverKey(aac))
        assertNotEquals(AudioProfileMemory.receiverKey(comparison), AudioProfileMemory.receiverKey(aac))
        try {
            AudioProfileMemory.recordWorking(null, pcm, "pcm-working")
            AudioProfileMemory.recordRejected(null, aac, "aac-rejected", setOf("aac-rejected"))
            assertTrue(AudioProfileMemory.load(null, aac).disabled)
            assertEquals("pcm-working", AudioProfileMemory.load(null, pcm).working)
            AudioProfileMemory.reset(null, aac)
            assertEquals("pcm-working", AudioProfileMemory.load(null, pcm).working)
        } finally { AudioProfileMemory.clearAll(null) }
    }
}
