package io.github.shaonialife.carptocar.iap2

import kotlin.math.roundToInt

/** PASCD is vehicle motion, not a GPS fix. Unknown fields C/0 are not interpreted. */
internal data class PascdMotion(val sampleSeconds: Double, val speedKmh: Int, val gear: PascdGear? = null) {
    companion object {
        /** Production-HU layout: time,C,gear,unknown,count,(offset seconds,speed m/s)... */
        fun parse(sentence: String): PascdMotion? {
            val text = sentence.trim().trimEnd(';')
            if (!text.startsWith("\$PASCD,")) return null
            val star = text.indexOf('*')
            if (star < 0 || star != text.length - 3) return null
            val checksum = text.substring(star + 1).toIntOrNull(16) ?: return null
            val body = text.substring(1, star)
            if (body.fold(0) { sum, c -> sum xor c.code } != checksum) return null
            val fields = body.split(',')
            if (fields.size < 8) return null
            // Only the evidenced C/.../0 layout is supported; other variants are not inferred.
            if (fields[2] != "C" || fields[4] != "0") return null
            val start = fields[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
            val count = fields[5].toIntOrNull()?.takeIf { it in 1..32 } ?: return null
            if (fields.size != 6 + count * 2) return null
            var previousOffset = -1.0
            var speed = 0.0
            for (i in 0 until count) {
                val offset = fields[6 + i * 2].toDoubleOrNull() ?: return null
                speed = fields[7 + i * 2].toDoubleOrNull() ?: return null
                if (!offset.isFinite() || offset !in 0.0..10.0 || offset <= previousOffset) return null
                // Reject impossible/unknown readings rather than clamping them to stationary.
                if (!speed.isFinite() || speed !in 0.0..100.0) return null
                previousOffset = offset
            }
            val sampleTime = start + previousOffset
            if (!sampleTime.isFinite()) return null
            return PascdMotion(sampleTime, (speed * 3.6).roundToInt(), PascdGear.entries.firstOrNull { it.name == fields[3] })
        }
    }
}

/** Only source symbols evidenced by the PASCD sender implementation; no M/S/L guesses. */
internal enum class PascdGear { P, R, N, D }

/** One instance per CarPlay source session. No cached sample is replayed on connection. */
internal class PascdMotionTracker {
    private var lastSeconds: Double? = null
    private var anchorSeconds = 0.0
    private var anchorMillis = 0L

    @Synchronized
    fun accept(sample: PascdMotion, receivedAt: Long): Boolean {
        val last = lastSeconds
        if (last != null && sample.sampleSeconds <= last) return false
        if (last == null) {
            anchorSeconds = sample.sampleSeconds
            anchorMillis = receivedAt
        }
        val delay = (receivedAt - anchorMillis) - (sample.sampleSeconds - anchorSeconds) * 1000.0
        if (delay > MAX_AGE_MS) return false
        // Keep the lowest observed transport delay as the local monotonic-clock anchor.
        if (delay < 0) {
            anchorSeconds = sample.sampleSeconds
            anchorMillis = receivedAt
        }
        lastSeconds = sample.sampleSeconds
        return true
    }

    companion object {
        const val MAX_AGE_MS = 1500L
        fun isFresh(receivedAt: Long, now: Long): Boolean = now - receivedAt in 0..MAX_AGE_MS
    }
}
