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

    @Test fun explicitStopWinsAndRepeatedStartDoesNotDefeatRateLimit() {
        val delivery = CarLifeVehicleDelivery()
        assertTrue(delivery.allowed(1, 0))
        delivery.sent(1, 0)
        delivery.control(1, true)
        assertFalse(delivery.allowed(1, 899))
        assertTrue(delivery.allowed(1, 900))
        delivery.control(1, false)
        assertFalse(delivery.allowed(1, 10000))
        delivery.control(1, true)
        assertTrue(delivery.allowed(1, 10000))
    }

    @Test fun legacyListMustNotMistakeCountForModuleId() {
        val list = byteArrayOf(8, 1, 18, 2, 8, 4)
        assertEquals(listOf(CarLifeVehicleData.Control(4, 0)), CarLifeVehicleData.controls(list))
        assertNull(CarLifeVehicleData.control(list))
        assertNull(CarLifeVehicleData.controls(byteArrayOf(8, 2, 18, 2, 8, 4)))
        assertNull(CarLifeVehicleData.controls(byteArrayOf(8, 1, 18, 2, 8, -128)))
    }

    @Test fun gearValuesFollowCarLifeRatherThanSourceEnumOrder() {
        for ((gear, value) in listOf(PascdGear.N to 1, PascdGear.P to 2, PascdGear.D to 3, PascdGear.R to 5)) {
            assertArrayEquals(byteArrayOf(8, value.toByte()), CarLifeVehicleData.gear(gear))
        }
        assertArrayEquals(byteArrayOf(8, 2, 18, 6, 8, 1, 16, 1, 24, 1, 18, 6, 8, 4, 16, 1, 24, 1),
            CarLifeVehicleData.capabilities(setOf(1, 4)))
    }

    @Test fun gearChangeBypassesRepeatLimitButNeverOverridesStopOrGpsSwitch() {
        val previous = CarGpsOption.enabled
        try {
            CarGpsOption.save(null, true)
            val output = ByteArrayOutputStream()
            val hu = client(output)
            fun sample(gear: PascdGear?) = hu.sendCarMotion(PascdMotion(1.0, 0, gear), 0, CarGpsOption.generation) { true }
            sample(PascdGear.D)
            assertArrayEquals(CarLifeProtocol.packCommand(0x1800F, byteArrayOf(8, 0)) +
                CarLifeProtocol.packCommand(0x18029, byteArrayOf(8, 3)), output.toByteArray())
            output.reset()
            sample(PascdGear.D)
            sample(null)
            assertEquals(0, output.size())
            sample(PascdGear.R)
            assertArrayEquals(CarLifeProtocol.packCommand(0x18029, byteArrayOf(8, 5)), output.toByteArray())
            output.reset()
            receive(hu, CarLifeVehicleData.STOP, byteArrayOf(8, 4))
            sample(PascdGear.P)
            assertEquals(0, output.size())
            receive(hu, CarLifeVehicleData.START, byteArrayOf(8, 4, 24, 0))
            sample(PascdGear.P)
            assertArrayEquals(CarLifeProtocol.packCommand(0x18029, byteArrayOf(8, 2)), output.toByteArray())
            output.reset()
            CarGpsOption.save(null, false)
            sample(PascdGear.D)
            assertEquals(0, output.size())
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
                hu.sendCarMotion(PascdMotion(1.0, 36, PascdGear.D), 0, CarGpsOption.generation) { true }
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
    private fun client(output: ByteArrayOutputStream): CarLifeHuClient =
        CarLifeHuClient(log = {}, onVideoFrame = { _, _, _ -> }, vehicleClock = { 0L }).also {
            set(it, "running", AtomicBoolean(true))
            set(it, "versionMatched", true)
            set(it, "cmdOut", output)
        }

    @Test fun enabledFreshSampleIsWrittenButDisabledStaleAndRetiredSourcesAreNot() {
        val previous = CarGpsOption.enabled
        try {
            CarGpsOption.save(null, true)
            val output = ByteArrayOutputStream()
            val hu = client(output)
            val generation = CarGpsOption.generation
            val motion = PascdMotion(10.0, 36)
            hu.sendCarMotion(motion, -2000, generation) { true }
            hu.sendCarMotion(motion, 0, generation) { false }
            assertEquals(0, output.size())
            CarGpsOption.save(null, false)
            hu.sendCarMotion(motion, 0, generation) { true }
            CarGpsOption.save(null, true)
            hu.sendCarMotion(motion, 0, generation) { true }
            assertEquals(0, output.size())
            hu.sendCarMotion(motion, 0, CarGpsOption.generation) { true }
            assertArrayEquals(CarLifeProtocol.packCommand(0x1800F, byteArrayOf(8, 36)), output.toByteArray())
        } finally { CarGpsOption.save(null, previous) }
    }

    @Test fun requestBeforeSourceIsUpdatedOnFirstSampleAndStopPreventsSpeed() {
        val previous = CarGpsOption.enabled
        try {
            CarGpsOption.save(null, true)
            val output = ByteArrayOutputStream()
            val hu = client(output)
            receive(hu, CarLifeVehicleData.SUBSCRIBE)
            assertArrayEquals(CarLifeProtocol.packCommand(0x18032, byteArrayOf(8, 0)), output.toByteArray())
            output.reset()
            receive(hu, CarLifeVehicleData.STOP, byteArrayOf(8, 1))
            hu.sendCarMotion(PascdMotion(1.0, 36), 0, CarGpsOption.generation) { true }
            assertArrayEquals(CarLifeProtocol.packCommand(0x18032, CarLifeVehicleData.capabilities(setOf(1))), output.toByteArray())
            output.reset()
            receive(hu, CarLifeVehicleData.START, byteArrayOf(8, 1, 24, 10))
            hu.sendCarMotion(PascdMotion(2.0, 36), 0, CarGpsOption.generation) { true }
            assertArrayEquals(CarLifeProtocol.packCommand(0x1800F, byteArrayOf(8, 36)), output.toByteArray())
            output.reset()
            CarGpsOption.save(null, false)
            hu.carGpsOptionChanged()
            assertArrayEquals(CarLifeProtocol.packCommand(0x18032, byteArrayOf(8, 0)), output.toByteArray())
        } finally { CarGpsOption.save(null, previous) }
    }
}
