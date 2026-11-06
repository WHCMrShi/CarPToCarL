package io.github.shaonialife.carptocar.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HidReportProfilesTest {
    @Test fun cachesBothTouchAndNonTouchDevices() {
        val cache = HidReportProfiles()
        val touch = byteArrayOf(5, 13, 117, 16, 9, 34)
        val first = cache.get("touch", touch)
        assertEquals(TouchReports.Format.SINGLE_ABSOLUTE, first.touch)
        assertNull(first.keys)
        assertSame(first, cache.get("touch", touch))
        val other = byteArrayOf(5, 1)
        assertSame(cache.get("other", other), cache.get("other", other))
    }

    @Test fun replacingOrRemovingDescriptorInvalidatesCachedResult() {
        val cache = HidReportProfiles()
        val missing = cache.get("touch", null)
        val single = cache.get("touch", byteArrayOf(5, 13, 117, 16, 9, 34))
        assertNotSame(missing, single)
        val multi = cache.get("touch", byteArrayOf(5, 13, 117, 16, 9, 34, 9, 34))
        assertEquals(TouchReports.Format.MULTI_SLOT, multi.touch)
        assertNull(cache.get("touch", null).touch)
        assertNull(HidReportProfiles().get("touch", null).touch)
    }
}
