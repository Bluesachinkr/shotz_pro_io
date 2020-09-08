package com.android.shotz_pro_io.rtmp

import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.android.shotz_pro_io.stream.StreamingActivity
import java.io.IOException
import java.nio.ByteBuffer

internal class VideoEncoder : Encoder {
    private var isEncoding = false
    var inputSurface: Surface? = null
        private set
    private var encoder: MediaCodec? = null
    private var bufferInfo: MediaCodec.BufferInfo? = null
    private var listener: StreamingActivity.OnVideoEncoderStateListener? = null
    var lastFrameEncodedAt: Long = 0
        private set
    private var startStreamingAt: Long = 0
    fun setOnVideoEncoderStateListener(listener: StreamingActivity.OnVideoEncoderStateListener?) {
        this.listener = listener
    }

    /**
     * prepare the Encoder. call this before start the encoder.
     */
    @Throws(IOException::class)
    fun prepare(
        width: Int,
        height: Int,
        bitRate: Int,
        frameRate: Int,
        startStreamingAt: Long,
        mediaProjection: MediaProjection
    ) {
        this.startStreamingAt = startStreamingAt
        bufferInfo = MediaCodec.BufferInfo()
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder!!.createInputSurface()

        StreamingActivity.virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "Stream Activity",
            StreamingActivity.DISPLAY_WIDTH,
            StreamingActivity.DISPLAY_HEIGHT,
            StreamingActivity.mScreenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
    }

    override fun start() {
        encoder!!.start()
        isEncoding = true
        drain()
    }

    override fun stop() {
        if (isEncoding()) {
            encoder!!.signalEndOfInputStream()
        }
    }

    override fun isEncoding(): Boolean {
        return encoder != null && isEncoding
    }

    fun drain() {
        val handlerThread = HandlerThread("VideoEncoder-drain")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)
        handler.post(Runnable { // keep running... so use a different thread.
            while (isEncoding) {
                if (encoder == null) return@Runnable
                val encoderOutputBuffers: Array<ByteBuffer> = encoder!!.outputBuffers
                val inputBufferId =
                    encoder!!.dequeueOutputBuffer(bufferInfo!!, TIMEOUT_USEC.toLong())
                if (inputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder!!.outputFormat

                    val sps: ByteBuffer? = newFormat.getByteBuffer("csd-0")
                    val pps: ByteBuffer? = newFormat.getByteBuffer("csd-1")
                    if (sps != null && pps != null) {
                        val config = ByteArray(sps.limit() + pps.limit())
                        sps.get(config, 0, sps.limit())
                        pps.get(config, sps.limit(), pps.limit())
                        listener!!.onVideoDataEncoded(config, config.size, 0)
                    }
                } else {
                    if (inputBufferId > 0) {
                        val encodedData: ByteBuffer =
                            encoderOutputBuffers[inputBufferId] ?: continue
                        if (bufferInfo!!.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo!!.size = 0
                        }
                        if (bufferInfo!!.size != 0) {
                            encodedData.position(bufferInfo!!.offset)
                            encodedData.limit(bufferInfo!!.offset + bufferInfo!!.size)
                            val currentTime = System.currentTimeMillis()
                            val timestamp = (currentTime - startStreamingAt).toInt()
                            val data = ByteArray(bufferInfo!!.size)
                            encodedData.get(data, 0, bufferInfo!!.size)
                            encodedData.position(bufferInfo!!.offset)
                            listener!!.onVideoDataEncoded(data, bufferInfo!!.size, timestamp)
                            lastFrameEncodedAt = currentTime
                        }
                        encoder!!.releaseOutputBuffer(inputBufferId, false)
                    } else if (inputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue
                    }
                    if (bufferInfo!!.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
            release()
        })
    }

    private fun release() {
        if (encoder != null) {
            isEncoding = false
            encoder!!.stop()
            encoder!!.release()
            encoder = null
        }
    }

    companion object {
        // H.264 Advanced Video Coding
        private const val MIME_TYPE = "video/avc"

        // 5 seconds between I-frames
        private const val IFRAME_INTERVAL = 5
        private const val TIMEOUT_USEC = 10000
    }
}