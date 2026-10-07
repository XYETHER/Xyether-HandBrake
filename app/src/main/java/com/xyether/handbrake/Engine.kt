package com.xyether.handbrake

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

class RenderException(msg: String) : Exception(msg)

object RenderBus {
    val progress = mutableFloatStateOf(0f)
    val outBytes = mutableLongStateOf(0L)
    val encodeFps = mutableFloatStateOf(0f)
    val frameBmp = mutableStateOf<Bitmap?>(null)
    val frameT = mutableStateOf("")
    val batchIx = mutableIntStateOf(0)
    val batchTotal = mutableIntStateOf(0)
    val totalDurSec = mutableFloatStateOf(0f)
    val activeName = mutableStateOf<String?>(null)
    val encoderLabel = mutableStateOf("H.264")
    var webOpt: Boolean = false
    val elapsedSeconds = mutableFloatStateOf(0f)
    @Volatile var abortRequested: Boolean = false

    fun resetForJob(durSec: Float) {
        progress.floatValue = 0f
        outBytes.longValue = 0L
        encodeFps.floatValue = 0f
        frameBmp.value = null
        frameT.value = ""
        totalDurSec.floatValue = durSec
        elapsedSeconds.floatValue = 0f
    }
}

// ============================ public entry ============================
suspend fun renderJob(ctx: Context, job: Job, outFile: File): Long =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        RenderExecution.run(cancelled = { RenderBus.abortRequested }) { session ->
            encodeWithSurfaces(ctx, job, outFile, session = session)
        }
    }

// ============================ audio pump (dedicated audio-track extractor) ============================
private class AudioPump(private val ext: MediaExtractor?) {
    class Sample(val bytes: ByteBuffer, val info: MediaCodec.BufferInfo)
    private val pending = ArrayDeque<Sample>()
    private val scratch = ByteBuffer.allocateDirect(1024 * 1024)
    var videoWatermarkUs = 0L
    private fun read(): Sample? {
        val e = ext ?: return null
        if (e.sampleTrackIndex < 0) return null
        scratch.clear()
        val size = e.readSampleData(scratch, 0)
        if (size < 0) return null
        require(size <= scratch.capacity()) { "Audio sample exceeds supported size" }
        scratch.position(0); scratch.limit(size)
        val bytes = ByteBuffer.allocateDirect(size).apply { put(scratch); flip() }
        val info = MediaCodec.BufferInfo().apply {
            offset = 0; this.size = size; presentationTimeUs = e.sampleTime
            flags = if (e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }
        e.advance()
        return Sample(bytes, info)
    }
    fun pump(muxStarted: Boolean, emit: (Sample) -> Unit) {
        val e = ext ?: return
        if (muxStarted) { pending.forEach(emit); pending.clear() }
        repeat(64) {
            if (e.sampleTrackIndex < 0 || e.sampleTime > videoWatermarkUs + 700_000L) return
            val sample = read() ?: return
            if (muxStarted) emit(sample) else pending.addLast(sample)
        }
    }
    fun drainAll(emit: (Sample) -> Unit) {
        pending.forEach(emit); pending.clear()
        while (true) {
            if (RenderBus.abortRequested) throw RenderException("__aborted__")
            if (RenderBus.abortRequested || Thread.currentThread().isInterrupted) throw RenderException("__aborted__")
            emit(read() ?: break)
        }
    }
}

// ============================ main transcode ============================
internal fun encodeWithSurfaces(context: Context, job: Job, out: File, hardwareOnly: Boolean = true, session: PipelineSession = PipelineSession()): Long {
    val s = job.settings
    val pfd = context.contentResolver.openFileDescriptor(job.uri, "r")!!

    val extractor = MediaExtractor()
    val previewer = FramePreviewer(context, job.uri)
    var success = false
    val startedAt = System.nanoTime()
    val schedule = FrameSchedule(s.fps)
    var dec: MediaCodec? = null
    var enc: MediaCodec? = null
    var encInSurface: Surface? = null
    var gl: GlScalePass? = null
    var muxer: MediaMuxer? = null
    var extAudio: MediaExtractor? = null

    try {
        session.stage = "opening the video"
        extractor.setDataSource(pfd.fileDescriptor)
        var vIx = -1
        var aIx = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (vIx < 0 && mime.startsWith("video/")) vIx = i
            else if (aIx < 0 && mime.startsWith("audio/")) aIx = i
        }
        if (vIx < 0) throw RenderException("no video track")
        val vFormat = extractor.getTrackFormat(vIx)
        val srcW = vFormat.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = vFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val durUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION))
            vFormat.getLong(MediaFormat.KEY_DURATION) else 10_000_000L
        val durSec = durUs / 1_000_000f
        RenderBus.resetForJob(durSec)
        RenderBus.encoderLabel.value = s.encoder.label

        val rotation = if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) vFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
        val transfer = if (vFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) vFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
        if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG)
            throw RenderException("HDR input is not supported yet. Use an SDR copy to avoid incorrect colors.")
        val rotated = rotation % 180 != 0
        val displayH = if (rotated) srcW else srcH
        val needsRescale = s.resolution != null && displayH > s.resolution!!
        // GL only when actually resizing; fps caps are handled by NOT rendering
        // skipped frames (keeps the zero-copy fast path at full speed)
        val useGl = needsRescale || srcW % 2 != 0 || srcH % 2 != 0
        val dims = if (rotated) scaledDims(srcH, srcW, s.resolution).let { it.second to it.first }
            else scaledDims(srcW, srcH, s.resolution)
        val (outW, outH) = dims

        val encFormat = MediaFormat.createVideoFormat(s.encoder.mime, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Qualcomm (c2.qti) encoders REQUIRE KEY_FRAME_RATE at configure time,
            // even in same-as-source mode — always provide one.
            val nominalFps = s.fps?.roundToInt() ?: run {
                try {
                    if (vFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
                        vFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(10, 240)
                    else 30
                } catch (_: Exception) { 30 }
            }
            setInteger(MediaFormat.KEY_FRAME_RATE, nominalFps)
            if (s.encoder.is10Bit) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                )
            }
            applyRateMode(this, s, durSec)
        }

        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { codec ->
            codec.isEncoder && (!hardwareOnly || isHardwareCodec(codec)) &&
                codec.supportedTypes.any { it.equals(s.encoder.mime, ignoreCase = true) } &&
                runCatching { codec.getCapabilitiesForType(s.encoder.mime).let { caps ->
                    caps.isFormatSupported(encFormat) && (s.encodingEffort == null || caps.encoderCapabilities.complexityRange.let { it.upper > it.lower })
                } }.getOrDefault(false)
        }?.name ?: throw RenderException("No compatible ${s.encoder.label} hardware encoder for ${outW}x${outH}. Try H.264 or a lower resolution.")
        val selectedCodec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == encoderName }
        applyEncodingEffort(encFormat, selectedCodec, s.encodingEffort)
        enc = MediaCodec.createByCodecName(encoderName)
        session.stage = "starting the encoder"
        enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encInSurface = enc.createInputSurface()
        enc.start()

        val drawTarget: Surface = if (useGl) {
            gl = GlScalePass(null, encInSurface!!, srcW, srcH, outW, outH)
            gl.decoderSurface!!
        } else {
            encInSurface!!
        }

        // Retain codec-specific data, crop and color metadata; rotation belongs to the muxer.
        val decFormat = vFormat
        decFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
        dec = MediaCodec.createDecoderByType(vFormat.getString(MediaFormat.KEY_MIME)!!)
        session.stage = "starting the decoder"
        dec.configure(decFormat, drawTarget, null, 0)
        dec.start()

        extractor.selectTrack(vIx)
        val audioFmt: MediaFormat? = if (aIx >= 0) extractor.getTrackFormat(aIx) else null
        // dedicated extractor with only the audio track selected (independent cursor)
        if (aIx >= 0) {
            extAudio = MediaExtractor().apply {
                setDataSource(pfd.fileDescriptor)
                selectTrack(aIx)
            }
        }

        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        var muxVideoIx = -1
        var muxAudioIx = -1
        var muxStarted = false
        val audioPump = AudioPump(extAudio)

        fun startMuxer(videoOutFmt: MediaFormat) {
            if (muxStarted) return
            muxVideoIx = muxer.addTrack(videoOutFmt)
            if (audioFmt != null) muxAudioIx = muxer.addTrack(audioFmt)
            muxer.start()
            muxStarted = true
        }

        val info = MediaCodec.BufferInfo()
        var writtenBytes = 0L
        var lastAudioPtsUs = Long.MIN_VALUE
        var lastEncodedVideoPtsUs = Long.MIN_VALUE
        var fpsWinFrames = 0L
        var fpsWinNanos = System.nanoTime()

        fun emitAudio(smp: AudioPump.Sample) {
            if (muxStarted && muxAudioIx >= 0) {
                muxer.writeSampleData(muxAudioIx, smp.bytes, smp.info)
                lastAudioPtsUs = smp.info.presentationTimeUs
                writtenBytes += smp.info.size
                RenderBus.outBytes.longValue = writtenBytes
            }
        }

        var decInEos = false
        var decOutEos = false
        var encEos = false
        session.advance()

        while (!encEos) {
            if (session.stopRequested.get() || RenderBus.abortRequested || Thread.currentThread().isInterrupted) throw RenderException("__aborted__")
            if (session.idleMs() > 60_000)
                throw RenderException("The device codec stopped responding. Try H.264 or a lower resolution.")

            // feed decoder (video track; audio samples skipped past via advance)
            if (!decInEos) {
                if (aIx >= 0) audioPump.pump(muxStarted, ::emitAudio)
                session.stage = "feeding the decoder"
                val ix = dec.dequeueInputBuffer(10_000)
                if (ix >= 0) {
                    val ib = dec.getInputBuffer(ix)!!
                    val sz = extractor.readSampleData(ib, 0)
                    if (sz < 0) {
                        dec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        decInEos = true
                    } else {
                        dec.queueInputBuffer(ix, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // drain decoder -> render kept frames into encoder path
            while (!decOutEos) {
                // Output drains must stay nonblocking. The old fast path used 0 here;
                // a fixed 10 ms wait on every drain pass caps the pipeline.
                session.stage = "reading decoded frames"
                val dix = dec.dequeueOutputBuffer(info, 0)
                when {
                    dix == MediaCodec.INFO_TRY_AGAIN_LATER || dix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> break
                    dix >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val pts = info.presentationTimeUs
                        session.advance()
                        // EOS may be attached to the last real frame; only an empty buffer is control-only.
                        val hasFrame = info.size > 0
                        val keep = hasFrame && schedule.keep(pts)
                        session.stage = "sending a frame to the encoder"
                        if (keep && !useGl) dec.releaseOutputBuffer(dix, pts * 1000L)
                        else dec.releaseOutputBuffer(dix, keep)
                        if (hasFrame) {
                            if (keep) {
                                if (useGl) {
                                    if (!gl!!.awaitFrame()) error("GL frame timeout")
                                    gl!!.consumeFrame()
                                    gl!!.presentFrame(pts * 1000L)
                                }
                                fpsWinFrames++
                                val now = System.nanoTime()
                                if (now - fpsWinNanos >= 500_000_000L) {
                                    RenderBus.encodeFps.floatValue = fpsWinFrames * 1_000_000_000f / (now - fpsWinNanos)
                                    fpsWinFrames = 0
                                    fpsWinNanos = now
                                }
                            }
                            val frac = (pts.toFloat() / durUs.coerceAtLeast(1)).coerceIn(0f, 0.99f)
                            RenderBus.progress.floatValue = maxOf(RenderBus.progress.floatValue, frac)
                            RenderBus.elapsedSeconds.floatValue = (System.nanoTime() - startedAt) / 1_000_000_000f
                            previewer.maybeGrab(pts / 1000f, frac)
                            audioPump.videoWatermarkUs = pts
                        }
                        if (eos) {
                            enc.signalEndOfInputStream()
                            decOutEos = true
                        }
                        break // Drain the encoder before another surface render can block.
                    }
                    else -> break
                }
            }

            // drain encoder -> muxer
            while (true) {
                // Keep encoder draining nonblocking; fixed waits here cap throughput.
                session.stage = "draining the encoder"
                val oix = enc.dequeueOutputBuffer(info, 0)
                when {
                    oix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer(enc.outputFormat)
                    oix >= 0 -> {
                        session.advance()
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxStarted && muxVideoIx >= 0) {
                            val ob = enc.getOutputBuffer(oix)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(muxVideoIx, ob, info)
                            lastEncodedVideoPtsUs = info.presentationTimeUs
                            writtenBytes += info.size
                            RenderBus.outBytes.longValue = writtenBytes
                        }
                        enc.releaseOutputBuffer(oix, false)
                        if (eos) { encEos = true; break }
                    }
                    else -> break
                }
            }
        }

        if (!muxStarted) throw RenderException("encoder produced no frames")

        if (aIx >= 0 && muxAudioIx >= 0) {
            audioPump.drainAll(::emitAudio)
            RenderBus.outBytes.longValue = writtenBytes
        }

        // MediaMuxer otherwise repeats the preceding packet's duration for the final sample.
        // Explicit track ends preserve trimmed AAC tails and the source video endpoint.
        fun finishTrack(track: Int, format: MediaFormat, lastPtsUs: Long) {
            if (track < 0 || lastPtsUs == Long.MIN_VALUE || !format.containsKey(MediaFormat.KEY_DURATION)) return
            val endUs = format.getLong(MediaFormat.KEY_DURATION)
            if (endUs > lastPtsUs) {
                val end = MediaCodec.BufferInfo().apply { set(0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                muxer.writeSampleData(track, ByteBuffer.allocateDirect(0), end)
            }
        }
        if (audioFmt != null) finishTrack(muxAudioIx, audioFmt, lastAudioPtsUs)
        finishTrack(muxVideoIx, vFormat, lastEncodedVideoPtsUs)

        session.stage = "finalizing the MP4"
        muxer.stop()
        if (s.webOptimize && !FastStart.apply(out)) throw RenderException("Could not optimize the MP4 for streaming.")
        if (session.stopRequested.get() || RenderBus.abortRequested || Thread.currentThread().isInterrupted) throw RenderException("__aborted__")
        success = true
        return out.length()
    } finally {
        session.stage = "closing the codecs"
        try { dec?.stop() } catch (_: Exception) {}
        try { dec?.release() } catch (_: Exception) {}
        try { enc?.stop() } catch (_: Exception) {}
        try { enc?.release() } catch (_: Exception) {}
        try { encInSurface?.release() } catch (_: Exception) {}
        try { gl?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { extractor.release() } catch (_: Exception) {}
        try { extAudio?.release() } catch (_: Exception) {}
        try { previewer.release() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
        if (!success) out.delete()
    }
}

internal fun isHardwareCodec(codec: MediaCodecInfo): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= 29) codec.isHardwareAccelerated
    else !codec.name.startsWith("OMX.google.", true) && !codec.name.startsWith("c2.android.", true)

private fun applyRateMode(f: MediaFormat, s: Settings, durSec: Float) {
    // avg bitrate, VBR (CQ removed — hardware CQ support is spotty across devices)
    f.setInteger(MediaFormat.KEY_BIT_RATE,
        (s.vbrMbps * 1_000_000).toInt().coerceAtLeast(100_000))
    f.setInteger(MediaFormat.KEY_BITRATE_MODE,
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
}

// ============================ viewfinder previewer ============================
private class FramePreviewer(private val context: Context, private val uri: android.net.Uri) {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val publicationLock = Any()
    @Volatile private var closed = false
    private var lastGrabMs = 0L
    private var mmr: MediaMetadataRetriever? = null
    fun maybeGrab(frameMs: Float, frac: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (closed || now - lastGrabMs < 1500 || !busy.compareAndSet(false, true)) return
        lastGrabMs = now
        worker.execute {
            try {
                val r = mmr ?: MediaMetadataRetriever().also { it.setDataSource(context, uri); mmr = it }
                val bmp = if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime((frameMs * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                else r.getFrameAtTime((frameMs * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                synchronized(publicationLock) {
                    if (!closed && bmp != null) { RenderBus.frameBmp.value = bmp; RenderBus.frameT.value = "T+${(frac * 100).toInt()}%" }
                    else bmp?.recycle()
                }
            } catch (_: Exception) {} finally { busy.set(false) }
        }
    }
    fun release() {
        synchronized(publicationLock) { closed = true }
        worker.execute { try { mmr?.release() } catch (_: Exception) {}; mmr = null }
        worker.shutdown()
    }
}
