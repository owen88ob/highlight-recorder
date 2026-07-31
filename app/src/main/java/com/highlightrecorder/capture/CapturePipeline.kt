package com.highlightrecorder.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Display
import com.highlightrecorder.buffer.AudioRingBuffer
import com.highlightrecorder.buffer.EncodedPacket
import com.highlightrecorder.buffer.RingSegmentBuffer
import com.highlightrecorder.buffer.VideoSegment
import com.highlightrecorder.data.AudioSource
import com.highlightrecorder.data.RecordingSettings

/**
 * 采集管线:MediaProjection → VirtualDisplay → VideoEncoder → 环形缓冲。
 * 音频(可选)经 [AudioCaptureEncoder] → 音频环形缓冲。
 */
class CapturePipeline(
    private val context: Context,
    private val settings: RecordingSettings,
    private val projection: MediaProjection,
) {
    companion object {
        private const val TAG = "CapturePipeline"
        private const val SLACK_SECONDS = 2
    }

    interface Listener {
        fun onError(t: Throwable)
        /** 编码器输出格式就绪(含 csd),保存剪辑需要。 */
        fun onVideoFormat(format: MediaFormat)
    }

    var listener: Listener? = null

    val videoBuffer = RingSegmentBuffer((settings.rewindSeconds + SLACK_SECONDS) * 1_000_000L)
    val audioBuffer = AudioRingBuffer((settings.rewindSeconds + SLACK_SECONDS) * 1_000_000L)

    @Volatile
    var videoFormat: MediaFormat? = null
        private set

    @Volatile
    var audioFormat: MediaFormat? = null
        private set

    private var encoder: VideoEncoder? = null
    private var audioEncoder: AudioCaptureEncoder? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    var running = false
        private set

    fun start() {
        check(!running) { "pipeline already running" }
        val (w, h) = resolveSize()
        val enc = VideoEncoder(
            width = w,
            height = h,
            frameRate = settings.frameRate,
            bitrateBps = settings.videoBitrateBps,
            mime = settings.videoMime,
        )
        enc.listener = object : VideoEncoder.Listener {
            override fun onPacket(packet: EncodedPacket) = videoBuffer.onPacket(packet)

            override fun onOutputFormat(format: MediaFormat) {
                videoFormat = format
                listener?.onVideoFormat(format)
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "video encoder error", t)
                listener?.onError(t)
            }
        }
        enc.start()
        encoder = enc

        if (settings.audioSource != AudioSource.MUTE) {
            try {
                val audio = AudioCaptureEncoder(settings.audioSource, projection)
                audio.listener = object : AudioCaptureEncoder.Listener {
                    override fun onPacket(packet: EncodedPacket) = audioBuffer.onPacket(packet)

                    override fun onOutputFormat(format: MediaFormat) {
                        audioFormat = format
                    }

                    override fun onError(t: Throwable) {
                        Log.w(TAG, "audio error, continue without audio", t)
                    }
                }
                audio.start()
                audioEncoder = audio
            } catch (t: Throwable) {
                Log.w(TAG, "audio capture unavailable, continue muted", t)
            }
        }

        val surface = enc.inputSurface ?: error("input surface null")
        virtualDisplay = projection.createVirtualDisplay(
            "highlight-recorder",
            w, h,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null,
        )
        running = true
        Log.i(TAG, "pipeline started ${w}x$h audio=${settings.audioSource}")
    }

    /** 取最近 [windowUs] 的视频分片与对齐音频帧快照。 */
    fun snapshot(windowUs: Long): Pair<List<VideoSegment>, List<EncodedPacket>> =
        videoBuffer.snapshot(windowUs) to audioBuffer.snapshot(0L)

    fun requestKeyFrame() = encoder?.requestKeyFrame()

    /** 运行中调整码率(降级用)。 */
    fun adjustBitrate(bps: Int) = encoder?.adjustBitrate(bps)

    fun stop() {
        if (!running) return
        running = false
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release virtual display", t)
        }
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        audioEncoder?.stop()
        audioEncoder = null
        videoBuffer.clear()
        audioBuffer.clear()
        Log.i(TAG, "pipeline stopped")
    }

    /** 按设置解析输出分辨率,保持屏幕宽高比,边长取偶。 */
    private fun resolveSize(): Pair<Int, Int> {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val real = android.graphics.Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(real)
        var sw = if (real.x > 0) real.x else context.resources.displayMetrics.widthPixels
        var sh = if (real.y > 0) real.y else context.resources.displayMetrics.heightPixels
        if (sw <= 0 || sh <= 0) {
            sw = 1080; sh = 2400
        }

        if (settings.resolutionShortEdge <= 0) {
            return even(sw) to even(sh)
        }
        val target = settings.resolutionShortEdge
        val (w, h) = if (sw <= sh) {
            target to (target.toLong() * sh / sw).toInt()
        } else {
            (target.toLong() * sw / sh).toInt() to target
        }
        return even(w) to even(h)
    }

    private fun even(v: Int): Int = v - (v % 2)
}
