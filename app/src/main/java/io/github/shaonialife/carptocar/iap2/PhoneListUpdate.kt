package io.github.shaonialife.carptocar.iap2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal object PhoneListUpdate {
    // P3873 libesoiap2.so: StartListUpdates composer at 0x578f0, ListUpdate decoder at 0x5a6d0.
    data class Request(val recents: Int?, val favorites: Int?) {
        companion object {
            fun parse(body: ByteArray): Request {
                val params = Iap2Csm.parseParams(body).associateBy { it.id }
                fun limit(fields: Int, maximum: Int): Int? {
                    if (fields !in params) return null
                    val bytes = params[maximum]?.value
                    return if (bytes?.size == 2) {
                        (ByteBuffer.wrap(bytes).short.toInt() and 0xffff).takeIf { it != 0 } ?: Int.MAX_VALUE
                    } else Int.MAX_VALUE
                }
                return Request(limit(1, 3), limit(6, 8))
            }
        }
    }

    data class Number(val phone: String, val name: String?, val label: String? = null)
    data class Recent(val number: Number, val type: Int, val unixSeconds: Long, val durationSeconds: Long)
    data class Snapshot(val recents: List<Recent>, val favorites: List<Number>)

    fun encode(snapshot: Snapshot, budget: Int): ByteArray {
        val empty = Iap2SubscriptionReplies.idleBodyFor(Iap2SubscriptionReplies.LIST_UPDATE)!!
        val recents = ByteArrayOutputStream()
        val favorites = ByteArrayOutputStream()
        var remaining = budget - empty.size
        var recentIndex = 0
        var favoriteIndex = 0
        for (index in 0 until maxOf(snapshot.recents.size, snapshot.favorites.size)) {
            if (index < snapshot.recents.size) {
                val call = snapshot.recents[index]
                val fields = commonFields(recentIndex, call.number) +
                    Iap2Csm.u8(6, call.type) +
                    Iap2Csm.param(7, ByteBuffer.allocate(Long.SIZE_BYTES).putLong(call.unixSeconds).array()) +
                    Iap2Csm.u32(8, call.durationSeconds) +
                    Iap2Csm.u8(9, 1)
                val entry = Iap2Csm.param(1, fields)
                if (entry.size <= remaining) {
                    recents.write(entry)
                    remaining -= entry.size
                    recentIndex++
                }
            }
            if (index < snapshot.favorites.size) {
                val entry = Iap2Csm.param(6, commonFields(favoriteIndex, snapshot.favorites[index]))
                if (entry.size <= remaining) {
                    favorites.write(entry)
                    remaining -= entry.size
                    favoriteIndex++
                }
            }
        }
        // Preserve the envelope used for the empty reply. Q4 decodes repeated entry groups 1 and 6.
        return Iap2Csm.u8(0, 0) + recents.toByteArray() + Iap2Csm.u16(2, 0) +
            Iap2Csm.u8(5, 0) + favorites.toByteArray() + Iap2Csm.u16(7, 0)
    }

    private fun commonFields(index: Int, number: Number): ByteArray =
        Iap2Csm.u16(0, index) + Iap2Csm.string(1, number.phone) +
            (number.name?.let { Iap2Csm.string(2, it) } ?: byteArrayOf()) +
            (number.label?.let { Iap2Csm.string(3, it) } ?: byteArrayOf()) +
            Iap2Csm.u8(5, 1) // Telephony
}
