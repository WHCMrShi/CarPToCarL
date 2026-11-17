package io.github.shaonialife.carptocar.iap2

import kotlin.math.roundToLong

/** PASCD motion adapted to CarLife's single integer speed and gear fields. */
internal data class PascdMotion(val speedKmh: Int, val gear: PascdGear? = null) {
    companion object {
        /** Sender layout: time,unknown,gear,unknown,count,(offset seconds,speed m/s)... */
        fun parse(sentence: String): PascdMotion? {
            val text = sentence.trim().trimEnd(';')
            if (!text.startsWith("\$PASCD,")) return null
            val star = text.indexOf('*')
            if (star < 0 || star != text.length - 3) return null
            val checksum = text.substring(star + 1).toIntOrNull(16) ?: return null
            val body = text.substring(1, star)
            if (body.fold(0) { sum, c -> sum xor c.code } != checksum) return null
            val fields = body.split(',')
            if (fields.size < 8 || (fields.size - 6) % 2 != 0) return null
            val count = fields[5].toIntOrNull() ?: return null
            if (count != (fields.size - 6) / 2) return null
            // CarLife accepts one current speed, so select the last sample in the batch.
            // Time/offset and unknown fields are not transmitted and need no interpretation.
            val kmh = (fields.last().toDoubleOrNull() ?: return null) * 3.6
            if (!kmh.isFinite()) return null
            // Nearest integer minimizes quantization error (<= 0.5 km/h); not a CarLife rule.
            val rounded = kmh.roundToLong()
            if (rounded !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
            return PascdMotion(rounded.toInt(), PascdGear.entries.firstOrNull { it.name == fields[3] })
        }
    }
}

/** Only source symbols evidenced by the PASCD sender implementation; no M/S/L guesses. */
internal enum class PascdGear { P, R, N, D }
