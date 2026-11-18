package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Test

class CarLifePhoneStateTest {
    @Test
    fun logsEachPhoneReportInArrivalOrder() {
        val lines = mutableListOf<String>()
        val client = CarLifeHuClient(log = lines::add, onVideoFrame = { _, _, _ -> })
        val dispatch = CarLifeHuClient::class.java
            .getDeclaredMethod("handleMessage", Int::class.javaPrimitiveType, ByteArray::class.java)
            .apply { isAccessible = true }

        for (id in listOf(65559, 65559, 65558, 65556, 65558, 65557)) {
            dispatch.invoke(client, id, byteArrayOf())
        }
        assertEquals(
            listOf("通话中 id=65559", "通话中 id=65559", "空闲 id=65558", "来电 id=65556", "空闲 id=65558", "去电 id=65557")
                .map { "CARLIFE: 引擎电话状态=$it" },
            lines,
        )
    }
}
