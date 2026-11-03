package org.librehu.bridge.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.ArrayDeque

/**
 * H.264 of the head unit's screen ([org.librehu.bridge.data.BridgeClient] binary frames: kind `C` SPS and PPS, `K` key
 * frame, `P` other frame, then the 8-byte pts in µs and the Annex B data) decoded by MediaCodec into [surface], each
 * frame shown as soon as it is decoded. [onBroken] asks the head unit for a key frame (decoder error, frames dropped
 * while behind). The decoder is fed from its own thread only, in the order of the frames.
 */
class ScreenDecoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val onBroken: () -> Unit,
) {
    private class Packet(
        val kind: Char,
        val pts: Long,
        val data: ByteArray,
    )

    private val thread = HandlerThread("screen-decoder").apply { start() }
    private val handler = Handler(thread.looper)

    // Guarded by this.
    private val packets = ArrayDeque<Packet>()
    private var config: Packet? = null

    /** Frames are dropped until the next key frame (decoder restarted, or too many waiting). */
    private var waitKey = true

    // Decoder thread only.
    private val inputs = ArrayDeque<Int>()
    private var codec: MediaCodec? = null

    @Volatile
    private var closed = false

    init {
        handler.post { open() }
    }

    private fun open() {
        if (closed) return
        try {
            val c = MediaCodec.createDecoderByType(MIME)
            c.setCallback(
                object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(
                        mc: MediaCodec,
                        index: Int,
                    ) {
                        if (mc !== codec) return
                        inputs.add(index)
                        pump()
                    }

                    override fun onOutputBufferAvailable(
                        mc: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo,
                    ) {
                        if (mc === codec) runCatching { mc.releaseOutputBuffer(index, info.size > 0) }
                    }

                    override fun onError(
                        mc: MediaCodec,
                        e: MediaCodec.CodecException,
                    ) {
                        Log.w(TAG, "decoder: ${e.diagnosticInfo}")
                        if (mc === codec) restart()
                    }

                    override fun onOutputFormatChanged(
                        mc: MediaCodec,
                        format: MediaFormat,
                    ) {}
                },
                handler,
            )
            val format =
                MediaFormat.createVideoFormat(MIME, width, height).apply {
                    // Android 11 and later: each frame out as soon as it is in.
                    setInteger("low-latency", 1)
                }
            c.configure(format, surface, null, 0)
            inputs.clear()
            codec = c
            c.start()
        } catch (e: Exception) {
            Log.w(TAG, "decoder: ${e.message}")
        }
    }

    /** After a decoder error: a new decoder, the SPS and PPS again, then the next key frame (asked for). */
    private fun restart() {
        val c = codec
        codec = null
        inputs.clear()
        runCatching { c?.stop() }
        runCatching { c?.release() }
        synchronized(this) {
            packets.clear()
            config?.let { packets.add(it) }
            waitKey = true
        }
        onBroken()
        handler.postDelayed({ open() }, 200)
    }

    /** A binary frame of the head unit (network thread). */
    fun feed(frame: ByteArray) {
        if (frame.size < 9 || closed) return
        var pts = 0L
        for (i in 1..8) pts = (pts shl 8) or (frame[i].toLong() and 0xff)
        val p = Packet(frame[0].toInt().toChar(), pts, frame.copyOfRange(9, frame.size))
        var broken = false
        synchronized(this) {
            when (p.kind) {
                'C' -> {
                    config = p
                    packets.add(p)
                }
                'K' -> {
                    waitKey = false
                    packets.add(p)
                }
                else -> if (!waitKey) packets.add(p)
            }
            // Far behind (decoder stuck or too slow): start again from the next key frame.
            if (packets.size > MAX_WAITING) {
                packets.removeAll { it.kind != 'C' }
                waitKey = true
                broken = true
            }
        }
        if (broken) onBroken()
        handler.post { pump() }
    }

    private fun pump() {
        val c = codec ?: return
        while (inputs.isNotEmpty()) {
            val p = synchronized(this) { packets.poll() } ?: return
            val index = inputs.poll()!!
            try {
                val buf = c.getInputBuffer(index) ?: continue
                buf.clear()
                if (buf.capacity() < p.data.size) {
                    Log.w(TAG, "frame of ${p.data.size} bytes, buffer of ${buf.capacity()}")
                    c.queueInputBuffer(index, 0, 0, p.pts, 0)
                    continue
                }
                buf.put(p.data)
                c.queueInputBuffer(index, 0, p.data.size, p.pts, if (p.kind == 'C') MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
            } catch (e: Exception) {
                Log.w(TAG, "decoder input: ${e.message}")
                restart()
                return
            }
        }
    }

    fun close() {
        closed = true
        handler.post {
            val c = codec
            codec = null
            runCatching { c?.stop() }
            runCatching { c?.release() }
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "LibreHU-Screen"
        private const val MIME = "video/avc"
        private const val MAX_WAITING = 45
    }
}
