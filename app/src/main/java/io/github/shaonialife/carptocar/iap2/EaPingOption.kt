package io.github.shaonialife.carptocar.iap2

import android.content.Context

internal object EaPingOption {
    @Volatile
    var enabled = false
        private set

    private fun preferences(context: Context) = context.getSharedPreferences("ea_ping_option_v1", Context.MODE_PRIVATE)

    fun load(context: Context) {
        enabled = preferences(context).getBoolean("enabled", false)
    }

    fun save(context: Context, value: Boolean) {
        enabled = value
        preferences(context).edit().putBoolean("enabled", value).apply()
    }
}
