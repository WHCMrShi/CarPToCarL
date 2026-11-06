package io.github.shaonialife.carptocar.carlife

/** HID tip 是状态，不是新的 DOWN；报告间隔不能证明手指已抬起。 */
internal class TouchPressState {
    enum class Change { DOWN, MOVE, UP, IDLE }

    private var generation = 0L

    fun newSession(): Long {
        reset()
        return ++generation
    }

    fun isCurrent(token: Long): Boolean = token == generation

    var pressed = false
        private set

    fun accept(down: Boolean): Change {
        val change =
            when {
                down && !pressed -> Change.DOWN
                down -> Change.MOVE
                pressed -> Change.UP
                else -> Change.IDLE
            }
        pressed = down
        return change
    }

    fun reset() {
        pressed = false
    }
}
