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
            val canvasImage = Bitmap.createBitmap(NavigationIconLayout.SIZE, NavigationIconLayout.SIZE, Bitmap.Config.ARGB_8888)
            return try {
                canvasImage.eraseColor(Color.TRANSPARENT)
                val placement = source?.let { image ->
                    NavigationIconLayout.fit(image.width, image.height).also { p ->
                        Canvas(canvasImage).drawBitmap(image, null, RectF(p.left, p.top, p.left + p.width, p.top + p.height), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                }
                ByteArrayOutputStream().use { out ->
                    check(canvasImage.compress(Bitmap.CompressFormat.PNG, 100, out))
                    log("ARTWORK: NAV-ART inputBytes=${bytes?.size ?: 0} source=${source?.width ?: 0}x${source?.height ?: 0} sourceHasAlpha=${source?.hasAlpha()} canvas=256x256 transparentBackground=true draw=$placement outputBytes=${out.size()} placeholder=${source == null}")
                    out.toByteArray()
                }
            } finally { canvasImage.recycle() }
        } finally { source?.recycle() }
    }
}
