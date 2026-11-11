package io.github.shaonialife.carptocar.video

import org.junit.Assert.assertEquals
import org.junit.Test

class StallJudgeTest {
    @Test
    fun frameAgeDistinguishesNeverReceivedFromJustReceived() {
        // -1 是「本轮还没收到过帧」的哨兵：引擎刚起来那几秒不能按「距今无穷大」判停摆
        assertEquals(-1L, StallJudge.frameAgeMs(lastFrameAt = 0L, now = 1_000_000L))
        assertEquals(-1L, StallJudge.frameAgeMs(lastFrameAt = -5L, now = 1_000_000L))
        assertEquals(0L, StallJudge.frameAgeMs(lastFrameAt = 1_000_000L, now = 1_000_000L))
        assertEquals(2_400L, StallJudge.frameAgeMs(lastFrameAt = 1_000_000L, now = 1_002_400L))
        // 时钟异常（now 早于 last）不能算出负数，否则会被当成「刚收到」而永不判停摆
        assertEquals(0L, StallJudge.frameAgeMs(lastFrameAt = 2_000L, now = 1_000L))
    }

}
