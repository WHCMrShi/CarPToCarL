package io.github.shaonialife.carptocar.input

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class TouchVisitTest {
    @Test fun primitiveVisitorMatchesLegacyParserIncludingMalformedAndFallbackReports() {
        val random = Random(42)
        for (format in listOf(null, TouchReports.Format.SINGLE_ABSOLUTE, TouchReports.Format.MULTI_SLOT)) {
            for (size in 0..20) {
                repeat(10) {
                    val report = ByteArray(size).also(random::nextBytes)
                    val expected = TouchReports.parse(report, format)
                    val actual = mutableListOf<TouchReports.Contact>()
                    TouchReports.visit(
                        report,
                        format,
                        TouchReports.ContactSink { slot, x, y, down ->
                            actual.add(TouchReports.Contact(slot, x, y, down))
                        },
                    )
                    assertEquals(expected != null, TouchReports.canParse(report, format))
                    assertEquals(expected.orEmpty(), actual)
                    assertEquals(
                        expected.orEmpty().any { it.slot == 0 && !it.down },
                        TouchReports.hasPrimaryRelease(report, format),
                    )
                }
            }
        }
    }
}
