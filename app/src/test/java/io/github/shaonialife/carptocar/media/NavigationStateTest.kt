package io.github.shaonialife.carptocar.media

import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import org.junit.Assert.*
import org.junit.Test

class NavigationStateTest {
    private val owner = Any()
    private fun event(action: Int = 2, turn: Int = 7, road: String = "道路", distance: Int = 100, icon: ByteArray? = byteArrayOf(4)) = HudTurn(action, turn, road, distance, icon)
    private fun started() = NavigationState().apply { begin(owner); receive(owner, event(3, 24, "终点", 1), 0) }
    private val song = CarPlayNowPlaying("歌曲", "音乐歌手", "音乐专辑", 180000, Iap2NowPlaying.STATUS_PLAYING, 12000, appName = "player")

    @Test fun allPhasesUseMusicFieldsAndOnlyNavigationArtwork() {
        val state = started()
        for (time in listOf(0L, 300L, 1000L)) {
            if (time == 300L) { state.receive(owner, event(1), 10) }
            if (time == 1000L) state.receive(owner, event(3, 24, distance = 0), time)
            val view = state.display(time)
            val mixed = view.nowPlaying(music = song)
            assertEquals("歌曲", mixed.album)
            assertEquals(180000, mixed.durationMs)
            assertEquals(12000, mixed.elapsedMs)
            assertEquals(Iap2NowPlaying.STATUS_PLAYING, mixed.status)
            assertNull(mixed.artworkSource)
            assertNull(mixed.artworkTransferId)
            if (view.phase == "guide") assertEquals("道路", mixed.artist)
        }
    }

    @Test fun endedDurationIsFrozenAndDuplicateEndDoesNotExtendTenSecondHold() {
        val state = started()
        state.receive(owner, event(1), 10); state.display(300)
        state.receive(owner, event(3, 24, distance = 0), 1000)
        state.receive(owner, event(3, 24, distance = 0), 9000)
        assertEquals("驾驶时长：00:01", state.display(10999).artist)
        assertNull(state.display(10999).icon)
        assertEquals("等待导航", state.display(11000).title)
    }

    @Test fun newGuideDuringEndHoldCannotBeDestroyedByOldDeadlineOrResurrectAfterEnd() {
        val state = started()
        state.receive(owner, event(3, 24, distance = 0), 100)
        state.receive(owner, event(1), 200)
        state.receive(owner, event(3, 24, distance = 0), 300)
        assertEquals("导航结束", state.display(600).title)
        assertEquals("等待导航", state.display(10300).title)
        state.receive(owner, event(2), 10400)
        assertEquals("100米 左转", state.display(11000).title)
    }

    @Test fun missingStartAndStaleHuNeverReusePreviousDurationOrGuide() {
        val state = started()
        val next = Any()
        state.begin(next)
        state.receive(owner, event(1), 1)
        assertEquals("等待导航", state.display(1000).title)
        state.receive(next, event(3, 24, distance = 0), 1001)
        assertEquals("驾驶时长：--:--", state.display(1002).artist)
        state.clear()
        state.receive(next, event(1), 2000)
        assertEquals("等待导航", state.display(3000).title)
    }

    @Test fun firstUpdateStartsTimingAndLateStatusHintDoesNotResetIt() {
        val state = NavigationState().apply { begin(owner) }
        state.receive(owner, event(2), 100)
        state.receive(owner, event(3, 24, "目的地", 1), 20000)
        state.receive(owner, event(3, 24, distance = 0), 65100)
        assertEquals("驾驶时长：01:05", state.display(65101).artist)
    }

    @Test fun coalescingAndMissingIconPolicyArePreservedWithoutDistanceProgress() {
        val state = started()
        state.receive(owner, event(1, distance = 1000), 0)
        state.receive(owner, event(distance = 500), 100)
        assertEquals("500米 左转", state.display(300).title)
        val version = state.display(300).iconVersion
        state.receive(owner, event(distance = 400, icon = null), 1000)
        assertEquals(version, state.display(1300).iconVersion)
        state.receive(owner, event(1, distance = 300, icon = null), 2000)
        assertNull(state.display(2300).icon)
        assertEquals(0, state.display(2300).nowPlaying().elapsedMs)
    }

    @Test fun duplicateStatusHintDoesNotReplaceRoadOrIcon() {
        val state = started()
        state.receive(owner, event(1), 10)
        val before = state.display(300)
        state.receive(owner, event(3, 24, "其他目的地", 1, byteArrayOf(9)), 400)
        assertEquals(before, state.display(500))
    }

    @Test fun durationFormattingAndBlankRoadAreSafe() {
        assertEquals("59:59", NavigationState.formatDrivingDuration(3599999))
        assertEquals("1:00:00", NavigationState.formatDrivingDuration(3600000))
        assertEquals("1千米", NavigationState.formatDistance(1000))
        val state = started()
        state.receive(owner, event(1, road = " \t"), 0)
        assertEquals("   ", state.display(300).artist)
    }
    @Test fun unmappedShowAndUpdateUseNeutralLabelWithoutChangingIconOrProgress() {
        for (code in listOf(0, 25, 26, 27, 28) + (45..68) + listOf(-1, 77, 999)) {
            val state = NavigationState().apply { begin(owner) }
            state.receive(owner, event(1, code, "隧道道路", 1000, byteArrayOf(1)), 0)
            assertEquals("1千米 途径", state.display(300).nowPlaying().title)
            val updatedIcon = byteArrayOf(2, 3)
            state.receive(owner, event(2, code, "下一道路", 500, updatedIcon), 1000)
            val view = state.display(1300)
            val media = view.nowPlaying()
            assertEquals("500米 途径", media.title)
            assertEquals("暂无音乐", media.album)
            assertEquals("下一道路", media.artist)
            assertEquals(0, media.durationMs)
            assertEquals(0, media.elapsedMs)
            assertArrayEquals(updatedIcon, view.icon)
        }
    }

    @Test fun unmappedTitleUsesNeutralLabelEvenWhenRoadIsUnnamedOrBlank() {
        for (road in listOf("无名道路", "", "   ", "  原始道路  ")) {
            val state = NavigationState().apply { begin(owner) }
            state.receive(owner, event(2, 25, road, 26), 0)
            assertEquals("26米 途径", state.display(300).nowPlaying().title)
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
            assertEquals("code=$code", "1千米 $text", state.display(300).nowPlaying().title)
            val icon = byteArrayOf(8, 9)
            state.receive(owner, event(2, code, "下一道路", 500, icon), 1000)
            val view = state.display(1300)
            val media = view.nowPlaying()
            assertEquals("code=$code", "500米 $text", media.title)
            assertEquals("暂无音乐", media.album)
            assertEquals("下一道路", media.artist)
            assertEquals(0, media.durationMs)
            assertEquals(0, media.elapsedMs)
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
