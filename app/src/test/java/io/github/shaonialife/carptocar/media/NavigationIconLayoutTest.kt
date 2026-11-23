package io.github.shaonialife.carptocar.media

import org.junit.Assert.*
import org.junit.Test

class NavigationIconLayoutTest {
    @Test fun nativeSmallIconIsCenteredWithoutUpscaling() {
        assertEquals(128, NavigationIconLayout.canvasSize(48, 48))
        assertEquals(NavigationIconLayout.Placement(40f, 40f, 48f, 48f), NavigationIconLayout.fit(48, 48))
        assertEquals(128, NavigationIconLayout.canvasSize(63, 32))
        assertEquals(256, NavigationIconLayout.canvasSize(64, 32))
        assertEquals(256, NavigationIconLayout.canvasSize(32, 64))
        assertEquals(NavigationIconLayout.Placement(112f, 96f, 32f, 64f), NavigationIconLayout.fit(32, 64))
        assertEquals(NavigationIconLayout.Placement(0f, 0f, 256f, 256f), NavigationIconLayout.fit(256, 256))
    }
    @Test fun oversizedIconsShrinkWithoutStretchingOrCropping() {
        assertEquals(NavigationIconLayout.Placement(0f, 64f, 256f, 128f), NavigationIconLayout.fit(1024, 512))
        assertEquals(NavigationIconLayout.Placement(96f, 0f, 64f, 256f), NavigationIconLayout.fit(512, 2048))
    }
}
