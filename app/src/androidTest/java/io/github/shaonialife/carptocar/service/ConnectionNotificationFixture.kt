package io.github.shaonialife.carptocar.service

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import io.github.shaonialife.carptocar.SessionController

/** Exercises both foreground notification variants without opening a vehicle connection. */
internal class ConnectionNotificationFixture(private val instrumentation: Instrumentation) {
    fun run() {
        val targetContext = instrumentation.targetContext
        val manager = checkNotNull(targetContext.getSystemService(NotificationManager::class.java))
        try {
            instrumentation.runOnMainSync {
                SessionController.phaseHint = "等待车机"
                CarPlayService.start(targetContext)
            }
            val initial = awaitNotification(manager, "等待车机")
            check(!initial.requestsLiveUpdate())
            check(initial.notification.actions.map { it.title.toString() } == listOf("断开", "打开界面"))

            SessionController.phaseHint = "等待车机邀请"
            val ordinary = awaitNotification(manager, "等待车机邀请")
            check(!ordinary.requestsLiveUpdate())
            checkStable(manager, ordinary)

            instrumentation.runOnMainSync {
                SessionController.sessionStarting.set(true)
                SessionController.phaseHint = "正在连接车机"
            }
            val live = awaitNotification(manager, "正在连接车机")
            check(live.requestsLiveUpdate())
            checkStable(manager, live)
        } finally {
            instrumentation.runOnMainSync {
                CarPlayService.stop(targetContext)
                SessionController.sessionStarting.set(false)
                SessionController.phaseHint = null
            }
        }
    }

    private fun awaitNotification(manager: NotificationManager, text: String): StatusBarNotification {
        val deadline = SystemClock.uptimeMillis() + 2_500L
        while (SystemClock.uptimeMillis() < deadline) {
            val posted = currentNotification(manager)
            if (posted?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text) {
                return posted
            }
            SystemClock.sleep(25L)
        }
        error("Notification did not show '$text' before the previous polling interval")
    }

    private fun checkStable(manager: NotificationManager, posted: StatusBarNotification) {
        SystemClock.sleep(3_300L)
        check(currentNotification(manager)?.postTime == posted.postTime) { "Unchanged notification was republished" }
    }

    private fun currentNotification(manager: NotificationManager): StatusBarNotification? =
        manager.activeNotifications.singleOrNull { it.id == CarPlayService.NOTIFICATION_ID }

    private fun StatusBarNotification.requestsLiveUpdate(): Boolean =
        notification.extras.getBoolean("android.requestPromotedOngoing")
}
