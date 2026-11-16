package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首轮初始化顺序的闸门（[CarLifeHuClient.shouldDeferHotResend]）。
 *
 * 车机 /info 更新申请值时，不得绕过首轮的配置发送及本地间隔。
 * 间隔结束不代表引擎应用成功，65570 也不是特征 ACK。
 */
class InitOrderTest {

    @Test
    fun `有序模式下首条 98311 未发出时必须让位`() {
        assertTrue(CarLifeHuClient.shouldDeferHotResend(firstInitSent = false))
    }

    @Test
    fun `有序模式下首条已发出后可以直接重发`() {
        assertFalse(CarLifeHuClient.shouldDeferHotResend(firstInitSent = true))
    }

}
