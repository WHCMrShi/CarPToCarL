package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.media.EncodedArtwork

/** 仅一张在途图片；协议状态锁内不执行 IO。独立超时只终止封面，不关闭控制连接。 */
internal class Iap2ArtworkSender(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    private enum class Phase { NONE, SETUP, START, DATA, SUCCESS, DONE }
    private var phase = Phase.NONE
    private var content: String? = null
    private var image: EncodedArtwork? = null
    private var transferId: Int? = null
    private var nextId = 129
    private var allocated = 0
    private var generation = 0L
    private var offset = 0
    private var deadline = 0L
    private var announced = false
    private var cancel: Int? = null
    private var activePlan: Plan? = null
    private var attribute: Int? = null
    private var awaitingImage = false
    private var failed = false
    private var retryCount = 0
    private var retryContent: String? = null
    private val diagnosticStates = HashMap<String, String>()
    private fun diagnostic(state: String) {
        val category = state.substringBefore(' ')
        if (diagnosticStates[category] == state) return
        diagnosticStates[category] = state
        log("ARTWORK: $state")
    }

    class Plan internal constructor(val bytes: ByteArray, internal val generation: Long,
        internal val previousPhase: Int, internal val previousOffset: Int,
        internal val previousDeadline: Long, internal val cancelId: Int?)

    @Synchronized fun reset(resetBudget: Boolean = true) {
        diagnosticStates.clear()
        if (phase in listOf(Phase.START, Phase.DATA, Phase.SUCCESS)) cancel = transferId
        generation++; phase = Phase.NONE; content = null; image = null; transferId = null
        announced = false; activePlan = null; awaitingImage = false
        failed = false
        if (resetBudget) { retryCount = 0; retryContent = null }
    }

    @Synchronized fun newLink() {
        reset(); cancel = null; attribute = null; lastTrack = null
        nextId = 129; allocated = 0
    }

    @Synchronized fun decorate(value: CarPlayNowPlaying, requestedField: Int?, supported: Boolean): CarPlayNowPlaying {
        if (!supported || requestedField == null) {
            diagnostic("skip supported=$supported field=$requestedField sourcePresent=${value.artworkSource != null}")
            return value.copy(artworkSource = null, artworkTransferId = null)
        }
        attribute = requestedField
        val result = value.artworkSource?.request()
        val nextImage = result?.image
        if (value.artworkSource?.trackId != lastTrack) {
            retryCount = 0; retryContent = null
            if (failed && phase == Phase.DONE) {
                content = null; transferId = null; failed = false
            }
        }
        awaitingImage = result != null && !result.complete
        diagnostic("prepare revision=${value.artworkSource?.revision} sourcePresent=${value.artworkSource != null} complete=${result?.complete} imageBytes=${nextImage?.size ?: 0}")
        // 加载中不等于「无封面」；新曲先撤去引用，不发送空文件再撤销。
        val pendingImage = result != null && !result.complete && nextImage == null
        if (pendingImage && value.artworkSource?.trackId != lastTrack) {
            if (phase in listOf(Phase.START, Phase.DATA, Phase.SUCCESS)) cancel = transferId
            generation++; activePlan = null; phase = Phase.NONE; image = null; content = null
            transferId = null; announced = false
        }
        lastTrack = value.artworkSource?.trackId
        if (!pendingImage) {
            val key = nextImage?.digest ?: "empty"
            if (key != content) {
                if (retryContent != key) { retryCount = 0; retryContent = key }
                failed = false
                if (phase in listOf(Phase.START, Phase.DATA, Phase.SUCCESS)) cancel = transferId
                generation++; activePlan = null; image = nextImage; content = key
                // 0x80 始终代表空图；其余 ID 在本连接不复用，防接收端缓存旧图。
                transferId = if (nextImage == null || allocated >= 127) 128 else {
                    allocated++
                    nextId++
                }
                if (transferId == 128) {
                    if (nextImage != null && allocated == 127) log("ARTWORK: 本连接封面 ID 已用尽，等待重连")
                    image = null
                }
                offset = 0; announced = false; phase = Phase.SETUP
                log("ARTWORK: selected id=$transferId field=$attribute bytes=${image?.size ?: 0} empty=${image == null}")
            }
        }
        return value.copy(artworkSource = null, artworkTransferId = transferId.takeUnless { failed && phase == Phase.DONE }, artworkAttributeId = attribute ?: 26)
    }
    private var lastTrack: String? = null

    /** 重复订阅只重申引用，不取消同一图片；字段改变才重建。 */
    @Synchronized fun subscribe(field: Int?) {
        if (attribute != field) reset()
        else if (phase == Phase.DONE && failed) {
            if (retryCount < 2) {
                retryCount++
                log("ARTWORK: retry-on-subscribe attempt=$retryCount previousId=$transferId")
                reset(resetBudget = false)
            } else diagnostic("retry-exhausted id=$transferId attempts=$retryCount")
        }
        attribute = field
    }

    @Synchronized fun metadataSent(id: Int?) {
        if (id != null && id == transferId) {
            if (!announced) log("ARTWORK: reference-queued id=$id field=$attribute (link queue accepted, not receiver display confirmation)")
            announced = true
        }
    }

    @Synchronized fun response(bytes: ByteArray) {
        if (bytes.size != 2 || (bytes[0].toInt() and 255) != transferId) {
            diagnostic("ignored-response bytes=${bytes.size} id=${bytes.firstOrNull()?.toInt()?.and(255)} expected=$transferId")
            return
        }
        diagnostic("response id=$transferId status=${bytes[1].toInt() and 255} phase=$phase sent=$offset/${image?.size ?: 0}")
        when (bytes[1].toInt() and 255) {
            1 -> if (phase == Phase.START) { phase = Phase.DATA; deadline = clock() + 30_000 }
            5, 6, 2 -> {
                failed = bytes[1].toInt() != 5
                if (bytes[1].toInt() != 5) log("ARTWORK: 接收端终止封面，状态=${bytes[1].toInt() and 255}")
                phase = Phase.DONE; image = null
            }
            3 -> if (phase == Phase.DATA) { phase = Phase.START; deadline = clock() + 10_000 }
        }
    }

    @Synchronized fun poll(maxPayload: Int, sessionVersion: Int = 2): Plan? {
        if (activePlan != null) return null
        val setupSize = Iap2FileTransferFormat.setupSize(sessionVersion) ?: return null
        if (maxPayload < setupSize) { phase = Phase.DONE; image = null; cancel = null; awaitingImage = false; return null }
        if (phase in listOf(Phase.START, Phase.DATA, Phase.SUCCESS) && clock() >= deadline) {
            log("ARTWORK: 封面传输超时 id=$transferId phase=$phase sent=$offset/${image?.size ?: 0}，取消文件传输")
            cancel = transferId; phase = Phase.DONE; image = null
            failed = true
        }
        val oldPhase = phase; val oldOffset = offset; val oldDeadline = deadline
        val id = transferId
        val oldCancel = cancel
        val bytes = if (oldCancel != null) {
            cancel = null; byteArrayOf(oldCancel.toByte(), 2)
        } else {
            if (!announced || id == null) return null
            when (phase) {
                Phase.SETUP -> Iap2FileTransferFormat.setup(id, image?.size ?: 0, sessionVersion).also {
                    log("ARTWORK: SETUP version=$sessionVersion bytes=${it.size} id=$id imageBytes=${image?.size ?: 0}")
                    phase = Phase.START; deadline = clock() + 10_000
                }
                Phase.DATA -> {
                    val size = image?.size ?: 0
                    val length = minOf(size - offset, maxPayload - 2, 2048)
                    ByteArray(length + 2).also {
                        it[0] = id.toByte()
                        it[1] = (if (offset == 0 && length == size) 0xC0 else if (offset == 0) 0x80
                            else if (offset + length == size) 0x40 else 0).toByte()
                        image?.copyTo(it, 2, offset, length)
                        offset += length
                        phase = if (offset == size) Phase.SUCCESS else Phase.DATA
                        deadline = clock() + 30_000
                    }
                }
                else -> return null
            }
        }
        return Plan(bytes, generation, oldPhase.ordinal, oldOffset, oldDeadline, oldCancel).also { activePlan = it }
    }

    @Synchronized fun submitted(plan: Plan, accepted: Boolean) {
        if (activePlan !== plan || generation != plan.generation) return
        activePlan = null
        val type = plan.bytes[1].toInt() and 255
        if (accepted && (type == 4 || type == 2 || type and 0x40 != 0)) {
            log("ARTWORK: file-queued id=${plan.bytes[0].toInt() and 255} type=$type phase=$phase sent=$offset/${image?.size ?: 0} (await receiver response)")
        }
        if (!accepted) {
            diagnostic("file-backpressure id=$transferId phase=$phase; retry pending")
            if (plan.cancelId != null) cancel = plan.cancelId
            else { phase = Phase.entries[plan.previousPhase]; offset = plan.previousOffset; deadline = plan.previousDeadline }
        }
    }
    @Synchronized fun pending(): Boolean = awaitingImage || cancel != null || phase !in listOf(Phase.NONE, Phase.DONE)

    @Synchronized fun hasCancellation(): Boolean = cancel != null || activePlan?.cancelId != null

    @Synchronized fun needsRefresh(): Boolean = awaitingImage

    @Synchronized fun nextDelayMillis(): Long = if (phase == Phase.DATA || phase == Phase.SETUP || cancel != null) 100L else 500L

    companion object {
        fun requestedField(body: ByteArray): Int? {
            val media = Iap2Csm.parseParams(body).firstOrNull { it.id == 0 } ?: return null
            val fields = Iap2Csm.parseParams(media.value)
            return when { fields.any { it.id == 26 } -> 26; fields.any { it.id == 20 } -> 20; else -> null }
        }
    }
}
