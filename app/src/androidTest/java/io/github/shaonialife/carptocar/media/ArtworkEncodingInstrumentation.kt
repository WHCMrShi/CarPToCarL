package io.github.shaonialife.carptocar.media

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import java.io.ByteArrayOutputStream

/** 直接测试 Android Bitmap 编解码，不用其他 JVM 编码器冒充设备结果。 */
class ArtworkEncodingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        try {
            opaqueThumbnail()
            transparentThumbnail()
            smallNativeJpeg()
            musicSizeBoundary()
            navigationTransparentPlaceholder()
            navigationFullyTransparentInput()
            navigationNativeIcon()
            navigationOversizedIcon()
            results.putString("result", "8 Android Bitmap fixtures passed")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("failure", "${error.javaClass.simpleName}: ${error.message}")
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun encoded(bitmap: Bitmap, format: Bitmap.CompressFormat) = ByteArrayOutputStream().let {
        check(bitmap.compress(format, 85, it)); it.toByteArray()
    }

    private fun checkBounds(bytes: ByteArray): BitmapFactory.Options {
        check(bytes.size <= 64 * 1024)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        check(options.outWidth in 1..256 && options.outHeight in 1..256)
        return options
    }

    private fun opaqueThumbnail() {
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(960 * 640) { 0xff000000.toInt() or ((it * 1103515245 + 12345) and 0xffffff) }
            bitmap.setPixels(pixels, 0, 960, 0, 0, 960, 640)
            val result = checkNotNull(ArtworkEncoder.loadBytes(encoded(bitmap, Bitmap.CompressFormat.JPEG)))
            val bounds = checkBounds(result)
            check(bounds.outMimeType == "image/jpeg")
            check(bounds.outWidth == 256 && bounds.outHeight == 170)
        } finally { bitmap.recycle() }
    }

    private fun transparentThumbnail() {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0x4000ff00)
            val result = checkNotNull(ArtworkEncoder.loadBytes(encoded(bitmap, Bitmap.CompressFormat.PNG)))
            val bounds = checkBounds(result)
            check(bounds.outMimeType == "image/png")
            check(bounds.outWidth == 256 && bounds.outHeight == 256)
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(result, 0, result.size))
            try { check((decoded.getPixel(10, 10) ushr 24) in 60..68) }
            finally { decoded.recycle() }
        } finally { bitmap.recycle() }
    }

    private fun smallNativeJpeg() {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xffff0000.toInt())
            val original = encoded(bitmap, Bitmap.CompressFormat.JPEG)
            check(original.contentEquals(checkNotNull(ArtworkEncoder.loadBytes(original))))
        } finally { bitmap.recycle() }
    }

    private fun musicSizeBoundary() {
        for (side in listOf(256, 320)) {
            val source = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(0xff123456.toInt())
                val original = encoded(source, Bitmap.CompressFormat.JPEG)
                val output = checkNotNull(ArtworkEncoder.loadBytes(original))
                val bounds = checkBounds(output)
                check(bounds.outWidth == 256 && bounds.outHeight == 256)
                if (side == 256) check(original.contentEquals(output))
            } finally { source.recycle() }
        }
    }

    private fun navigationFullyTransparentInput() {
        val source = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(android.graphics.Color.TRANSPARENT)
            val output = NavigationIcon.render(encoded(source, Bitmap.CompressFormat.PNG))
            check(output.contentEquals(NavigationIcon.render(null)))
            // One visible source pixel must keep the full navigation canvas.
            source.setPixel(47, 47, 0x8000ff00.toInt())
            val visible = NavigationIcon.render(encoded(source, Bitmap.CompressFormat.PNG))
            val image = checkNotNull(BitmapFactory.decodeByteArray(visible, 0, visible.size))
            try {
                check(image.width == 256 && image.height == 256)
                check(image.getPixel(151, 151) ushr 24 in 126..130)
            } finally { image.recycle() }
        } finally { source.recycle() }
    }

    private fun navigationTransparentPlaceholder() {
        val bytes = NavigationIcon.render(null)
        check(bytes.contentEquals(NavigationIcon.render(byteArrayOf(1, 2, 3))))
        val image = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            check(image.width == 16 && image.height == 16)
            val pixels = IntArray(16 * 16)
            image.getPixels(pixels, 0, 16, 0, 0, 16, 16)
            check(pixels.all { it ushr 24 == 0 })
        } finally { image.recycle() }
    }

    private fun navigationNativeIcon() {
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(0x80ff0000.toInt())
            val bytes = NavigationIcon.render(encoded(source, Bitmap.CompressFormat.PNG))
            val image = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                check(image.width == 256 && image.height == 256)
                check(image.getPixel(95, 112) ushr 24 == 0)
                check(image.getPixel(96, 111) ushr 24 == 0)
                check(image.getPixel(96, 112) ushr 24 in 126..130)
                check(image.getPixel(159, 143) ushr 24 in 126..130)
                check(image.getPixel(160, 143) ushr 24 == 0)
                check(image.getPixel(159, 144) ushr 24 == 0)
            } finally { image.recycle() }
        } finally { source.recycle() }
    }

    private fun navigationOversizedIcon() {
        val source = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(0xff00ff00.toInt())
            val bytes = NavigationIcon.render(encoded(source, Bitmap.CompressFormat.PNG))
            val image = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                check(image.getPixel(128, 63) ushr 24 == 0)
                check(image.getPixel(0, 64) == 0xff00ff00.toInt())
                check(image.getPixel(255, 191) == 0xff00ff00.toInt())
                check(image.getPixel(128, 192) ushr 24 == 0)
            } finally { image.recycle() }
        } finally { source.recycle() }
    }
}
