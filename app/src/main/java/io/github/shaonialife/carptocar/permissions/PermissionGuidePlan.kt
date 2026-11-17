package io.github.shaonialife.carptocar.permissions

/** Stable steps persisted before leaving the app, so a denied/skipped step never loops. */
internal object PermissionGuidePlan {
    const val INTRO = 0
    const val RUNTIME = 1
    const val OVERLAY = 2
    const val BATTERY = 3
    const val MEDIA = 4
    const val DONE = 5

    fun unasked(missing: List<String>, attempted: Set<String>): List<String> =
        missing.filterNot { it in attempted }

    fun runtimeRequest(missing: List<String>, attempted: Set<String>): List<String> {
        val request = unasked(missing, attempted)
        val fine = "android.permission.ACCESS_FINE_LOCATION"
        val coarse = "android.permission.ACCESS_COARSE_LOCATION"
        return if (fine in request && coarse !in request) request + coarse else request
    }

    fun next(step: Int): Int = (step + 1).coerceAtMost(DONE)
}
