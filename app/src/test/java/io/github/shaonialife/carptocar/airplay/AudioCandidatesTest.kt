package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.audio.AudioCodec
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioCandidatesTest {
    private fun client(formats: List<Map<String, Any>>): AirPlayClient {
        val client = AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "test",
                passphrase = "",
                channel = 0,
                securityType = 2,
                airPlayPort = 7000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("127.0.0.1"),
            ),
            log = {},
        )
        AirPlayClient::class.java.getDeclaredField("negotiatedInfo").apply {
            isAccessible = true
            set(client, mapOf("audioFormats" to formats))
        }
        return client
    }

    private fun format(type: Long, audioType: String, bits: Long): Map<String, Any> =
        mapOf("type" to type, "audioType" to audioType, "audioOutputFormats" to bits)

    @Test
    fun nativeMediaPcm48PrecedesAacAndPcm44WhenBothAreDeclared() {
        val candidates = client(
            listOf(
                format(102, "media", 1L shl 22),
                format(100, "media", (1L shl 15) or (1L shl 11)),
            ),
        ).audioCandidates()
        assertEquals(AudioCodec.PCM, candidates.first().codec)
        assertEquals(48_000, candidates.first().sampleRate)
        assertEquals(100L, candidates.first().streamType)
        assertEquals(listOf(48_000, 44_100, 44_100), candidates.map { it.sampleRate })
        assertEquals(AudioCodec.AAC_LC, candidates.last().codec)
    }

    @Test
    fun aacOnlyReceiverDoesNotAcquireUndeclaredPcmCandidate() {
        val candidates = client(listOf(format(102, "media", 1L shl 22))).audioCandidates()
        assertEquals(1, candidates.size)
        assertEquals(AudioCodec.AAC_LC, candidates.single().codec)
    }

    @Test
    fun manualCompatibilityModeKeepsCompatibilityPcmFirstAndSkipsAac() {
        val candidates = client(
            listOf(
                format(100, "media", 1L shl 15),
                format(100, "compatibility", 1L shl 15),
                format(102, "media", 1L shl 22),
            ),
        ).audioCandidates(preferCompat = true)
        assertEquals("compatibility", candidates.first().audioType)
        assertFalse(candidates.any { it.codec == AudioCodec.AAC_LC })
    }
}
