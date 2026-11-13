package io.github.shaonialife.carptocar.ui

import android.annotation.SuppressLint
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View

private const val HOLD_DURATION_MS = 800L

/** A deliberate hold; uses elapsed time so disabling animations cannot skip confirmation. */
@SuppressLint("ClickableViewAccessibility")
internal class HoldToCancel(
    private val view: View,
    private val allowed: () -> Boolean,
    private val confirm: () -> Unit,
    private val progressColor: () -> Int = { view.context.getColor(io.github.shaonialife.carptocar.R.color.m3_hold_overlay) },
) {
    private var consuming = false
    private var startedAt: Long? = null
    private var original: Drawable? = null
    private var progress: ClipDrawable? = null
    private val tick = object : Runnable {
        override fun run() {
            val start = startedAt ?: return
            if (!allowed() || !view.isShown || !view.hasWindowFocus()) {
                cancel()
                return
            }
            val elapsed = SystemClock.uptimeMillis() - start
            progress?.level = (elapsed * 10_000 / HOLD_DURATION_MS).coerceIn(0, 10_000).toInt()
            if (elapsed >= HOLD_DURATION_MS) {
                cancel()
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                confirm()
            } else view.postOnAnimation(this)
        }
    }

    init {
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                consuming = allowed()
                if (consuming) {
                    cancel()
                    original = view.background
                    val fill = GradientDrawable().apply {
                        setColor(progressColor())
                        cornerRadius = view.resources.getDimension(io.github.shaonialife.carptocar.R.dimen.q4_corner_action)
                    }
                    progress = ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL)
                    view.background = LayerDrawable(arrayOf(original!!, progress!!))
                    startedAt = SystemClock.uptimeMillis()
                    view.isPressed = true
                    view.postOnAnimation(tick)
                }
            }
            if (!consuming) false else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.y < 0 ||
                        event.x >= view.width || event.y >= view.height) cancel()
                    MotionEvent.ACTION_POINTER_DOWN -> cancel()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        cancel()
                        consuming = false
                    }
                }
                true
            }
        }
        // Touch events above are consumed; this action supports accessibility long-click.
        view.setOnLongClickListener {
            if (allowed()) { cancel(); confirm(); true } else false
        }
    }

    fun cancel() {
        startedAt = null
        view.removeCallbacks(tick)
        original?.let { view.background = it }
        original = null
        progress = null
        view.isPressed = false
    }
}
