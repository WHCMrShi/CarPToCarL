package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TouchFrameEncoderTest {
    @Test fun matchesExistingWireEncodingAtVarintBoundaries() {
        val encoder = TouchFrameEncoder()
        val storage = encoder.bytes
        val values = listOf(0, 1, 127, 128, 1411, 8191)
        for (x in values) {
            for (y in values) {
                for (service in listOf(CarLifeProtocol.TOUCH_DOWN, CarLifeProtocol.TOUCH_MOVE, CarLifeProtocol.TOUCH_UP)) {
                    val length = encoder.encode(service, x, y, -1)
                    assertArrayEquals(
                        CarLifeProtocol.packCommand(service, CarLifeProtocol.touchPoint(x, y)),
                        encoder.bytes.copyOf(length),
                    )
                }
                for (action in listOf(0, 1, 2, 261, 262, -1)) {
                    val length = encoder.encode(CarLifeProtocol.TOUCH_ACTION, x, y, action)
                    assertArrayEquals(
                        CarLifeProtocol.packCommand(
                            CarLifeProtocol.TOUCH_ACTION,
                            CarLifeProtocol.touchAction(action, x, y),
                        ),
                        encoder.bytes.copyOf(length),
                    )
                }
            }
        }
        assertSame(storage, encoder.bytes)
    }
}
