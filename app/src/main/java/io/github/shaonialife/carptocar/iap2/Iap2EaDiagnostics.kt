package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.util.SessionTrace.toHexBody

/** 只解释入站 EA 控制消息；保留原始字段，便于和配件的状态报告对照。 */
internal fun Iap2Csm.Frame.eaReceiveLog(channel: String): String? {
    val event = when (messageId) {
        Iap2MessageIds.REQUEST_APP_LAUNCH -> "RequestAppLaunch"
        Iap2MessageIds.EA_SESSION_STATUS -> describeEaStatus(body)
        else -> return null
    }
    return "EA: RX $channel 0x${messageId.toString(16)} $event len=${body.size} body=[${body.toHexBody()}]"
}

private fun describeEaStatus(body: ByteArray): String {
    val params = Iap2Csm.parseParams(body)
    val session = params.singleOrNull { it.id == 0 }?.value
    val status = params.singleOrNull { it.id == 1 }?.value
    if (params.sumOf { 4 + it.value.size } != body.size) return "StatusExternalAccessoryProtocolSession malformed TLV"
    if (session?.size != 2 || status?.size != 1) {
        return "StatusExternalAccessoryProtocolSession invalid fields=" +
            params.joinToString { "#${it.id}(${it.value.size}B)" }
    }
    val sessionId = ((session[0].toInt() and 0xff) shl 8) or (session[1].toInt() and 0xff)
    val code = status[0].toInt() and 0xff
    val name = when (code) {
        0 -> "SessionStatusOK"
        1 -> "SessionClose"
        else -> "unknown"
    }
    return "StatusExternalAccessoryProtocolSession session=$sessionId status=$code($name)"
}
