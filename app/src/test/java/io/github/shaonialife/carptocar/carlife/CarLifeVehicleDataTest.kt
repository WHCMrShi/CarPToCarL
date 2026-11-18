package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.iap2.PascdMotion
import io.github.shaonialife.carptocar.iap2.PascdGear
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class CarLifeVehicleDataTest {
    @Test fun speedAndCapabilitiesMatchWireFormatWithoutInventingGpsOrTimestamp() {
        assertArrayEquals(byteArrayOf(8, 36), CarLifeVehicleData.speed(36))
        assertArrayEquals(byteArrayOf(8, 1, 18, 6, 8, 1, 16, 1, 24, 1),
            CarLifeVehicleData.capabilities(setOf(CarLifeVehicleData.VELOCITY)))
        assertArrayEquals(byteArrayOf(8, 0), CarLifeVehicleData.capabilities(emptySet()))
        assertEquals(CarLifeVehicleData.Control(1, 0), CarLifeVehicleData.control(byteArrayOf(8, 1, 16, 1, 24, 0)))
        assertNull(CarLifeVehicleData.control(byteArrayOf(8, -128)))
        assertNull(CarLifeVehicleData.control(byteArrayOf(8, 1, 24, -1)))
        assertNull(CarLifeVehicleData.control(byteArrayOf(8, -127, -128, -128, -128, 16)))
    }

    @Test fun legacyListIsNotAcceptedAsSingleModuleControl() {
        assertNull(CarLifeVehicleData.control(byteArrayOf(8, 1, 18, 2, 8, 4)))
    }

    @Test fun gearValuesFollowCarLifeRatherThanSourceEnumOrder() {
        for ((gear, value) in listOf(PascdGear.N to 1, PascdGear.P to 2, PascdGear.D to 3, PascdGear.R to 5)) {
            assertArrayEquals(byteArrayOf(8, value.toByte()), CarLifeVehicleData.gear(gear))
        }
        assertArrayEquals(byteArrayOf(8, 2, 18, 6, 8, 1, 16, 1, 24, 1, 18, 6, 8, 4, 16, 1, 24, 1),
            CarLifeVehicleData.capabilities(setOf(1, 4)))
    }

    @Test fun repeatedSamplesAreForwardedWithoutThrottlingOrDeduplication() {
        val previous = CarGpsOption.enabled
        try {
            CarGpsOption.save(null, true)
            val output = ByteArrayOutputStream()
            val hu = client(output)
            val speed = CarLifeProtocol.packCommand(0x1800F, byteArrayOf(8, 0))
            val gear = CarLifeProtocol.packCommand(0x18029, byteArrayOf(8, 3))
            repeat(2) { hu.sendCarMotion(PascdMotion(listOf(0), PascdGear.D), CarGpsOption.generation) { true } }
            hu.sendCarMotion(PascdMotion(listOf(0)), CarGpsOption.generation) { true }
            assertArrayEquals(speed + gear + speed + gear + speed, output.toByteArray())
            output.reset()
            receive(hu, CarLifeVehicleData.STOP, byteArrayOf(8, 4))
            hu.sendCarMotion(PascdMotion(listOf(0), PascdGear.D), CarGpsOption.generation) { true }
            assertArrayEquals(speed, output.toByteArray())
            output.reset()
            receive(hu, CarLifeVehicleData.START, byteArrayOf(8, 4, 24, 0))
            hu.sendCarMotion(PascdMotion(listOf(0), PascdGear.D), CarGpsOption.generation) { true }
            assertArrayEquals(speed + gear, output.toByteArray())
        } finally { CarGpsOption.save(null, previous) }
    }

    @Test fun batchWritesEverySpeedInOrderAndGearOncePerSentence() {
        val previous = CarGpsOption.enabled
        try {
            CarGpsOption.save(null, true)
            val output = ByteArrayOutputStream()
            val hu = client(output)
            val body = "PASCD,100,C,D,0,4,0,10,0.25,1,0.5,1,0.75,0"
            val sentence = "\$$body*%02X".format(body.fold(0) { n, c -> n xor c.code })
            val motion = PascdMotion.parse(sentence)!!
            val expected = listOf(36, 4, 4, 0).fold(byteArrayOf()) { bytes, speed ->
                bytes + CarLifeProtocol.packCommand(0x1800F, byteArrayOf(8, speed.toByte()))
            } + CarLifeProtocol.packCommand(0x18029, byteArrayOf(8, 3))
            repeat(2) { hu.sendCarMotion(motion, CarGpsOption.generation) { true } }
            assertArrayEquals(expected + expected, output.toByteArray())
        } finally { CarGpsOption.save(null, previous) }
    }

    @Test fun stoppingSpeedDoesNotStopGearAndStoppingGearDoesNotStopSpeed() {
        val previous = CarGpsOption.enabled
        try {
            CarGpsOption.save(null, true)
            for ((stopped, expectedId, expectedValue) in listOf(Triple(1, 0x18029, 3), Triple(4, 0x1800F, 36))) {
                val output = ByteArrayOutputStream()
                val hu = client(output)
                receive(hu, CarLifeVehicleData.STOP, byteArrayOf(8, stopped.toByte()))
                hu.sendCarMotion(PascdMotion(listOf(36), PascdGear.D), CarGpsOption.generation) { true }
                assertArrayEquals(CarLifeProtocol.packCommand(expectedId, byteArrayOf(8, expectedValue.toByte())), output.toByteArray())
            }
        } finally { CarGpsOption.save(null, previous) }
    }

    private fun set(hu: CarLifeHuClient, name: String, value: Any) {
        CarLifeHuClient::class.java.getDeclaredField(name).apply { isAccessible = true }.set(hu, value)
    }
    private fun receive(hu: CarLifeHuClient, id: Int, payload: ByteArray = byteArrayOf()) {
        CarLifeHuClient::class.java.getDeclaredMethod("handleMessage", Int::class.javaPrimitiveType, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(hu, id, payload)
    }
    private fun client(output: ByteArrayOutputStream, log: (String) -> Unit = {}): CarLifeHuClient =
        CarLifeHuClient(log = log, onVideoFrame = { _, _, _ -> }).also {
            set(it, "running", AtomicBoolean(true))
            set(it, "versionMatched", true)
            set(it, "cmdOut", output)
        }

    @Test fun enabledSampleIsWrittenButDisabledAndRetiredSourcesAreNot() {
        val previous = CarGpsOption.enabled
        try {
            CarGpsOption.save(null, true)
            val output = ByteArrayOutputStream()
            val hu = client(output)
            val generation = CarGpsOption.generation
            val motion = PascdMotion(listOf(36))
            hu.sendCarMotion(motion, generation) { false }
            assertEquals(0, output.size())
            CarGpsOption.save(null, false)
            hu.sendCarMotion(motion, generation) { true }
            CarGpsOption.save(null, true)
            hu.sendCarMotion(motion, generation) { true }
            assertEquals(0, output.size())
            hu.sendCarMotion(motion, CarGpsOption.generation) { true }
            assertArrayEquals(CarLifeProtocol.packCommand(0x1800F, byteArrayOf(8, 36)), output.toByteArray())
        } finally { CarGpsOption.save(null, previous) }
    }

    @Test fun queryIsLoggedAndReceivesFixedCapabilitiesWithoutLaterUpdates() {
        val previous = CarGpsOption.enabled
        try {
            CarGpsOption.save(null, true)
            val output = ByteArrayOutputStream()
            val logs = mutableListOf<String>()
            val hu = client(output) { logs.add(it) }
            val response = CarLifeProtocol.packCommand(0x18032, CarLifeVehicleData.capabilities(setOf(1, 4)))
            set(hu, "versionMatched", false)
            receive(hu, CarLifeVehicleData.SUBSCRIBE)
            assertArrayEquals(response, output.toByteArray())
            assertTrue(logs.first().contains("收到车辆能力查询 0x10031"))
            output.reset()
            set(hu, "versionMatched", true)
            hu.sendCarMotion(PascdMotion(listOf(36)), CarGpsOption.generation) { true }
            assertArrayEquals(CarLifeProtocol.packCommand(0x1800F, byteArrayOf(8, 36)), output.toByteArray())
            output.reset()
            CarGpsOption.save(null, false)
            hu.carGpsOptionChanged()
            assertEquals(0, output.size())
            receive(hu, CarLifeVehicleData.SUBSCRIBE)
            assertArrayEquals(response, output.toByteArray())
        } finally { CarGpsOption.save(null, previous) }
    }
}
