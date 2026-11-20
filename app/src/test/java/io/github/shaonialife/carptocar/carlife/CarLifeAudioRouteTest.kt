package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.util.SessionEpoch
import org.junit.Assert.assertEquals
import org.junit.Test

class CarLifeAudioRouteTest {
    private val epoch = SessionEpoch()
    private val ordinary = Any()
    private val q4 = Any()
    private val route = CarLifeAudioRoute<Any, String, String>(epoch) { it === q4 }
    private val deliveries = mutableListOf<String>()

    private fun connect(owner: Any, main: String = "main", navigation: String? = null) {
        epoch.begin(owner)
        val lease = checkNotNull(epoch.capture(owner))
        route.publishMedia(lease, main)
        if (navigation != null) route.publishNavigation(lease, navigation)
    }

    private fun dispatch(
        owner: Any?,
        probe: Boolean = false,
        type: Int = 0x40003,
        sourceCurrent: () -> Boolean = { true },
    ) = route.dispatch(owner, probe, type, sourceCurrent, deliveries::add, { deliveries.add(it) })

    @Test fun carLifeFirstStartsDeliveringWithoutAnotherInit() {
        assertEquals(CarLifeAudioRoute.Result.MEDIA_UNAVAILABLE, dispatch(null))
        connect(ordinary)
        assertEquals(CarLifeAudioRoute.Result.ROUTED, dispatch(ordinary))
        assertEquals(listOf("main"), deliveries)
    }

    @Test fun carPlayFirstUsesMainFor9240AndOrdinary9241() {
        connect(ordinary, navigation = "unexpected nav")
        dispatch(ordinary)
        dispatch(ordinary, probe = true)
        assertEquals(listOf("main", "main"), deliveries)
    }

    @Test fun q4RoutesOnlyEligibleTtsTo101AndDoesNotFallBack() {
        connect(q4)
        assertEquals(CarLifeAudioRoute.Result.NAVIGATION_UNAVAILABLE, dispatch(q4, probe = true))
        assertEquals(CarLifeAudioRoute.Result.IGNORED, dispatch(q4, probe = true, type = 0x40002))
        val lease = checkNotNull(epoch.capture(q4))
        route.publishNavigation(lease, "101")
        dispatch(q4, probe = true)
        dispatch(q4)
        assertEquals(listOf("101", "main"), deliveries)
    }

    @Test fun ownerChangesAfterTargetSelectionAndOnlyTheNextPacketUsesNewOwner() {
        connect(ordinary, main = "old")
        var checks = 0
        dispatch(ordinary, sourceCurrent = {
            if (++checks == 2) connect(q4, main = "new")
            true
        })
        assertEquals(2, checks)
        assertEquals(emptyList<String>(), deliveries)
        dispatch(q4)
        assertEquals(listOf("new"), deliveries)
    }

    @Test fun navigationSelectionCannotFallThroughWhenNextOwnerUsesMainForTts() {
        connect(q4, navigation = "old 101")
        var checks = 0
        dispatch(q4, probe = true, sourceCurrent = {
            if (++checks == 2) connect(ordinary, main = "new main")
            true
        })
        assertEquals(2, checks)
        assertEquals(emptyList<String>(), deliveries)
        dispatch(ordinary, probe = true)
        assertEquals(listOf("new main"), deliveries)
    }

    @Test fun expiredSourceOrPortGenerationCannotUseTheSelectedTarget() {
        connect(q4, navigation = "101")
        var checks = 0
        dispatch(q4, probe = true, sourceCurrent = { ++checks == 1 })
        dispatch(q4, sourceCurrent = { false })
        assertEquals(2, checks)
        assertEquals(emptyList<String>(), deliveries)
        dispatch(q4, probe = true)
        assertEquals(listOf("101"), deliveries)
    }
}
