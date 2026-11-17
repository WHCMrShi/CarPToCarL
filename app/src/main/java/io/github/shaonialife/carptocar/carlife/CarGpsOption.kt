package io.github.shaonialife.carptocar.carlife

import android.content.Context

/**
 * 「使用车机 GPS」开关（偏好 `car_gps_option_v1`，默认关闭）。
 *
 * ## 它控制什么
 *
 * 开启后转发有效 GPS 坐标（0x18010）、独立 PASCD 车速（0x1800F）和挡位（0x18029）。
 * 无坐标的运动样本不伪装成 GPS；关闭后停止车辆数据转发。
 *
 * ## 为什么给用户留开关
 *
 * 车机定位不是永远更好：有的车机 GPS 天线在仪表台里、精度不如手机；也有车型给的是
 * 「上次熄火时的位置」。用户能一键回到「只用手机 GPS」，比我们替他决定更稳。
 *
 * 默认使用手机定位；用户手动保存过的开关选择保持不变。
 */
object CarGpsOption {
    /** Invalidates queued samples even if the switch is turned off and back on quickly. */
    @Volatile
    internal var generation: Any = Any()
        private set
    private const val PREFS = "car_gps_option_v1"
    private const val KEY_ENABLED = "enabled"
    private const val DEFAULT_ENABLED = false

    /**
     * 当前是否启用。用 `@Volatile` 而不是每次都读 SharedPreferences：iAP2 收帧线程
     * 每秒会问一次，读盘没必要；[load]/[save] 是唯一写入口，两处都同步更新它。
     */
    @Volatile
    var enabled: Boolean = DEFAULT_ENABLED
        private set

    /** 启动时调用一次（[io.github.shaonialife.carptocar.SenderApp]）。 */
    fun load(context: Context?) {
        val app = context?.applicationContext ?: return
        enabled =
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    /** 开关切换时调用（UI 层），立即生效——下一条定位就按新值走。 */
    fun save(
        context: Context?,
        value: Boolean,
    ) {
        if (enabled != value) generation = Any()
        enabled = value
        val app = context?.applicationContext ?: return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }
}
