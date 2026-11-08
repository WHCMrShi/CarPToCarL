package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2FileTransferFormatTest {
    @Test fun v1HasOnlyIdCommandAndUnsigned64BitSize() {
        assertArrayEquals(byteArrayOf(0x81.toByte(), 4, 0, 0, 0, 0, 0, 0, 0x13, 0x88.toByte()),
            Iap2FileTransferFormat.setup(129, 5000, 1))
        assertEquals(10, Iap2FileTransferFormat.setup(128, 0, 1).size)
    }

    @Test fun v2AddsArtworkTypeAndUnknownVersionsRemainDisabled() {
        assertArrayEquals(byteArrayOf(0x81.toByte(), 4, 0, 0, 0, 0, 0, 0, 0x13, 0x88.toByte(), 0, 2),
            Iap2FileTransferFormat.setup(129, 5000, 2))
        assertNull(Iap2FileTransferFormat.setupSize(0))
        assertNull(Iap2FileTransferFormat.setupSize(3))
    }
}
