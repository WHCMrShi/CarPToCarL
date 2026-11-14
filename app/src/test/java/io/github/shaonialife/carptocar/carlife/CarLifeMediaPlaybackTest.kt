package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeMediaPlaybackTest {
    @Test fun `stale playing snapshot cannot undo explicit car pause`() {
        val state = CarLifeMediaPlayback()
        state.onSessionPlayback(true)
        state.onCarPause()
        state.onSessionPlayback(true)
        state.onActivity(CarLifeMediaActivity.PCM)
        assertFalse(state.playing)
        assertFalse(state.canForwardPcm)
        state.onSessionPlayback(false)
        state.onExplicitPlay()
        assertTrue(state.playing)
        assertTrue(state.canForwardPcm)
    }
    @Test
    fun `setup init stop init without focus and pcm does not authorize playback`() {
        val playback = CarLifeMediaPlayback()

        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.STOP)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        assertFalse(playback.playing)

        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        assertFalse(playback.playing)

        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `car pause blocks residual pcm and car resume restores playback without needing 0x30004`() {
        val playback = CarLifeMediaPlayback()
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)

        playback.onCarPause()
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)

        // 暂停期间引擎残留 PCM 不得重新点燃播放。
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)

        // 车机解除静音：即便第三方播放器只继续推裸 PCM 而不发 0x30004，也能立即恢复。
        playback.onCarResume()
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `explicit media session pause vetoes car resume`() {
        val playback = CarLifeMediaPlayback()
        playback.onSessionPlayback(false)
        playback.onCarResume()
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)
    }

    @Test
    fun `carlife hardkey and audio packet constants match verified md wire protocol`() {
        assertEquals(0x0F, CarLifeProtocol.HardKey.SEEK_SUB)
        assertEquals(0x10, CarLifeProtocol.HardKey.SEEK_ADD)
        assertEquals(0x1F, CarLifeProtocol.HardKey.MEDIA_START)
        assertEquals(0x20, CarLifeProtocol.HardKey.MEDIA_STOP)
        assertEquals(98318, CarLifeProtocol.HU_PAUSE_MEDIA)
        assertEquals(0x30002, CarLifeProtocol.MUSIC_PKG_STOP)
        assertEquals(0x30003, CarLifeProtocol.MUSIC_PKG_PAUSE)
        assertEquals(0x30004, CarLifeProtocol.MUSIC_PKG_RESUME)
        assertEquals(0x40002, CarLifeProtocol.TTS_PKG_STOP)
        assertEquals(0x40003, CarLifeProtocol.TTS_PKG_NORMAL)
    }

    @Test
    fun `focus released before trailing 0x30003 pause does not deadlock next focus gained play`() {
        val playback = CarLifeMediaPlayback()
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)

        // 控制通道（7240）的音乐焦点=0 早于媒体通道（9240）的 0x30003 PAUSE 到达：
        // 尾随 PAUSE 不得抹掉下一轮焦点的起播资格。
        playback.onActivity(CarLifeMediaActivity.FOCUS_RELEASED)
        playback.onActivity(CarLifeMediaActivity.PAUSE)
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)

        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.STOP)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `pause and focus release followed by focus gain and pcm resumes even without new 0x30001 init`() {
        val playback = CarLifeMediaPlayback()
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)

        playback.onActivity(CarLifeMediaActivity.PAUSE)
        playback.onActivity(CarLifeMediaActivity.FOCUS_RELEASED)
        assertFalse(playback.playing)

        // 同一 9240 连接在采样率未变时可能直接恢复推裸 PCM，不重发 0x30001 INIT。
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `car pause does not reclaim native radio on format rebuild while focus never released`() {
        val playback = CarLifeMediaPlayback()
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)

        playback.onCarPause()
        playback.onActivity(CarLifeMediaActivity.STOP)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertFalse(playback.playing)

        // 只有焦点真正释放后再重新获得，才开启新一轮有效播放。
        playback.onActivity(CarLifeMediaActivity.FOCUS_RELEASED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)
    }

}
