package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.*
import org.junit.Test

class PascdMotionTest {
    private fun sentence(body: String): String = "\$$body*%02X".format(body.fold(0) { n, c -> n xor c.code })

    @Test fun speedPreservesEveryBatchSampleInWireOrderAndConvertsMetersPerSecond() {
        val sample = PascdMotion.parse(sentence("PASCD,100.5,C,D,0,2,0.00,1.0,0.25,10.0"))!!
        assertEquals(listOf(4, 36), sample.speedsKmh)
        assertEquals(65, PascdMotion.parse(sentence("PASCD,101,C,D,0,1,0.00,17.992"))!!.speedsKmh.single())
        assertFalse(NmeaLocation.parseSentence(sentence("PASCD,101,C,D,0,1,0.00,17.992"))!!.hasPosition)
        assertEquals(0, NmeaLocation.parseSentence(sentence("PASCD,101,C,D,9,1,0.00,17.992"))!!.satellitesUsed)
    }

    @Test fun malformedAndUnknownSamplesNeverBecomeZeroSpeed() {
        for (body in listOf(
            "PASCD,100,C,D,0,0", "PASCD,100,C,D,0,2,0,10",
            "PASCD,100,C,D,0,1,0,NaN", "PASCD,100,C,D,0,1,0,Infinity",
            "PASCD,100,C,D,0,1,0,1e100", "PASCD,100,C,D,0,1,0,-1e100",
            "PASCD,100,C,D,0,1,0,10,extra",
        )) assertNull(body, PascdMotion.parse(sentence(body)))
        assertNull(PascdMotion.parse("\$PASCD,100,C,D,0,1,0,10"))
        assertNull(PascdMotion.parse("\$PASCD,100,C,D,0,1,0,10*XX"))
        assertNull(PascdMotion.parse(sentence("PASCD,100,C,D,0,1,0,10").dropLast(1)))
        assertNull(PascdMotion.parse(sentence("PASCD,100,C,D,0,1,0,10").replace(",10*", ",11*")))
        assertEquals(0, PascdMotion.parse(sentence("PASCD,100,C,D,0,1,0,0"))!!.speedsKmh.single())
    }

    @Test fun conversionHasNoInventedSpeedCountOrUnusedFieldLimits() {
        assertEquals(-4, PascdMotion.parse(sentence("PASCD,unused,X,D,9,1,unused,-1"))!!.speedsKmh.single())
        assertEquals(364, PascdMotion.parse(sentence("PASCD,unused,X,D,9,1,11,101"))!!.speedsKmh.single())
        val batch = List(33) { "11,10" }.joinToString(",")
        assertEquals(List(33) { 36 }, PascdMotion.parse(sentence("PASCD,unused,X,D,9,33,$batch"))!!.speedsKmh)
        for (speed in listOf(-12.34, 0.0, 0.14, 17.992, 101.0)) {
            val result = PascdMotion.parse(sentence("PASCD,100,C,D,0,1,0,$speed"))!!
            assertTrue(kotlin.math.abs(result.speedsKmh.single() - speed * 3.6) <= 0.5)
        }
    }

    @Test fun gearIsIndependentOfSpeedAndUnknownSymbolsAreNeverMappedToDriveOrPark() {
        for (gear in PascdGear.entries) {
            assertEquals(gear, PascdMotion.parse(sentence("PASCD,100,C,${gear.name},0,1,0,0"))!!.gear)
        }
        for (unknown in listOf("S", "M", "L", "", "4", "d")) {
            val sample = PascdMotion.parse(sentence("PASCD,100,C,$unknown,0,1,0,10"))!!
            assertNull(sample.gear)
            assertEquals(36, sample.speedsKmh.single())
        }
        assertEquals(36, PascdMotion.parse(sentence("PASCD,100,X,D,0,1,0,10"))!!.speedsKmh.single())
        assertEquals(36, PascdMotion.parse(sentence("PASCD,100,C,D,9,1,0,10"))!!.speedsKmh.single())
    }
}
