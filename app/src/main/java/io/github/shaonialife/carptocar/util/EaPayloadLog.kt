package io.github.shaonialife.carptocar.util

import java.io.File

internal object EaPayloadLog {
    private const val FILE_NAME = "ea_payloads.hex.log"
    private var file: File? = null

    @Synchronized
    fun attach(directory: File, revision: String) {
        file = File(directory, FILE_NAME)
        file?.appendText("timeMs=${System.currentTimeMillis()} capture-start revision=$revision\n")
    }

    @Synchronized
    fun record(connection: String, linkSession: Int, sequence: Int, payload: ByteArray) {
        val target = file ?: return
        val session = if (payload.size >= 2) {
            ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        } else null
        val offset = if (session == null) 0 else 2
        val hex = buildString((payload.size - offset) * 2) {
            for (index in offset until payload.size) {
                val value = payload[index].toInt() and 0xff
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 0xf])
            }
        }
        val size = payload.size - offset
        val detail = if (session == null) "malformed missing-session-id rawBytes=$size rawHex=$hex"
            else "session=$session dataBytes=$size bodyHex=$hex"
        target.appendText("timeMs=${System.currentTimeMillis()} RX WiFi connection=$connection linkSession=$linkSession seq=$sequence $detail\n")
    }
}
