package io.github.shaonialife.carptocar.audio

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** 每条音频流独占；由 AudioSender 发送锁保护。返回值借用工作区，必须在下次调用前发送。 */
internal class PcmPacketSealer(key: ByteArray, private val streamType: Long) {
    private val keyParameter = KeyParameter(key)
    private val cipher = ChaCha20Poly1305()
    private val nonce = ByteArray(12)
    private val swapped = ByteArray(AudioSender.PACKET_BYTES)
    private val wire = ByteArray(12 + AudioSender.PACKET_BYTES + 16 + 8)
    private var lastNonce = -1L

    fun seal(source: ByteArray, offset: Int, sequence: Int, timestamp: Long, counter: Long, marker: Boolean): ByteArray {
        require(offset >= 0 && offset <= source.size - swapped.size)
        require(counter >= 0 && counter > lastNonce) { "PCM nonce must increase within a stream" }
        // 计数即使在加密失败时也不回退，禁止在同一实例上复用 nonce。
        lastNonce = counter
        wire[0] = 0x80.toByte()
        wire[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7f)).toByte()
        wire[2] = (sequence ushr 8).toByte()
        wire[3] = sequence.toByte()
        for (i in 0..3) wire[4 + i] = (timestamp ushr (24 - i * 8)).toByte()
        // SSRC=0；nonce 前四字节始终为 0。
        for (i in 0..7) nonce[4 + i] = (counter ushr (i * 8)).toByte()
        var i = 0
        while (i < swapped.size) {
            swapped[i] = source[offset + i + 1]
            swapped[i + 1] = source[offset + i]
            i += 2
        }
        cipher.init(true, AEADParameters(keyParameter, 128, nonce))
        cipher.processAADBytes(wire, 4, 8)
        val count = cipher.processBytes(swapped, 0, swapped.size, wire, 12)
        cipher.doFinal(wire, 12 + count)
        nonce.copyInto(wire, wire.size - 8, 4, 12)
        return wire
    }
}
