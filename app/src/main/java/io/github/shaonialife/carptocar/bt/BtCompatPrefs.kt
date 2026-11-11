package io.github.shaonialife.carptocar.bt

import android.content.Context

/** Q4 固定关闭兼容探针，忽略历史设置。 */
object BtCompatPrefs {
    @Suppress("UNUSED_PARAMETER")
    fun enabled(context: Context): Boolean = false
}
