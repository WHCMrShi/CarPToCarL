package io.github.shaonialife.carptocar.carlife

import android.view.KeyEvent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Commands only. CarLife owns mixing; playback state must never gate or clear merged PCM. */
internal object CarLifeMediaCommands {
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "carlife-media-command").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** Socket writes must not occupy iAP2/event readers; overload never runs work on the caller. */
    fun enqueue(
        isCurrent: () -> Boolean = { true },
        onRetired: () -> Unit = {},
        action: () -> Unit,
    ): Boolean = try {
        worker.execute { if (isCurrent()) action() else onRetired() }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun accepts(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_STOP ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    data class Result(val play: Boolean, val sessionSent: Boolean, val hardKeyQueued: Boolean, val pauseSent: Boolean) {
        val submitted: Boolean get() = sessionSent || hardKeyQueued || pauseSent
    }

    fun dispatch(
        keyCode: Int,
        playing: Boolean,
        session: (Int) -> Boolean,
        enqueueHardKey: (Int) -> Boolean,
        pauseMedia: () -> Boolean,
    ): Result? {
        val play = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> true
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> false
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> !playing
            else -> return null
        }
        val sessionSent = runCatching { session(if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE) }.getOrDefault(false)
        val hardKey = if (play) CarLifeProtocol.HardKey.MEDIA_START else CarLifeProtocol.HardKey.MEDIA_STOP
        val hardKeyQueued = runCatching { enqueueHardKey(hardKey) }.getOrDefault(false)
        val pauseSent = !play && runCatching { pauseMedia() }.getOrDefault(false)
        return Result(play, sessionSent, hardKeyQueued, pauseSent)
    }
}
