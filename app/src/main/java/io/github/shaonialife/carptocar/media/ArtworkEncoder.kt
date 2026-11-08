package io.github.shaonialife.carptocar.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection

/** 仪表封面只在工作线程缩略编码；同时限制尺寸和体积，避免大 PNG 长期占用控制通道。 */
internal object ArtworkEncoder {
    fun load(metadata: MediaMetadata?, resolver: ContentResolver, control: ArtworkReadControl,
        cache: ArtworkEncodingCache, log: (String) -> Unit = {}): ByteArray? {
        control.check()
        if (metadata == null) { log("ARTWORK: source=none metadata-missing"); return null }
        val direct = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (direct != null) {
            if (direct.isRecycled) { log("ARTWORK: source=bitmap rejected=recycled"); return null }
            log("ARTWORK: source=bitmap width=${direct.width} height=${direct.height}")
            return cache.bitmap(direct, direct.generationId, sameContent = { previous, generation ->
                val old = previous as? Bitmap
                old != null && !old.isRecycled && old.generationId == generation && direct.sameAs(old)
            }) { control.check(); encode(direct) }
        }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: run { log("ARTWORK: source=none no-bitmap-or-uri"); return null }
        log("ARTWORK: source=uri scheme=${Uri.parse(uri).scheme} (URI value omitted)")
        val connection = if (uri.startsWith("https://") || uri.startsWith("http://"))
            (java.net.URL(uri).openConnection() as HttpURLConnection).apply { connectTimeout = 3000; readTimeout = 3000 }
            else null
        val bytes = if (connection != null) {
            control.onCancel { connection.disconnect() }
            try {
                control.check()
                connection.inputStream.use { input ->
                    control.onCancel { connection.disconnect(); input.close() }
                    control.read(input)
                }
            } finally { connection.disconnect() }
        } else {
            val signal = android.os.CancellationSignal()
            control.onCancel { signal.cancel() }
            try {
                control.check()
                resolver.openAssetFileDescriptor(Uri.parse(uri), "r", signal)?.use { asset ->
                    asset.createInputStream().use { input ->
                        control.onCancel { signal.cancel(); input.close() }
                        control.read(input)
                    }
                }
            } finally { signal.cancel() }
        }
        control.check()
        return bytes?.let { cache.bytes(it) { loadBytes(it) } }
    }

    fun loadBytes(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > EncodedArtwork.MAX_BYTES || Thread.currentThread().isInterrupted) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val native = bounds.outMimeType == "image/jpeg" || bounds.outMimeType == "image/png"
        if (native && maxOf(bounds.outWidth, bounds.outHeight) <= MAX_SIDE && bytes.size <= MAX_TRANSFER_BYTES) return bytes
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try { encode(decoded) } finally { decoded.recycle() }
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        if (bitmap.isRecycled || Thread.currentThread().isInterrupted) return null
        var targetSide = minOf(maxOf(bitmap.width, bitmap.height), MAX_SIDE)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (size() + length > EncodedArtwork.MAX_BYTES) throw IOException("artwork exceeds 1 MiB")
                super.write(bytes, offset, length)
            }
            override fun write(value: Int) {
                if (size() >= EncodedArtwork.MAX_BYTES) throw IOException("artwork exceeds 1 MiB")
                super.write(value)
            }
        }
        while (targetSide > 0) {
            val side = maxOf(bitmap.width, bitmap.height)
            val scaled = if (side > targetSide) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width.toLong() * targetSide / side).toInt().coerceAtLeast(1),
                (bitmap.height.toLong() * targetSide / side).toInt().coerceAtLeast(1), true) else bitmap
            try {
                val transparent = scaled.hasAlpha() && IntArray(scaled.width * scaled.height).let { pixels ->
                    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                    pixels.any { (it ushr 24) != 255 }
                }
                val format = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                for (quality in if (transparent) intArrayOf(100) else intArrayOf(85, 70, 55)) {
                    output.reset()
                    if (!scaled.compress(format, quality, output)) return null
                    if (output.size() <= MAX_TRANSFER_BYTES) return output.toByteArray()
                }
            } finally { if (scaled !== bitmap) scaled.recycle() }
            targetSide /= 2
        }
        return null
    }
    private const val MAX_SIDE = 320
    private const val MAX_TRANSFER_BYTES = 64 * 1024
}
