package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayClient
import io.github.shaonialife.carptocar.airplay.TimingServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Independent, experimental type 101 PCM sender with separate packetization and RTP pacing. */
internal class NavigationAudioSender(
    private val host: String,
    private val stream: AirPlayClient.AudioStreamInfo,
    private val log: (String) -> Unit,
    private val ducking: NavigationPromptDucking? = null,
) : AutoCloseable {
    private data class Block(
        val pcm: ByteArray,
        val rate: Int,
        val channels: Int,
        val source: Any,
        val generation: Int,
        val atNs: Long,
    )

    private sealed interface RtpWork {
        data class Packet(val payload: ByteArray, val pcmAtNs: Long, val startsPrompt: Boolean) : RtpWork
        data class End(val reportIdle: Boolean) : RtpWork
    }

    private val queue = ArrayBlockingQueue<Block>(8)
    private val rejected = AtomicInteger()
    private val rtpLock = Object()
    private val rtpQueue = ArrayDeque<RtpWork>()
    private val packetized = AtomicInteger()
    private val rtpQueueDrops = AtomicInteger()
    @Volatile private var closed = false
    private var unsupportedFormat: Triple<Int, Int, Int>? = null
    private var firstInput = true
    private var loggedClosedInput = false
    private val worker = Thread(::run, "q4-alt-audio").apply { isDaemon = true }
    private val rtpWorker = Thread(::runRtp, "q4-alt-audio-rtp").apply { isDaemon = true }

    fun start() = worker.start()

    @Synchronized
    fun offer(pcm: ByteArray, size: Int, rate: Int, channels: Int, bits: Int, source: Any, generation: Int) {
        if (closed) {
            if (!loggedClosedInput) log("ALT-AUDIO-PROBE: dropped 9241 PCM because the 101 sender stopped")
            loggedClosedInput = true
            return
        }
        if (rate !in 8_000..192_000 || channels !in 1..2 || bits != 16) {
            val format = Triple(rate, channels, bits)
            if (format != unsupportedFormat) log("ALT-AUDIO-PROBE: unsupported TTS input ${rate}Hz ${channels}ch ${bits}bit")
            unsupportedFormat = format
            return
        }
        unsupportedFormat = null
        val frameBytes = channels * 2
        val rejection = when {
            size !in frameBytes..minOf(pcm.size, rate / 5 * frameBytes) || size % frameBytes != 0 ->
                "invalid PCM block bytes=$size for ${rate}Hz ${channels}ch"
            queue.remainingCapacity() == 0 -> "9241 queue full"
            else -> null
        }
        if (rejection != null) {
            reject(rejection)
            return
        }
        queue.offer(Block(pcm.copyOf(size), rate, channels, source, generation, System.nanoTime()))
        if (firstInput) {
            firstInput = false
            log("ALT-AUDIO-PROBE: first 9241 PCM ${rate}Hz ${channels}ch s16, bytes=$size")
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        queue.clear()
        synchronized(rtpLock) { rtpQueue.clear() }
        val caller = Thread.currentThread()
        if (caller !== worker) worker.interrupt()
        if (caller !== rtpWorker) rtpWorker.interrupt()
        ducking?.close()
    }

    private fun reject(reason: String) {
        val dropped = rejected.incrementAndGet()
        if (dropped <= 3) log("ALT-AUDIO-PROBE: dropped 9241 PCM: $reason, discarded=$dropped")
    }

    private fun run() {
        try {
            if (!closed) {
                log("ALT-AUDIO-PROBE: PCM rate=$SAMPLE_RATE channels=1 bits=16 samplesPerPacket=$FRAME_SAMPLES")
                rtpWorker.start()
                OboeSpeechResampler().use(::packetizeSpeech)
            }
        } catch (_: InterruptedException) {
            // Normal session retirement.
        } catch (error: Exception) {
            if (!closed) log("ALT-AUDIO-PROBE: sender stopped: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            close()
        }
    }

    private fun runRtp() {
        try {
            DatagramChannel.open().use { data ->
                data.configureBlocking(false)
                data.connect(InetSocketAddress(host, stream.dataPort))
                DatagramChannel.open().use { control ->
                    control.configureBlocking(false)
                    if (stream.controlPort != 0) control.connect(InetSocketAddress(host, stream.controlPort))
                    sendSpeech(data, control)
                }
            }
        } catch (_: InterruptedException) {
            // Normal session retirement.
        } catch (error: Exception) {
            if (!closed) log("ALT-AUDIO-PROBE: RTP sender stopped: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            close()
        }
    }

    private fun sendSpeech(data: DatagramChannel, control: DatagramChannel) {
        val sealer = PcmPacketSealer(stream.streamKey, 101, PACKET_BYTES)
        var packetCount = 0L
        var sent = 0L
        var udpDrops = 0L
        var firstPacket = true
        var sampleTime = 0L
        var deadline = 0L
        var lastSync = 0L
        var syncSequence = 0
        var syncFailures = 0
        var rtpAgeDrops = 0L
        var lastPacketEndNs: Long? = null
        val origin = System.nanoTime()

        while (!closed) {
            val work = synchronized(rtpLock) {
                while (!closed && rtpQueue.isEmpty()) rtpLock.wait()
                if (closed) return
                rtpQueue.removeFirst()
            }
            when (work) {
                is RtpWork.End -> {
                    ducking?.end(lastPacketEndNs)
                    lastPacketEndNs = null
                    firstPacket = true
                    if (work.reportIdle) {
                        log("ALT-AUDIO-PROBE: 9241 idle 60ms; totals packetized=${packetized.get()} sent=$sent udpDrops=$udpDrops rejected=${rejected.get()} syncFailures=$syncFailures rtpQueueDrops=${rtpQueueDrops.get()} rtpAgeDrops=$rtpAgeDrops")
                    }
                }
                is RtpWork.Packet -> {
                    firstPacket = firstPacket || work.startsPrompt
                    val now = System.nanoTime()
                    val wait = deadline - now
                    val expiresBeforeSend = !firstPacket && deadline - work.pcmAtNs > MAX_AUDIO_AGE_NS
                    if (!firstPacket && wait > 0 && !expiresBeforeSend) {
                        TimeUnit.NANOSECONDS.sleep(wait)
                    }
                    if (closed) return
                    val sentAt = System.nanoTime()
                    val pcmAge = sentAt - work.pcmAtNs
                    if (expiresBeforeSend || pcmAge > MAX_AUDIO_AGE_NS) {
                        if (++rtpAgeDrops == 1L) log("ALT-AUDIO-PROBE: dropped PCM before RTP: PCM age at send deadline exceeds 250ms")
                        if (!firstPacket) sampleTime += FRAME_SAMPLES
                        continue
                    }
                    if (firstPacket) {
                        val elapsedFrames = (sentAt - origin) / FRAME_NS
                        sampleTime = maxOf(sampleTime, elapsedFrames * FRAME_SAMPLES)
                        deadline = sentAt
                        ducking?.begin()
                    }
                    if (control.isConnected && (firstPacket || sentAt - lastSync >= 1_000_000_000L)) {
                        val sync = AudioSyncPacket.build(syncSequence++, sampleTime, sampleTime + FRAME_SAMPLES,
                            TimingServer.ntpFromNanos(sentAt), firstPacket)
                        try {
                            if (control.write(ByteBuffer.wrap(sync)) == 0 && ++syncFailures == 1) {
                                log("ALT-AUDIO-PROBE: first sync write blocked")
                            }
                        } catch (error: IOException) {
                            if (++syncFailures == 1) log("ALT-AUDIO-PROBE: first sync write failed: ${error.message}")
                        }
                        lastSync = sentAt
                    }
                    val wire = sealer.seal(work.payload, 0, packetCount.toInt(), sampleTime, packetCount + 1, firstPacket)
                    if (data.write(ByteBuffer.wrap(wire)) > 0) {
                        sent++
                        lastPacketEndNs = sentAt + FRAME_NS
                    } else if (++udpDrops == 1L) {
                        log("ALT-AUDIO-PROBE: first RTP datagram dropped by non-blocking UDP")
                    }
                    packetCount++
                    sampleTime += FRAME_SAMPLES
                    deadline = maxOf(deadline + FRAME_NS, sentAt)
                    if (firstPacket) log("ALT-AUDIO-PROBE: first RTP of prompt bytes=${work.payload.size} sampleTime=${sampleTime - FRAME_SAMPLES} sent=$sent pcmAgeMs=${pcmAge / 1_000_000}")
                    firstPacket = false
                }
            }
        }
    }

    private fun packetizeSpeech(resampler: OboeSpeechResampler) {
        var frame = ByteArray(PACKET_BYTES)
        var frameOffset = 0
        var previous: Block? = null
        var frameAtNs = 0L
        var startsPrompt = true
        var loggedRate = 0
        var loggedChannels = 0

        fun queuePacket() {
            packetized.incrementAndGet()
            val accepted = synchronized(rtpLock) {
                if (closed) return
                if (rtpQueue.size >= MAX_PENDING_RTP) false else {
                    rtpQueue.addLast(RtpWork.Packet(frame, frameAtNs, startsPrompt))
                    rtpLock.notify()
                    true
                }
            }
            frameOffset = 0
            if (accepted) {
                // The RTP worker owns the queued array.
                frame = ByteArray(PACKET_BYTES)
                startsPrompt = false
            } else if (rtpQueueDrops.incrementAndGet() == 1) {
                log("ALT-AUDIO-PROBE: dropped PCM before RTP: output queue full")
            }
        }

        fun endPrompt(reportIdle: Boolean) {
            synchronized(rtpLock) {
                if (closed) return
                // Keep the boundary behind its audio, with at most one extra slot for it.
                val previousEnd = rtpQueue.lastOrNull() as? RtpWork.End
                if (previousEnd != null) rtpQueue.removeLast()
                rtpQueue.addLast(RtpWork.End(reportIdle || previousEnd?.reportIdle == true))
                rtpLock.notify()
            }
            startsPrompt = true
        }

        fun finishPrompt() {
            if (frameOffset > 0) {
                frame.fill(0, fromIndex = frameOffset)
                queuePacket()
            }
            endPrompt(reportIdle = true)
            resampler.reset()
        }

        while (!closed) {
            val prior = previous
            val block = if (prior == null) queue.take() else queue.poll(60, TimeUnit.MILLISECONDS)
            if (block == null) {
                finishPrompt()
                previous = null
                continue
            }
            if (System.nanoTime() - block.atNs > MAX_AUDIO_AGE_NS) {
                reject("9241 block older than 250ms")
                frameOffset = 0
                resampler.reset()
                previous = null
                endPrompt(reportIdle = false)
                continue
            }
            if (prior != null && (prior.source !== block.source || prior.generation != block.generation ||
                    prior.rate != block.rate || prior.channels != block.channels)
            ) {
                frameOffset = 0
                resampler.reset()
                startsPrompt = true
            }
            previous = block
            fun write(sample: Short) {
                if (frameOffset == 0) frameAtNs = block.atNs
                val value = sample.toInt()
                // PcmPacketSealer consumes little-endian PCM.
                frame[frameOffset++] = value.toByte()
                frame[frameOffset++] = (value ushr 8).toByte()
                if (frameOffset == PACKET_BYTES) queuePacket()
            }
            if (block.rate == SAMPLE_RATE) {
                val pcm = ByteBuffer.wrap(block.pcm).order(ByteOrder.LITTLE_ENDIAN)
                while (pcm.hasRemaining() && !closed) {
                    val left = pcm.short
                    if (block.channels == 1) {
                        write(left)
                    } else {
                        write(((left + pcm.short) / 2).toShort())
                    }
                }
            } else if (!closed) {
                val resampled = resampler.process(block.pcm, block.rate, block.channels)
                while (resampled.hasRemaining() && !closed) write(resampled.short)
                if (loggedRate != block.rate || loggedChannels != block.channels) {
                    log("ALT-AUDIO-PROBE: 9241 resampler=Oboe Best ${block.rate}Hz ${block.channels}ch -> 48000Hz mono s16")
                }
            }
            loggedRate = block.rate
            loggedChannels = block.channels
        }
    }

    companion object {
        private const val SAMPLE_RATE = OboeSpeechResampler.OUTPUT_RATE
        private const val FRAME_SAMPLES = AudioSender.SAMPLES_PER_PACKET
        private const val PACKET_BYTES = FRAME_SAMPLES * 2
        private const val FRAME_NS = FRAME_SAMPLES * 1_000_000_000L / SAMPLE_RATE
        private const val MAX_AUDIO_AGE_NS = 250_000_000L
        private const val MAX_PENDING_RTP = (MAX_AUDIO_AGE_NS / FRAME_NS).toInt()
    }
}
