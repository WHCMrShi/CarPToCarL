package io.github.shaonialife.carptocar.carlife

import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 有界、单消费者触摸发送。队列和输出流属于同一连接，stop 后不再接受事件。 */
internal class TouchForwarder(
    private val output: OutputStream,
    private val onWrite: () -> Unit = {},
    private val onFailure: (Exception) -> Unit = {},
    private val onOverflow: () -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val entries = IntArray(CAPACITY * 4)
    private var head = 0
    private var size = 0
    private var stopped = false
    private var resetting = false
    private var dropping = false
    private var worker: Thread? = null

    fun start() =
        lock.withLock {
            if (stopped || worker != null) return
            worker =
                Thread({ run() }, "carlife-touch").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY + 1
                    start()
                }
        }

    fun stop() =
        lock.withLock {
            stopped = true
            size = 0
            ready.signal()
        }

    fun enqueue(
        service: Int,
        x: Int,
        y: Int,
        action: Int = -1,
    ) {
        var overflow = false
        lock.withLock {
            if (stopped) return
            val terminal =
                service == CarLifeProtocol.TOUCH_UP ||
                    (service == CarLifeProtocol.TOUCH_ACTION && action == CarLifeProtocol.TouchAction.UP)
            if (dropping) {
                if (terminal) dropping = false
                return
            }
            val px = x.coerceIn(0, 8191)
            val py = y.coerceIn(0, 8191)
            if (isMove(service, action) && size > 0) {
                val last = index(size - 1)
                // 不跨单点/复合协议或指针动作合并。
                if (entries[last] == service && entries[last + 3] == action) {
                    entries[last + 1] = px
                    entries[last + 2] = py
                    return
                }
            }
            if (size == CAPACITY) {
                var removable = -1
                for (i in 0 until size) {
                    val at = index(i)
                    if (isMove(entries[at], entries[at + 3])) {
                        removable = i
                        break
                    }
                }
                if (removable >= 0) {
                    for (i in removable until size - 1) {
                        val from = index(i + 1)
                        val to = index(i)
                        for (j in 0..3) entries[to + j] = entries[from + j]
                    }
                    size--
                } else {
                    // 边界事件也积满：不能只丢 UP。清除尚未发送的手势，由 worker
                    // 收尾实际已写出的触点；等物理 UP 后才接受下一笔，保持有界且不堵读线程。
                    size = 0
                    resetting = true
                    dropping = !terminal
                    ready.signal()
                    overflow = true
                }
            }
            if (!overflow) {
                val at = index(size++)
                entries[at] = service
                entries[at + 1] = px
                entries[at + 2] = py
                entries[at + 3] = action
                ready.signal()
            }
        }
        if (overflow) onOverflow()
    }

    private fun index(offset: Int) = ((head + offset) % CAPACITY) * 4

    private fun run() {
        val event = IntArray(4)
        val encoder = TouchFrameEncoder()
        var pressed = false
        var second = false
        var compound = false
        var x = 0
        var y = 0
        var secondX = 0
        var secondY = 0

        fun write(
            service: Int,
            px: Int,
            py: Int,
            action: Int = -1,
        ) {
            val length = encoder.encode(service, px, py, action)
            output.write(encoder.bytes, 0, length)
            output.flush()
            onWrite()
        }
        try {
            while (true) {
                val reset =
                    lock.withLock {
                        while (!stopped && !resetting && size == 0) ready.await()
                        if (stopped) return
                        if (resetting) {
                            resetting = false
                            true
                        } else {
                            val at = index(0)
                            for (i in 0..3) event[i] = entries[at + i]
                            head = (head + 1) % CAPACITY
                            size--
                            false
                        }
                    }
                if (reset) {
                    if (pressed) {
                        if (compound && second) {
                            write(
                                CarLifeProtocol.TOUCH_ACTION,
                                secondX,
                                secondY,
                                CarLifeProtocol.TouchAction.withPointer(CarLifeProtocol.TouchAction.POINTER_UP, 1),
                            )
                        }
                        write(
                            if (compound) CarLifeProtocol.TOUCH_ACTION else CarLifeProtocol.TOUCH_UP,
                            x,
                            y,
                            if (compound) CarLifeProtocol.TouchAction.UP else -1,
                        )
                    }
                    pressed = false
                    second = false
                    compound = false
                    continue
                }
                val service = event[0]
                val action = event[3]
                write(service, event[1], event[2], action)
                if (service == CarLifeProtocol.TOUCH_ACTION) {
                    compound = true
                    when (action and 255) {
                        CarLifeProtocol.TouchAction.DOWN -> pressed = true
                        CarLifeProtocol.TouchAction.UP -> {
                            pressed = false
                            second = false
                            compound = false
                        }
                        CarLifeProtocol.TouchAction.POINTER_DOWN -> {
                            second = true
                            secondX = event[1]
                            secondY = event[2]
                        }
                        CarLifeProtocol.TouchAction.POINTER_UP -> second = false
                    }
                } else {
                    when (service) {
                        CarLifeProtocol.TOUCH_DOWN -> {
                            pressed = true
                            compound = false
                        }
                        CarLifeProtocol.TOUCH_UP -> {
                            pressed = false
                            second = false
                            compound = false
                        }
                    }
                }
                if (service != CarLifeProtocol.TOUCH_ACTION || (action and 255) < 5) {
                    x = event[1]
                    y = event[2]
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            onFailure(error)
        } finally {
            stop()
        }
    }

    private fun isMove(
        service: Int,
        action: Int,
    ) = service == CarLifeProtocol.TOUCH_MOVE ||
        (service == CarLifeProtocol.TOUCH_ACTION && action == CarLifeProtocol.TouchAction.MOVE)

    companion object {
        private const val CAPACITY = 8
    }
}
