package io.github.shaonialife.carptocar.iap2

/** 按实际协商版本编码；v1 只有文件大小，v2 才带文件类型。 */
internal object Iap2FileTransferFormat {
    fun setupSize(version: Int): Int? = when (version) { 1 -> 10; 2 -> 12; else -> null }

    fun setup(id: Int, size: Int, version: Int): ByteArray {
        require(id in 1..255 && size >= 0)
        return ByteArray(requireNotNull(setupSize(version))).also {
            it[0] = id.toByte(); it[1] = 4
            repeat(8) { index -> it[2 + index] = (size.toLong() ushr (56 - index * 8)).toByte() }
            if (version == 2) it[11] = 2 // Now Playing artwork
        }
    }
}
