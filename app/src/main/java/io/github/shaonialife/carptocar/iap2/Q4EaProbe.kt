package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.util.SessionTrace.toHexBody

internal object Q4EaProbe {
    const val MMI_PROTOCOL = "de.audi.mmiconnectdatatransfer"
    const val EADATA0_PROTOCOL = "com.audi.cpp.eadata0"
    const val EADATA1_PROTOCOL = "com.audi.cpp.eadata1"
    private val protocolNames = listOf(
        MMI_PROTOCOL,
        EADATA0_PROTOCOL,
        EADATA1_PROTOCOL,
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

    data class Status(val sessionId: Int, val code: Int)

    private fun status(fields: List<Iap2Csm.Param>): Status? {
        val session = fields.singleOrNull { it.id == 0 }?.value ?: return null
        val code = fields.singleOrNull { it.id == 1 }?.value?.singleOrNull()?.toInt()?.and(0xff) ?: return null
        if (session.size != 2) return null
        return Status(((session[0].toInt() and 0xff) shl 8) or (session[1].toInt() and 0xff), code)
    }

    fun status(frame: Iap2Csm.Frame): Status? =
        if (frame.messageId == Iap2MessageIds.EA_SESSION_STATUS) parseCompleteParams(frame.body)?.let(::status) else null

    fun responseLog(channel: String, frame: Iap2Csm.Frame): String? {
        if (frame.messageId != Iap2MessageIds.EA_SESSION_STATUS) return null
        val fields = parseCompleteParams(frame.body)
        val status = fields?.let(::status)
        val summary = when {
            fields == null -> "malformed TLV"
            status == null -> "invalid fields=" + fields.joinToString { "#${it.id}(${it.value.size}B)" }
            else -> {
                val statusName = when (status.code) {
                    0 -> "SessionStatusOK"
                    1 -> "SessionClose"
                    else -> "unknown"
                }
                "session=${status.sessionId} status=${status.code}($statusName)"
            }
        }
        return "EA: RX $channel 0xea03 $summary len=${frame.body.size} body=${frame.body.toHexBody()}"
    }

    private fun parseCompleteParams(body: ByteArray): List<Iap2Csm.Param>? =
        Iap2Csm.parseParams(body).takeIf { fields -> fields.sumOf { 4 + it.value.size } == body.size }
}
