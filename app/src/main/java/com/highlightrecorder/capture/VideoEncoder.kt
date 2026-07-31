package com.highlightrecorder.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.highlightrecorder.buffer.EncodedPacket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 硬件视频编码器:MediaCodec async + inputSurface。
 * VirtualDisplay 直接渲染到 [inputSurface],全程无原始帧拷贝。
 * 输出回调把编码后的包转交 [onPacket](进环形缓冲)。
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitrateBps: Int,
    private val mime: String,
    private val iFrameIntervalSec: Int = 1,
) {
    companion object {
        private const val TAG = "VideoEncoder"
        const val TIMEOUT_US = 10_000L

        /** 优先选硬件加速编码器;找不到(或系统版本过低)回退默认选择。 */
        private fun createHardwareEncoder(mime: String): MediaCodec {
            if (Build.VERSION.SDK_INT >= 29) {
                val hw = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                    info.isEncoder && !info.isSoftwareOnly &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
                if (hw != null) {
                    Log.i(TAG, "using encoder: ${hw.name}")
                    return MediaCodec.createByCodecName(hw.name)
                }
                Log.w(TAG, "no hardware encoder for $mime, fallback to default")
            }
            return MediaCodec.createEncoderByType(mime)
        }
    }

    interface Listener {
        fun onPacket(packet: EncodedPacket)
        /** 编码器输出格式确定时回调(含 csd-0/csd-1,封装 MP4 时必需)。 */
        fun onOutputFormat(format: MediaFormat)
        fun onError(t: Throwable)
    }

    var listener: Listener? = null

    private var codec: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    var inputSurface: Surface? = null
        private set

    @Volatile
    var outputFormat: MediaFormat? = null
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            // 限速:源 120Hz 高刷时,超过编码帧率的帧在入队前直接丢弃,不进编码器
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
            // 声明为后台(best-effort)任务,让编码器调度给游戏等前台负载让路
            setInteger(MediaFormat.KEY_PRIORITY, 1)
            // 部分机型需要显式码率模式
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
        }

        val c = createHardwareEncoder(mime)
        val thread = HandlerThread("VideoEncoder").apply { start() }
        val handler = Handler(thread.looper)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                try {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // csd 已随 format 下发,跳过
                        codec.releaseOutputBuffer(index, false)
                        return
                    }
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(data)
                            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            listener?.onPacket(EncodedPacket(data, info.presentationTimeUs, isKey))
                        }
                    }
                } catch (t: Throwable) {
                    listener?.onError(t)
                } finally {
                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                outputFormat = format
                listener?.onOutputFormat(format)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                listener?.onError(e)
            }
        }, handler)

        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
        handlerThread = thread
        running.set(true)
        Log.i(TAG, "started ${width}x${height}@${frameRate} bitrate=$bitrateBps mime=$mime")
    }

    /** 请求下一帧为关键帧(降级/恢复时用于快速对齐分片边界)。 */
    fun requestKeyFrame() {
        if (!running.get()) return
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "requestKeyFrame failed", t)
        }
    }

    /** 运行中调整码率(低电量/低内存降级用,API 19+)。 */
    fun adjustBitrate(bps: Int) {
        if (!running.get()) return
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            })
            Log.i(TAG, "bitrate adjusted to $bps")
        } catch (t: Throwable) {
            Log.w(TAG, "adjustBitrate failed", t)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            codec?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "codec stop", t)
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null
        handlerThread?.quitSafely()
        handlerThread = null
        Log.i(TAG, "stopped")
    }
}
