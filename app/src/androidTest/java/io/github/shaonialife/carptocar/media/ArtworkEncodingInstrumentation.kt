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
            results.putString("result", "3 Android Bitmap fixtures passed")
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
        check(options.outWidth in 1..320 && options.outHeight in 1..320)
        return options
    }

    private fun opaqueThumbnail() {
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(960 * 640) { 0xff000000.toInt() or ((it * 1103515245 + 12345) and 0xffffff) }
            bitmap.setPixels(pixels, 0, 960, 0, 0, 960, 640)
            val result = checkNotNull(ArtworkEncoder.loadBytes(encoded(bitmap, Bitmap.CompressFormat.JPEG)))
            check(checkBounds(result).outMimeType == "image/jpeg")
        } finally { bitmap.recycle() }
    }

    private fun transparentThumbnail() {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0x4000ff00)
            val result = checkNotNull(ArtworkEncoder.loadBytes(encoded(bitmap, Bitmap.CompressFormat.PNG)))
            check(checkBounds(result).outMimeType == "image/png")
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
}
