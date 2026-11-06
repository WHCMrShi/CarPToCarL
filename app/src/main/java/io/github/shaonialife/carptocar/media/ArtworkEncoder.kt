package io.github.shaonialife.carptocar.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection

/** 只在封面工作线程调用。保持支持的原始 JPEG/PNG；否则最多缩到 1024 并一次无损 PNG 编码。 */
internal object ArtworkEncoder {
    fun load(metadata: MediaMetadata?, resolver: ContentResolver, control: ArtworkReadControl,
        cache: ArtworkEncodingCache): ByteArray? {
        control.check()
        if (metadata == null) return null
        val direct = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (direct != null) {
            if (direct.isRecycled) return null
            return cache.bitmap(direct, direct.generationId, sameContent = { previous, generation ->
                val old = previous as? Bitmap
                old != null && !old.isRecycled && old.generationId == generation && direct.sameAs(old)
            }) { control.check(); encode(direct) }
        }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: return null
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
        if (native && maxOf(bounds.outWidth, bounds.outHeight) <= MAX_SIDE) return bytes
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try { encode(decoded) } finally { decoded.recycle() }
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        if (bitmap.isRecycled || Thread.currentThread().isInterrupted) return null
        val side = maxOf(bitmap.width, bitmap.height)
        val scaled = if (side > MAX_SIDE) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width.toLong() * MAX_SIDE / side).toInt().coerceAtLeast(1),
            (bitmap.height.toLong() * MAX_SIDE / side).toInt().coerceAtLeast(1), true) else bitmap
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
        return try { if (scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null }
            finally { if (scaled !== bitmap) scaled.recycle() }
    }
    private const val MAX_SIDE = 1024
}
