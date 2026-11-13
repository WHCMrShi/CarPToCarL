package io.github.shaonialife.carptocar.launcher.settings

import android.content.Context

/**
 * 「启动后自动连接」偏好：打开 App 就自动发起 `SessionController.startConnection`，
 * 不必每次手动点英雄卡上的「启动连接」。
 *
 * 默认**开启**——上车 → 打开 App → 自动连，是这个应用最常见的用法；
 * 想在连之前先改分辨率/劫持开关（这两项都是「下次连接起效」）的人，
 * 在「连接准备」卡里关掉即可，关掉后仍需手动点按钮。
 *
 * 只在**冷启动**（`savedInstanceState == null`）时触发一次，
 * 旋转 / 重建不会重复发起。
 */
object AutoConnectPrefs {
    const val DEFAULT_ENABLED = true

    private const val PREFS_NAME = "settings"
    private const val KEY_ENABLED = "auto_connect_on_launch"

    fun enabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
