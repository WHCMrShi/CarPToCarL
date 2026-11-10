package io.github.shaonialife.carptocar.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream

internal object NavigationIconLayout {
    const val SIZE = 256
    const val PLACEHOLDER_SIZE = 16
    data class Placement(val left: Float, val top: Float, val width: Float, val height: Float)
    fun fit(width: Int, height: Int): Placement {
        require(width > 0 && height > 0)
        val scale = minOf(1f, SIZE.toFloat() / width, SIZE.toFloat() / height)
        val w = width * scale
        val h = height * scale
        return Placement((SIZE - w) / 2f, (SIZE - h) / 2f, w, h)
    }
}

/** Artwork worker only. A real transparent file replaces the cover; zero bytes would clear its reference. */
internal object NavigationIcon {
    fun render(bytes: ByteArray?, log: (String) -> Unit = {}): ByteArray {
        var source: Bitmap? = null
        if (bytes != null && bytes.size <= 65_535) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outMimeType == "image/png" && bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304) {
                source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inScaled = false })
            }
        }
        try {
            // Empty input and fully transparent PNGs both clear stale artwork with a small valid file.
            val image = source?.takeUnless { isFullyTransparent(it) }
            val size = if (image == null) NavigationIconLayout.PLACEHOLDER_SIZE else NavigationIconLayout.SIZE
            val canvasImage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            return try {
                canvasImage.eraseColor(Color.TRANSPARENT)
                val placement = image?.let { image ->
                    NavigationIconLayout.fit(image.width, image.height).also { p ->
                        Canvas(canvasImage).drawBitmap(image, null, RectF(p.left, p.top, p.left + p.width, p.top + p.height), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                }
                ByteArrayOutputStream().use { out ->
                    check(canvasImage.compress(Bitmap.CompressFormat.PNG, 100, out))
                    log("ARTWORK: NAV-ART inputBytes=${bytes?.size ?: 0} source=${source?.width ?: 0}x${source?.height ?: 0} sourceHasAlpha=${source?.hasAlpha()} canvas=${size}x${size} transparentBackground=true draw=$placement outputBytes=${out.size()} placeholder=${image == null}")
                    out.toByteArray()
                }
            } finally { canvasImage.recycle() }
        } finally { source?.recycle() }
    }
    private fun isFullyTransparent(image: Bitmap): Boolean {
        if (!image.hasAlpha()) return false
        // Scan one row at a time to avoid allocating an extra full-resolution pixel buffer.
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            if (row.any { it ushr 24 != 0 }) return false
        }
        return true
    }

}
