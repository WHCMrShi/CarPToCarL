package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineWindowTest {
    @Test fun aggregatesOnlyWhenDueAndResetsWithoutCallingRuntimePerPacket() {
        var now = 0L
        var probes = 0
        val stats =
            PipelineWindow("test", 100, { now }, {
                probes++
                "runtime"
            })
        assertNull(stats.record(10, 12, 1000, 2000))
        assertNull(stats.record(20, 22, 3000, 4000))
        assertEquals(0, probes)
        now = 100
        val first = stats.record(30, 32, 2000, 3000)!!
        assertTrue(first.contains("n=3 bytes=60 ingressArrayBytes=66"))
        assertTrue(first.contains("workUs(avg/p95<=/max)=2/3/3"))
        assertEquals(1, probes)
        now = 200
        val second = stats.record(5, 0, 1000)!!
        assertTrue(second.contains("n=1 bytes=5 ingressArrayBytes=0"))
        assertTrue(second.contains("queueUs(p95<=/max)=0/0"))
        assertEquals(2, probes)
    }

    @Test fun percentileUsesBucketUpperBoundAndIncludesLongStalls() {
        var now = 0L
        val stats = PipelineWindow("test", 100, { now }, { "" })
        repeat(94) { stats.record(1, 0, 20_000) }
        repeat(5) { stats.record(1, 0, 2_000_000) }
        now = 100
        val result = stats.record(1, 0, 900_000_000)!!
        assertTrue(result.contains("/5000/900000"))
    }
}
