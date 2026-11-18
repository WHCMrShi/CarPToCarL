package io.github.shaonialife.carptocar.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.View
import androidx.annotation.ColorInt
import io.github.shaonialife.carptocar.R
import kotlin.math.max

/** Cached vector arcs beneath the hold-progress layer; no bitmap, blur or per-frame geometry. */
internal class ConnectionButtonTexture(private val view: View) : View.OnAttachStateChangeListener {
    private val texture = ArcDrawable(view.resources.getDimension(R.dimen.q4_corner_action))
    private val visibleRect = Rect()
    private var resumed = false
    private var connecting = false
    private var startedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!shouldAnimate()) return
            if (view.isShown && view.getLocalVisibleRect(visibleRect)) {
                texture.angle = ((SystemClock.uptimeMillis() - startedAt) % PERIOD_MS) * 360f / PERIOD_MS
                // Use the view callback: HoldToCancel temporarily wraps the whole background.
                view.invalidate()
            }
            view.postDelayed(this, FRAME_MS)
        }
    }

    init {
        view.addOnAttachStateChangeListener(this)
    }

    fun apply(base: Drawable, animate: Boolean, @ColorInt color: Int) {
        view.removeCallbacks(tick)
        connecting = animate
        texture.angle = 0f
        texture.color = color
        startedAt = SystemClock.uptimeMillis()
        view.background = LayerDrawable(arrayOf(base, texture))
        schedule()
    }

    fun resume() {
        resumed = true
        schedule()
    }

    fun pause() {
        resumed = false
        view.removeCallbacks(tick)
    }

    private fun shouldAnimate() =
        resumed && connecting && view.isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()

    private fun schedule() {
        view.removeCallbacks(tick)
        if (shouldAnimate()) view.postDelayed(tick, FRAME_MS)
    }

    override fun onViewAttachedToWindow(v: View) = schedule()

    override fun onViewDetachedFromWindow(v: View) {
        view.removeCallbacks(tick)
    }

    private class ArcDrawable(private val cornerRadius: Float) : Drawable() {
        private val arcs = Path().apply {
            // Matches the selected 340 × 56 preview at 1× (12 seconds per revolution).
            for (radius in 26..369 step 17) {
                val r = radius.toFloat()
                addOval(280f - r, 60f - r * .78f, 280f + r, 60f + r * .78f, Path.Direction.CW)
            }
        }
        private val clip = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        private var scale = 1f
        var angle = 0f
        var color: Int
            get() = paint.color
            set(value) {
                paint.color = value
            }

        override fun onBoundsChange(bounds: Rect) {
            scale = max(bounds.width() / 340f, bounds.height() / 56f)
            clip.rewind()
            clip.addRoundRect(
                bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
                cornerRadius, cornerRadius, Path.Direction.CW,
            )
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val saved = canvas.save()
            canvas.clipPath(clip)
            canvas.translate(bounds.exactCenterX() - 170f * scale, bounds.exactCenterY() - 28f * scale)
            canvas.scale(scale, scale)
            canvas.rotate(angle, 280f, 60f)
            canvas.drawPath(arcs, paint)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val PERIOD_MS = 12_000L
        const val FRAME_MS = 33L // Approximately 30 fps; rotation speed remains one turn per 12 s.
    }
}
