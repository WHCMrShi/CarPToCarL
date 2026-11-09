package io.github.shaonialife.carptocar.media

import org.junit.Assert.*
import org.junit.Test

class NavigationIconLayoutTest {
    @Test fun nativeSmallIconIsCenteredWithoutUpscaling() {
        assertEquals(NavigationIconLayout.Placement(112f, 96f, 32f, 64f), NavigationIconLayout.fit(32, 64))
        assertEquals(NavigationIconLayout.Placement(0f, 0f, 256f, 256f), NavigationIconLayout.fit(256, 256))
    }
    @Test fun oversizedIconsShrinkWithoutStretchingOrCropping() {
        assertEquals(NavigationIconLayout.Placement(0f, 64f, 256f, 128f), NavigationIconLayout.fit(1024, 512))
        assertEquals(NavigationIconLayout.Placement(96f, 0f, 64f, 256f), NavigationIconLayout.fit(512, 2048))
    }
}
