package io.github.shaonialife.carptocar.media

import org.junit.Assert.*
import org.junit.Test

class NavigationStateTest {
    private val owner = Any()
    private val destination = byteArrayOf(1, 2, 3)
    private fun event(action: Int = 2, turn: Int = 7, road: String = "道路", distance: Int = 100, icon: ByteArray? = byteArrayOf(4)) = HudTurn(action, turn, road, distance, icon)
    private fun started() = NavigationState().apply { begin(owner); receive(owner, event(3, 24, "终点", 1, destination), 0) }

    @Test fun statusHintHasNoPrepareLayoutAndCannotReplaceCurrentGuidance() {
        val state = started()
        assertEquals("等待导航", state.display(0).title)
        assertTrue(state.display(0).navigationActive)
        assertNull(state.display(0).icon)
        state.receive(owner, event(1), 10)
        val guide = state.display(300)
        state.receive(owner, event(3, 24, "另一终点", 1, byteArrayOf(9)), 400)
        assertEquals(guide.title, state.display(500).title)
        assertEquals("   ", state.display(500).artist)
        assertArrayEquals(guide.icon, state.display(500).icon)
    }

    @Test fun endClearsIconAndFreezesDurationOnceThenExpiresAfterFiveSeconds() {
        val state = started()
        state.receive(owner, event(1), 10); state.display(300)
        state.receive(owner, event(3, 24, "终点", 0, byteArrayOf()), 1_000)
        assertNull(state.display(1_001).icon)
        assertEquals("驾驶时长：00:01", state.display(1_001).artist)
        state.receive(owner, event(3, 24, "重复", 0), 4_000)
        assertEquals("导航结束", state.display(5_999).title)
        assertEquals("驾驶时长：00:01", state.display(5_999).artist)
        assertEquals("等待导航", state.display(6_000).title)
        assertEquals("   ", state.display(6_000).artist)
    }

    @Test fun newRouteCancelsOldEndDeadline() {
        val state = started()
        state.receive(owner, event(3, 24, "终点", 0), 100)
        state.receive(owner, event(3, 24, "新终点", 1), 200)
        assertEquals("等待导航", state.display(6_000).title)
        assertTrue(state.display(6_000).navigationActive)
        assertEquals("   ", state.display(6_000).artist)
        state.receive(owner, event(3, 24, "新终点", 0), 7_000)
        state.receive(owner, event(1), 11_900)
        assertEquals("100米 左转", state.display(12_200).title)
    }

    @Test fun endingANewGuideBeforeItsDisplayFlushCannotResurrectIt() {
        val state = started()
        state.receive(owner, event(3, 24, "终点", 0), 100)
        state.receive(owner, event(1), 200)
        state.receive(owner, event(3, 24, "终点", 0), 300)
        assertEquals("导航结束", state.display(600).title)
        state.receive(owner, event(3, 24, "重复结束", 0), 1000)
        assertEquals("驾驶时长：00:00", state.display(600).artist)
        assertEquals("等待导航", state.display(5300).title)
        assertEquals("等待导航", state.display(10_000).title)
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

    @Test fun staleSourceCannotChangeNewSessionAndBlankFieldsUseThreeSpaces() {
        val state = started(); val next = Any()
        state.begin(next)
        state.receive(owner, event(3, 24, "旧终点", 1), 100)
        state.receive(next, event(1, distance = 0), 200)
        val view = state.display(500)
        assertEquals("   ", view.artist); assertEquals(0, view.elapsed); assertEquals(0, view.duration)
        state.clear(); state.receive(next, event(), 1000)
        assertEquals("等待导航", state.display(2000).title)
    }

    @Test fun formatAndUnknownTurnAreSafe() {
        assertEquals("999米", NavigationState.formatDistance(999))
        assertEquals("1.0公里", NavigationState.formatDistance(1000))
        assertEquals("16.7公里", NavigationState.formatDistance(16700))
        assertNull(NavigationState.turnName(25))
        assertEquals("右分叉直行", NavigationState.turnName(16))
        assertNull(NavigationState.turnName(999))
    }

    @Test fun protocolSnapshotKeepsRoadInAlbumAndBlankArtistWithPausedPercentageTimebase() {
        val state = NavigationState().apply { begin(owner) }
        assertEquals("   ", state.display(0).nowPlaying().artist)
        assertEquals("   ", state.display(0).nowPlaying().album)
        state.receive(owner, event(1, distance = 1000), 0); state.display(300)
        state.receive(owner, event(distance = 500), 1000)
        val value = state.display(1300).nowPlaying()
        assertEquals(io.github.shaonialife.carptocar.iap2.Iap2NowPlaying.STATUS_PAUSED, value.status)
        assertEquals(100_000, value.durationMs); assertEquals(50_000, value.elapsedMs)
        assertEquals("500米 左转", value.title)
        assertEquals("道路", value.album)
        assertEquals("   ", value.artist)
        assertEquals(value, state.display(10_000).nowPlaying())
    }

    @Test fun firstGuideOrUpdateStartsTimingWithoutAStatusHint() {
        for (action in 1..2) {
            val state = NavigationState().apply { begin(owner) }
            state.receive(owner, event(action), 100)
            state.display(400)
            state.receive(owner, event(3, 24, "目的地", 1, destination), 20_000)
            state.receive(owner, event(3, 24, "目的地", 0, destination), 65_100)
            val end = state.display(65_101)
            assertEquals("驾驶时长：01:05", end.artist)
            assertFalse(end.navigationActive)
            assertNull(end.icon)
            assertEquals("   ", end.album)
        }
    }

    @Test fun endWithoutStartHasUnknownDurationAndHuReplacementClearsTiming() {
        val state = NavigationState().apply { begin(owner) }
        state.receive(owner, event(3, 24, distance = 0), 100)
        assertEquals("驾驶时长：--:--", state.display(101).artist)
        state.receive(owner, event(1), 200)
        val next = Any()
        state.begin(next)
        state.receive(owner, event(3, 24, distance = 0), 999_999)
        assertEquals("等待导航", state.display(1_000_000).title)
        state.receive(next, event(3, 24, distance = 0), 1_000_001)
        assertEquals("驾驶时长：--:--", state.display(1_000_002).artist)
    }

    @Test fun restartedGuideDropsOldProgressImageAndDuplicateShowHistory() {
        val state = started()
        state.receive(owner, event(1, distance = 1000), 10); state.display(300)
        state.receive(owner, event(distance = 100), 400); state.display(700)
        state.receive(owner, event(3, 24, distance = 0), 710)
        state.receive(owner, event(1, distance = 1000, icon = null), 720)
        val view = state.display(1000)
        assertEquals(0, view.elapsed)
        assertNull(view.icon)
        state.receive(owner, event(3, 24, distance = 0), 1720)
        assertEquals("驾驶时长：00:01", state.display(1721).artist)
    }

    @Test fun blankRoadUsesSpacePlaceholderAndDurationHandlesHourBoundary() {
        val state = started()
        state.receive(owner, event(1, road = " \t"), 10)
        assertEquals("   ", state.display(300).album)
        assertEquals("00:00", NavigationState.formatDrivingDuration(0))
        assertEquals("59:59", NavigationState.formatDrivingDuration(3_599_999))
        assertEquals("1:00:00", NavigationState.formatDrivingDuration(3_600_000))
        assertEquals("25:01:02", NavigationState.formatDrivingDuration(90_062_000))
    }

    @Test fun wireEncodingPreservesAllThreeSpacesInsteadOfTrimmingThem() {
        val state = NavigationState().apply { begin(owner) }
        val body = io.github.shaonialife.carptocar.iap2.Iap2NowPlaying.body(state.display(0).nowPlaying())
        val media = io.github.shaonialife.carptocar.iap2.Iap2Csm.parseParams(body).single { it.id == 0 }
        val fields = io.github.shaonialife.carptocar.iap2.Iap2Csm.parseParams(media.value)
        for (id in listOf(6, 12)) assertArrayEquals(byteArrayOf(32, 32, 32, 0), fields.single { it.id == id }.value)
    }
    @Test fun unmappedShowAndUpdateUseRoadWithoutChangingIconOrProgress() {
        for (code in listOf(0, 25, 26, 27, 28) + (45..68) + listOf(-1, 77, 999)) {
            val state = NavigationState().apply { begin(owner) }
            state.receive(owner, event(1, code, "隧道道路", 1000, byteArrayOf(1)), 0)
            assertEquals("1.0公里 隧道道路", state.display(300).nowPlaying().title)
            val updatedIcon = byteArrayOf(2, 3)
            state.receive(owner, event(2, code, "下一道路", 500, updatedIcon), 1000)
            val view = state.display(1300)
            val media = view.nowPlaying()
            assertEquals("500米 下一道路", media.title)
            assertEquals("下一道路", media.album)
            assertEquals(NavigationState.BLANK, media.artist)
            assertEquals(100_000, media.durationMs)
            assertEquals(50_000, media.elapsedMs)
            assertArrayEquals(updatedIcon, view.icon)
        }
    }

    @Test fun unmappedTitlePreservesUnnamedAndBlankRoadExactlyAsReceived() {
        for (road in listOf("无名道路", "", "   ", "  原始道路  ")) {
            val state = NavigationState().apply { begin(owner) }
            state.receive(owner, event(2, 25, road, 26), 0)
            assertEquals("26米 $road", state.display(300).nowPlaying().title)
        }
    }

    @Test fun extendedCarPlusActionsReachMediaTitleOnShowAndUpdate() {
        val actions = mapOf(
            32 to "靠左直行进入辅路", 33 to "靠右直行进入辅路",
            34 to "靠左直行", 35 to "靠右直行",
            36 to "左分叉直行", 37 to "中间分叉直行", 38 to "右分叉直行",
            39 to "走左侧分叉进入辅路", 40 to "走中间分叉进入辅路", 41 to "走右侧分叉进入辅路",
            42 to "左分叉直行进入辅路", 43 to "中间分叉直行进入辅路", 44 to "右分叉直行进入辅路",
            69 to "前方驶出环岛", 70 to "左侧驶出环岛", 71 to "左后方驶出环岛", 72 to "左前方驶出环岛",
            73 to "右侧驶出环岛", 74 to "右后方驶出环岛", 75 to "右前方驶出环岛", 76 to "环岛内掉头",
        )
        for ((code, text) in actions) {
            val state = NavigationState().apply { begin(owner) }
            state.receive(owner, event(1, code, "原始道路", 1000), 0)
            assertEquals("code=$code", "1.0公里 $text", state.display(300).nowPlaying().title)
            val icon = byteArrayOf(8, 9)
            state.receive(owner, event(2, code, "下一道路", 500, icon), 1000)
            val view = state.display(1300)
            val media = view.nowPlaying()
            assertEquals("code=$code", "500米 $text", media.title)
            assertEquals("下一道路", media.album)
            assertEquals(NavigationState.BLANK, media.artist)
            assertEquals(100_000, media.durationMs)
            assertEquals(50_000, media.elapsedMs)
            assertArrayEquals(icon, view.icon)
        }
    }

    @Test fun originalTurnActionsRemainUnchanged() {
        val original = listOf(
            "直行", "右前方转弯", "右转", "右后方转弯", "掉头",
            "左后方转弯", "左转", "左前方转弯", "进入环岛", "出环岛",
            "靠左行驶", "靠右行驶", "靠左进入主路", "左分叉直行", "靠右进入主路",
            "右分叉直行", "分叉路走中间", "靠左驶入辅路", "靠右驶入辅路",
            "走左侧分叉", "走右侧分叉", "走中间分叉", "出发点", "到达目的地",
        )
        for ((index, text) in original.withIndex()) assertEquals(text, NavigationState.turnName(index + 1))
        assertEquals("进入轮渡", NavigationState.turnName(29))
        assertEquals("离开轮渡", NavigationState.turnName(30))
        assertEquals("经过收费站", NavigationState.turnName(31))
    }

}
