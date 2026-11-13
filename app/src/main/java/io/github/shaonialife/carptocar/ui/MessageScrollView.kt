package io.github.shaonialife.carptocar.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** 在首次测量中限制正文高度，避免弹出后重新限高导致窗口跳位。 */
class MessageScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val windowManager = context.getSystemService(android.view.WindowManager::class.java)
        val cap = (windowManager.currentWindowMetrics.bounds.height() * 0.46f).toInt()
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (mode == MeasureSpec.UNSPECIFIED) cap else minOf(size, cap)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}
