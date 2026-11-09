package io.github.shaonialife.carptocar.media

import org.junit.Assert.*
import org.junit.Test

class NavigationStateTest {
    private val owner = Any()
    private val destination = byteArrayOf(1, 2, 3)
    private fun event(action: Int = 2, turn: Int = 7, road: String = "道路", distance: Int = 100, icon: ByteArray? = byteArrayOf(4)) = HudTurn(action, turn, road, distance, icon)
    private fun started() = NavigationState().apply { begin(owner); receive(owner, event(3, 24, "终点", 1, destination), 0) }

    @Test fun duplicateDestinationDoesNotReplaceCurrentGuidanceOrDestination() {
        val state = started()
        assertEquals("导航目的地", state.display(0).title)
        state.receive(owner, event(1), 10)
        val guide = state.display(300)
        state.receive(owner, event(3, 24, "另一终点", 1, byteArrayOf(9)), 400)
        assertEquals(guide.title, state.display(500).title)
        assertEquals("🧭 终点", state.display(500).artist)
        assertArrayEquals(guide.icon, state.display(500).icon)
    }

    @Test fun endRetainsDestinationIconOnceAndExpiresAfterFiveSeconds() {
        val state = started()
        state.receive(owner, event(1), 10); state.display(300)
        state.receive(owner, event(3, 24, "终点", 0, byteArrayOf()), 1_000)
        assertArrayEquals(destination, state.display(1_001).icon)
        state.receive(owner, event(3, 24, "重复", 0), 4_000)
        assertEquals("导航结束", state.display(5_999).title)
        assertEquals("暂无导航", state.display(6_000).title)
        assertEquals("", state.display(6_000).artist)
    }

    @Test fun newRouteCancelsOldEndDeadline() {
        val state = started()
        state.receive(owner, event(3, 24, "终点", 0), 100)
        state.receive(owner, event(3, 24, "新终点", 1), 200)
        assertEquals("🧭 新终点", state.display(6_000).artist)
        state.receive(owner, event(3, 24, "新终点", 0), 7_000)
        state.receive(owner, event(1), 11_900)
        assertEquals("100米 左转", state.display(12_200).title)
    }

    @Test fun percentageIsDistanceDrivenAndDoesNotAdvanceWhenStationary() {
        val state = started()
        state.receive(owner, event(1, distance = 1000), 10); state.display(300)
        state.receive(owner, event(distance = 340), 1_000)
        assertEquals(66_000, state.display(1_300).elapsed)
        assertEquals(100_000, state.display(1_300).duration)
        assertEquals(66_000, state.display(20_000).elapsed)
    }

    @Test fun phasedUpdatesCoalesceAndMissingShowCanStartANewSegment() {
        val state = started()
        state.receive(owner, event(1, distance = 50), 0); state.display(300)
        state.receive(owner, event(distance = 0), 1000); state.display(1300)
        state.receive(owner, event(distance = 5500), 2_000)
        state.receive(owner, event(road = "新路", distance = 5500), 2_020)
        state.receive(owner, event(turn = 1, road = "新路", distance = 5500), 2_030)
        assertEquals(100_000, state.display(2_300).duration)
        assertEquals(0, state.display(2_300).elapsed)
        assertEquals("5.5公里 直行", state.display(2_300).title)
    }

    @Test fun roadCorrectionAndDuplicateShowDoNotResetDistanceBaseline() {
        val state = started()
        state.receive(owner, event(1, distance = 1000), 0); state.display(300)
        state.receive(owner, event(1, distance = 1000), 500); state.display(800)
        state.receive(owner, event(distance = 800), 1000); assertEquals(20_000, state.display(1300).elapsed)
        state.receive(owner, event(road = "补全名称", distance = 700), 2000)
        assertEquals(30_000, state.display(2300).elapsed)
    }

    @Test fun aNewShowOnTheSameRoadAndTurnStartsANewBaseline() {
        val state = started()
        state.receive(owner, event(1, distance = 1000), 0); state.display(300)
        state.receive(owner, event(distance = 600), 1000); state.display(1300)
        state.receive(owner, event(1, distance = 400), 2000)
        assertEquals(0, state.display(2300).elapsed)
    }

    @Test fun coalescingPreservesTheShowDistanceWhileDisplayingTheLatestRemainder() {
        val state = started()
        state.receive(owner, event(1, distance = 1000), 0)
        state.receive(owner, event(distance = 500), 100)
        assertEquals(50_000, state.display(300).elapsed)
    }

    @Test fun staleSourceCannotChangeNewSessionAndMissingDestinationStaysEmpty() {
        val state = started(); val next = Any()
        state.begin(next)
        state.receive(owner, event(3, 24, "旧终点", 1), 100)
        state.receive(next, event(1, distance = 0), 200)
        val view = state.display(500)
        assertEquals("", view.artist); assertEquals(0, view.elapsed); assertEquals(0, view.duration)
        state.clear(); state.receive(next, event(), 1000)
        assertEquals("暂无导航", state.display(2000).title)
    }

    @Test fun formatAndUnknownTurnAreSafe() {
        assertEquals("999米", NavigationState.formatDistance(999))
        assertEquals("1.0公里", NavigationState.formatDistance(1000))
        assertEquals("16.7公里", NavigationState.formatDistance(16700))
        assertEquals("进入隧道", NavigationState.turnName(25))
        assertEquals("右分叉直行", NavigationState.turnName(16))
        assertEquals("导航指引", NavigationState.turnName(999))
    }

    @Test fun protocolSnapshotIsPausedWithExplicitEmptyArtistAndFixedPercentageTimebase() {
        val state = NavigationState().apply { begin(owner) }
        assertEquals("", state.display(0).nowPlaying().artist)
        state.receive(owner, event(1, distance = 1000), 0); state.display(300)
        state.receive(owner, event(distance = 500), 1000)
        val value = state.display(1300).nowPlaying()
        assertEquals(io.github.shaonialife.carptocar.iap2.Iap2NowPlaying.STATUS_PAUSED, value.status)
        assertEquals(100_000, value.durationMs); assertEquals(50_000, value.elapsedMs)
        assertEquals("500米 左转", value.title)
        assertEquals(value, state.display(10_000).nowPlaying())
    }
}
