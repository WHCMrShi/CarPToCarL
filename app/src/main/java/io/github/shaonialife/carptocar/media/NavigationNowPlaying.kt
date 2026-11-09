package io.github.shaonialife.carptocar.media

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import java.io.ByteArrayOutputStream

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
        source = owner; state.begin(owner); artwork.clear(); lastPhase = null; reschedule()
        publishLatest()
    }
    @Synchronized fun end(owner: Any) { if (source === owner) clear() }
    @Synchronized fun clear() {
        source = null; state.clear(); artwork.clear(); handler.removeCallbacks(tick); lastPhase = null
        publishLatest()
    }
    private fun publishLatest() {
        // One latest-state publication, outside HU/selector locks; no stale snapshot is captured.
        handler.removeCallbacks(publish)
        if (selected) handler.post(publish)
    }
    @Synchronized fun receive(owner: Any, event: HudTurn) {
        if (source === owner) state.receive(owner, event, SystemClock.uptimeMillis())
    }
    @Synchronized fun value(): CarPlayNowPlaying? {
        if (!selected) return null
        val view = state.display(SystemClock.uptimeMillis())
        if (lastPhase != view.phase) { lastPhase = view.phase; log("NAV-MEDIA: phase=${view.phase}; progress=distance-percent paused=true") }
        val bytes = view.icon
        val art = artwork.source("navigation-icon-${view.iconVersion}", view.iconVersion) { validatedIcon(bytes) }
        return view.nowPlaying(art)
    }
    /** Runs only on ArtworkRepository's worker; never decode/compress on the control reader. */
    private fun validatedIcon(bytes: ByteArray?): ByteArray {
        if (bytes != null && bytes.size <= 65_535) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outMimeType == "image/png" && options.outWidth in 1..2048 && options.outHeight in 1..2048 && options.outWidth.toLong() * options.outHeight <= 4_194_304) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) { bitmap.recycle(); return bytes }
            }
        }
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(24, 32, 40))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 9f }
            canvas.drawCircle(128f, 128f, 82f, paint)
            canvas.drawLine(98f, 162f, 128f, 75f, paint)
            canvas.drawLine(128f, 75f, 158f, 162f, paint)
            ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
