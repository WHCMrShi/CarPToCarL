package io.github.shaonialife.carptocar.media

import java.lang.ref.WeakReference

/** 仅封面工作线程使用；相同位图及 generation 不再压缩，输入字节不变时复用转换结果。 */
internal class ArtworkEncodingCache {
    private var bitmap = WeakReference<Any>(null)
    private var generation = 0
    private var bitmapBytes: ByteArray? = null
    private var inputBytes: ByteArray? = null
    private var convertedBytes: ByteArray? = null

    fun bitmap(key: Any, version: Int, sameContent: (Any, Int) -> Boolean = { _, _ -> false }, encode: () -> ByteArray?): ByteArray? {
        val previous = bitmap.get()
        if (bitmapBytes != null && previous != null &&
            ((previous === key && generation == version) || (previous !== key && sameContent(previous, generation)))) {
            bitmap = WeakReference(key); generation = version
            return bitmapBytes
        }
        val result = encode()
        bitmap = WeakReference(key); generation = version; bitmapBytes = result
        return result
    }

    fun bytes(input: ByteArray, convert: () -> ByteArray?): ByteArray? {
        if (inputBytes?.contentEquals(input) == true && convertedBytes != null) return convertedBytes
        val result = convert()
        inputBytes = if (result != null) input else null
        convertedBytes = result
        return result
    }
}
