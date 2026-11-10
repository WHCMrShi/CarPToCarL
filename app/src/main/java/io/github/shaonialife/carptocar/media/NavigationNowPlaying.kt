package io.github.shaonialife.carptocar.media

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying

/** Output selector only: never publishes a synthetic Android MediaSession or changes audio playback. */
internal object NavigationNowPlaying {
    private val state = NavigationState()
    private val artwork = ArtworkRepository()
    private val handler = Handler(Looper.getMainLooper())
    private var source: Any? = null
    private var settings: SharedPreferences? = null
    private var selected = false
    @Volatile var hasSelectedNavigation = false
        private set
    @Volatile var changed: () -> Unit = {}
    private var lastPhase: String? = null
    private var lastActive: Boolean? = null
    @Volatile var log: (String) -> Unit = {}
    private val publish = Runnable { changed() }
    private val tick = object : Runnable {
        override fun run() {
            val repeat = synchronized(this@NavigationNowPlaying) { selected && source != null }
            if (!repeat) return
            changed()
            synchronized(this@NavigationNowPlaying) {
                if (selected && source != null) handler.postDelayed(this, 1_000)
            }
        }
    }
    @Synchronized fun ensure(ctx: Context) {
        if (settings != null) return
        settings = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        selected = settings!!.getBoolean("instrument_navigation", false)
        hasSelectedNavigation = selected
    }
    @Synchronized fun isNavigation(): Boolean = selected
    fun select(value: Boolean) {
        synchronized(this) {
            selected = value; hasSelectedNavigation = hasSelectedNavigation || value
            settings?.edit()?.putBoolean("instrument_navigation", value)?.apply()
            if (!value) artwork.clear()
            reschedule()
        }
        log("NAV-MEDIA: output=${if (value) "navigation" else "music"}; audio unaffected")
        changed()
    }
    @Synchronized private fun reschedule() {
        handler.removeCallbacks(tick)
        if (selected && source != null) handler.postDelayed(tick, 1_000)
    }
    @Synchronized fun begin(owner: Any) {
        source = owner; state.begin(owner); artwork.clear(); lastPhase = null; lastActive = null; reschedule()
        publishLatest()
    }
    @Synchronized fun end(owner: Any) { if (source === owner) clear() }
    @Synchronized fun clear() {
        source = null; state.clear(); artwork.clear(); handler.removeCallbacks(tick); lastPhase = null; lastActive = null
        publishLatest()
    }
    private fun publishLatest() {
        // One latest-state publication, outside HU/selector locks; no stale snapshot is captured.
        handler.removeCallbacks(publish)
        if (selected) handler.post(publish)
    }
    @Synchronized fun receive(owner: Any, event: HudTurn) {
        if (source === owner) state.receive(owner, event, SystemClock.elapsedRealtime())
    }
    @Synchronized fun value(music: CarPlayNowPlaying?): CarPlayNowPlaying? {
        if (!selected) return null
        val view = state.display(SystemClock.elapsedRealtime())
        if (lastPhase != view.phase || lastActive != view.navigationActive) {
            lastPhase = view.phase; lastActive = view.navigationActive
            log("NAV-MEDIA: phase=${view.phase} navigationActive=${view.navigationActive} endedDurationMs=${view.endedDurationMs}; album/progress=music artwork=navigation")
        }
        val bytes = view.icon
        val art = artwork.source("navigation-icon-${view.iconVersion}", view.iconVersion) { NavigationIcon.render(bytes, log) }
        return view.nowPlaying(art, music)
    }
}
