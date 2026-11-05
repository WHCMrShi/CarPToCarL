package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** 单视频流独占，由 VideoSender.writeLock 串行保护；输出只在本次同步 write 内有效。 */
internal class VideoFrameWorkspace(
    private val mode: StreamFrameCipher,
    private val aes: AirPlayCrypto.Aes128CtrStream?,
) {
    private var plain = ByteArray(0)
    private var wire = ByteArray(0)
    private val key = (mode as? StreamFrameCipher.ChaCha)?.let { KeyParameter(it.key) }
    private val nonce = ByteArray(12)
    var size: Int = 0
        private set

    private fun capacity(required: Int): Int {
        var capacity = 1024
        while (capacity < required) capacity *= 2
        return capacity
    }

    fun seal(units: List<NalSlice>, header: ByteArray, counter: Long): ByteArray {
        require(header.size == 128)
        val bodySize = AnnexBNals.avccSize(units)
        val tag = if (mode is StreamFrameCipher.ChaCha) 16 else 0
        size = 128 + bodySize + tag
        // 仅限制常驻工作区，不拒绝更大的合法输入；超大帧使用一次性数组。
        val retainedLimit = 8 * 1024 * 1024
        val target = if (size > retainedLimit) ByteArray(size) else {
            if (wire.size < size) wire = ByteArray(capacity(size))
            wire
        }
        header.copyInto(target)
        when (mode) {
            is StreamFrameCipher.ChaCha -> {
                val input = if (bodySize > retainedLimit) ByteArray(bodySize) else {
                    if (plain.size < bodySize) plain = ByteArray(capacity(bodySize))
                    plain
                }
                AnnexBNals.pack(units, input, 0)
                for (i in 0..7) nonce[4 + i] = (counter ushr (8 * i)).toByte()
                // 保留原来的逐帧 cipher 初始化；通道重连计数归零无需增加隐藏状态。
                val cipher = ChaCha20Poly1305()
                cipher.init(true, AEADParameters(requireNotNull(key), 128, nonce))
                cipher.processAADBytes(header, 0, header.size)
                val count = cipher.processBytes(input, 0, bodySize, target, 128)
                cipher.doFinal(target, 128 + count)
            }
            is StreamFrameCipher.AesCtr -> {
                AnnexBNals.pack(units, target, 128)
                requireNotNull(aes).applyKeystream(target, 128, bodySize)
            }
        }
        return target
    }
}
