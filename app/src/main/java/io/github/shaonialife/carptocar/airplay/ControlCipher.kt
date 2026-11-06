package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.util.StreamByteQueue

/**
 * 控制通道加密帧（与参考接收端 ControlCipher 逐字节兼容）：
 * 每帧 = [2 字节小端密文长度(作 AAD)][密文][16 字节 tag]，
 * nonce = 4 字节零 + 8 字节小端计数器，收发方向各自的密钥与计数器独立递增。
 *
 * 单帧明文上限取 **1422**（1440 MTU - 2 字节 AAD - 16 字节 tag）。对照 catplay
 * `AirPlayCipherCodec`：它按「以太网 1500 - IPv6 头 40 - TCP 头 20 = 1440」分片，
 * 且**解码侧遇到 len > 1422 直接判协议违规**。真 iPhone 的老版本用 1024 字节分片，
 * 两者都在接收端接受范围内；超过 1440 的帧则可能被严格车机拒收（旧实现用 0x4000，
 * 一旦某请求超过 MTU 就会被拆成非法帧——2026-10-19 按 catplay 对齐）。
 * 读方向保持宽松（车机自己发的分片长度由其决定，不应因超出我们的 MTU 而丢帧）。
 */
class ControlCipher(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
) {
    class Decrypted(
        val data: ByteArray,
        val rest: ByteArray,
    )

    private var readCounter = 0L
    private var writeCounter = 0L

    private val readAad = ByteArray(HEADER_SIZE)

    private fun openFrame(
        source: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        source.copyInto(readAad, 0, offset, offset + HEADER_SIZE)
        val plain =
            AirPlayCrypto.chachaOpenRange(
                readKey,
                AirPlayCrypto.nonce64(readCounter),
                source,
                offset + HEADER_SIZE,
                length + TAG_SIZE,
                readAad,
            )
        readCounter++ // 认证成功才推进 nonce，失败的明文绝不交给解析器。
        return plain
    }

    /** 兼容原先由调用方保存 rest 的 API；结果独立拥有数组。 */
    fun decrypt(buffer: ByteArray): Decrypted {
        val frames = ArrayList<ByteArray>()
        var offset = 0
        var total = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val plain = openFrame(buffer, offset, length)
            frames.add(plain)
            total += plain.size
            offset = frameEnd
        }
        val joined = ByteArray(total)
        var target = 0
        for (frame in frames) {
            frame.copyInto(joined, target)
            target += frame.size
        }
        return Decrypted(joined, buffer.copyOfRange(offset, buffer.size))
    }

    /** 每条连接仅一个读消费者；残片由 reader 持有，不再每次拼接整个 pending。 */
    internal fun newReader(): Reader = Reader()

    internal inner class Reader {
        private val pending = StreamByteQueue()

        fun offer(
            bytes: ByteArray,
            offset: Int,
            size: Int,
            onPlain: (ByteArray) -> Unit,
        ) {
            pending.append(bytes, offset, size)
            while (pending.size >= HEADER_SIZE) {
                val length = readU16Le(pending.storage, pending.start)
                val frameSize = HEADER_SIZE + length + TAG_SIZE
                if (pending.size < frameSize) break
                val plain = openFrame(pending.storage, pending.start, length)
                pending.discard(frameSize)
                onPlain(plain)
            }
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val frames = maxOf(1L, (plaintext.size.toLong() + MAX_PAYLOAD - 1) / MAX_PAYLOAD)
        val outputSize = plaintext.size.toLong() + frames * (HEADER_SIZE + TAG_SIZE)
        require(outputSize <= Int.MAX_VALUE)
        val output = ByteArray(outputSize.toInt())
        val aad = ByteArray(HEADER_SIZE)
        var offset = 0
        var target = 0
        do {
            val size = minOf(MAX_PAYLOAD, plaintext.size - offset)
            aad[0] = size.toByte()
            aad[1] = (size ushr 8).toByte()
            val sealed =
                AirPlayCrypto.chachaSealRange(
                    writeKey,
                    AirPlayCrypto.nonce64(writeCounter),
                    plaintext,
                    offset,
                    size,
                    aad,
                )
            aad.copyInto(output, target)
            sealed.copyInto(output, target + HEADER_SIZE)
            writeCounter++
            target += HEADER_SIZE + sealed.size
            offset += size
        } while (offset < plaintext.size)
        return output
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16

        /** 1440 MTU - 2B AAD - 16B tag（catplay AIRPLAY_CIPHER_MTU_MAX 同口径）。 */
        const val MAX_PAYLOAD = 1422
    }
}
