package io.github.shaonialife.carptocar.carlife

/** 两次完整重建后仍零帧就停止升级；真实帧恢复或新的显式启动才重新给预算。 */
internal class ZeroFrameRecoveryBudget {
    private var restarts = 0

    @Synchronized fun acquire(noFrameMs: Long, hasFrame: Boolean, initRetries: Int, projectionActive: Boolean): Boolean {
        if (!projectionActive || hasFrame || noFrameMs < 30_000 || initRetries < 2 || restarts >= 2) return false
        restarts++
        return true
    }

    @Synchronized fun reset() { restarts = 0 }
}
