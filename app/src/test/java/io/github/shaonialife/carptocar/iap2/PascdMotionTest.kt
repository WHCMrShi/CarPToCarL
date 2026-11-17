package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.*
import org.junit.Test

class PascdMotionTest {
    private fun sentence(body: String): String = "\$$body*%02X".format(body.fold(0) { n, c -> n xor c.code })

    @Test fun speedUsesLatestBatchSampleAndConvertsMetersPerSecond() {
        val sample = PascdMotion.parse(sentence("PASCD,100.5,C,D,0,2,0.00,1.0,0.25,10.0"))!!
        assertEquals(100.75, sample.sampleSeconds, 0.00001)
        assertEquals(36, sample.speedKmh)
        assertEquals(65, PascdMotion.parse(sentence("PASCD,101,C,D,0,1,0.00,17.992"))!!.speedKmh)
        assertFalse(NmeaLocation.parseSentence(sentence("PASCD,101,C,D,0,1,0.00,17.992"))!!.hasPosition)
        assertEquals(0, NmeaLocation.parseSentence(sentence("PASCD,101,C,D,9,1,0.00,17.992"))!!.satellitesUsed)
    }

    @Test fun malformedAndUnknownSamplesNeverBecomeZeroSpeed() {
        for (body in listOf(
            "PASCD,100,C,D,0,0", "PASCD,100,C,D,0,2,0,10",
            "PASCD,100,C,D,0,1,0,NaN", "PASCD,100,C,D,0,1,0,Infinity",
            "PASCD,100,C,D,0,1,0,-1", "PASCD,100,C,D,0,1,0,101",
            "PASCD,NaN,C,D,0,1,0,10", "PASCD,-1,C,D,0,1,0,10",
            "PASCD,100,C,D,0,2,0.5,10,0.2,11", "PASCD,100,C,D,0,1,11,10",
            "PASCD,100,C,D,0,1,0,10,extra",
        )) assertNull(body, PascdMotion.parse(sentence(body)))
        assertNull(PascdMotion.parse("\$PASCD,100,C,D,0,1,0,10"))
        assertNull(PascdMotion.parse("\$PASCD,100,C,D,0,1,0,10*XX"))
        assertNull(PascdMotion.parse(sentence("PASCD,100,C,D,0,1,0,10").dropLast(1)))
        assertNull(PascdMotion.parse(sentence("PASCD,100,C,D,0,1,0,10").replace(",10*", ",11*")))
        assertEquals(0, PascdMotion.parse(sentence("PASCD,100,C,D,0,1,0,0"))!!.speedKmh)
    }

    @Test fun trackerRejectsDuplicatesBackwardsAndDelayedSamplesAndResetsPerSource() {
        val tracker = PascdMotionTracker()
        assertTrue(tracker.accept(PascdMotion(100.0, 36), 1000))
        assertFalse(tracker.accept(PascdMotion(100.0, 36), 2000))
        assertFalse(tracker.accept(PascdMotion(99.0, 36), 2000))
        assertFalse(tracker.accept(PascdMotion(101.0, 36), 4000))
        assertTrue(tracker.accept(PascdMotion(103.0, 36), 4000))
        assertTrue(PascdMotionTracker().accept(PascdMotion(1.0, 0), 5000))
        assertFalse(PascdMotionTracker.isFresh(1000, 2501))
        assertFalse(PascdMotionTracker.isFresh(1000, 999))
    }
}
