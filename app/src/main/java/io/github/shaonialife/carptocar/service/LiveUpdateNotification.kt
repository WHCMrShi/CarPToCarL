package io.github.shaonialife.carptocar.service

import android.app.Notification
import android.content.Context

/** Native Live Updates compatibility without raising the project's compileSdk. */
internal object LiveUpdateNotification {
    // Public Notification.EXTRA_REQUEST_PROMOTED_ONGOING contract. Older systems ignore it;
    // supported systems decide eligibility and respect the user's notification settings.
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    // Public API on Android 16+. Probe the method itself to also support OEM backports.
    private val setShortCriticalText by lazy {
        runCatching {
            Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java)
        }.getOrNull()
    }

    fun requestPromotion(
        context: Context,
        notification: Notification,
        shortText: String,
    ): Notification {
        notification.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        val shortTextSetter = setShortCriticalText ?: return notification
        return runCatching {
            // Recover the existing standard template, actions and foreground-service flags.
            val builder = Notification.Builder.recoverBuilder(context, notification)
            shortTextSetter.invoke(builder, shortText)
            builder.build()
        }.getOrDefault(notification)
    }
}
