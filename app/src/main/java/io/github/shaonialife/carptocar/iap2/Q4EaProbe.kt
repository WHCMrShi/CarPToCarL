package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.util.SessionTrace.toHexBody

internal object Q4EaProbe {
    private val protocolNames = listOf(
        "de.audi.mmiconnectdatatransfer",
        "com.audi.cpp.eadata0",
        "com.audi.cpp.eadata1",
    )

    data class Request(val protocolId: Int, val protocolName: String, val sessionId: Int) {
        fun body(): ByteArray = Iap2Csm.u8(0, protocolId) + Iap2Csm.u16(1, sessionId)
    }

    fun requests(identificationBody: ByteArray): List<Request> {
        val declarations = Iap2Csm.parseParams(identificationBody)
            .filter { it.id == 10 }
            .mapNotNull { group ->
                val fields = parseCompleteParams(group.value) ?: return@mapNotNull null
                val id = fields.singleOrNull { it.id == 0 }?.value?.singleOrNull()?.toInt()?.and(0xff)
                    ?: return@mapNotNull null
                val name = fields.singleOrNull { it.id == 1 }?.asString() ?: return@mapNotNull null
                id to name
            }.distinct()
        return protocolNames.mapIndexedNotNull { index, name ->
            val declaration = declarations.singleOrNull { it.second == name } ?: return@mapIndexedNotNull null
            if (declarations.count { it.first == declaration.first } != 1) return@mapIndexedNotNull null
            Request(declaration.first, name, 0x0101 + index)
        }
    }

    fun responseLog(channel: String, frame: Iap2Csm.Frame): String? {
        if (frame.messageId != Iap2MessageIds.EA_SESSION_STATUS) return null
        val fields = parseCompleteParams(frame.body)
        val session = fields?.singleOrNull { it.id == 0 }?.value
        val status = fields?.singleOrNull { it.id == 1 }?.value?.singleOrNull()?.toInt()?.and(0xff)
        val summary = when {
            fields == null -> "malformed TLV"
            session?.size != 2 || status == null -> "invalid fields=" + fields.joinToString { "#${it.id}(${it.value.size}B)" }
            else -> {
                val sessionId = ((session[0].toInt() and 0xff) shl 8) or (session[1].toInt() and 0xff)
                val statusName = when (status) {
                    0 -> "SessionStatusOK"
                    1 -> "SessionClose"
                    else -> "unknown"
                }
                "session=$sessionId status=$status($statusName)"
            }
        }
        return "EA: RX $channel 0xea03 $summary len=${frame.body.size} body=${frame.body.toHexBody()}"
    }

    private fun parseCompleteParams(body: ByteArray): List<Iap2Csm.Param>? =
        Iap2Csm.parseParams(body).takeIf { fields -> fields.sumOf { 4 + it.value.size } == body.size }
}
