package com.highlightrecorder.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import com.highlightrecorder.buffer.AudioRingBuffer
import com.highlightrecorder.buffer.EncodedPacket
import com.highlightrecorder.buffer.RingSegmentBuffer
import com.highlightrecorder.buffer.VideoSegment
import com.highlightrecorder.data.AudioSource
import com.highlightrecorder.data.FileLogger
import com.highlightrecorder.data.RecordingSettings

/**
 * 采集管线:MediaProjection → VirtualDisplay → VideoEncoder → 环形缓冲。
 * 音频(可选)经 [AudioCaptureEncoder] → 音频环形缓冲。
 *
 * 分辨率遵循当前屏幕旋转方向(横屏游戏录横屏);旋转变化时自动重启编码器
 * (缓冲清空, GOP 边界处无缝继续);超出编码器能力上限时自动等比缩小。
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
        /** 屏幕旋转导致编码器重启(缓冲已清空)。 */
        fun onRotationChanged()
        /** 音频采集初始化失败或运行中出错(录制继续,但成品无声)。 */
        fun onAudioUnavailable(reason: String)
    }

    var listener: Listener? = null

    val videoBuffer = RingSegmentBuffer(
        capacityUs = (settings.rewindSeconds + SLACK_SECONDS) * 1_000_000L,
        // 字节安全阀按码率动态计算:预估(码率×保留时长)的 1.5 倍,保底 300MB、封顶 768MB。
        // 固定 300MB 在 2K@120fps 高码率下会被 VBR 峰值触发,把 60s 回退截短成 40~50s。
        maxBytes = minOf(
            768L * 1024 * 1024,
            maxOf(
                300L * 1024 * 1024,
                settings.videoBitrateBps / 8L * (settings.rewindSeconds + SLACK_SECONDS) * 3 / 2,
            ),
        ).toInt(),
    ).apply {
        onByteBudgetEvict = { total ->
            FileLogger.log(TAG, "byte budget evict: buffer=${total / 1024 / 1024}MB, VBR 峰值超预估,回退时长被临时缩短")
        }
    }
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
    private var lastRotation: Int = Surface.ROTATION_0

    /** 最近一次收到视频编码包的时间(nanoTime),看门狗用。 */
    @Volatile
    private var lastVideoPacketNs = 0L

    /** 上次编码器重启时间(ms),限频防止显示事件风暴导致反复重启。 */
    @Volatile
    private var lastRestartMs = 0L

    @Volatile
    var running = false
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running) return
            val dm = context.getSystemService(DisplayManager::class.java)
            val rot = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
            FileLogger.log(TAG, "onDisplayChanged rot=$rot last=$lastRotation running=$running")
            if (rot != lastRotation) {
                lastRotation = rot
                // 0°↔180° 这类分辨率不变的旋转直接跳过,不必重启编码器
                val (w, h) = resolveSize()
                if (w == currentWidth && h == currentHeight) {
                    FileLogger.log(TAG, "rotation changed but size unchanged, skip restart")
                    return
                }
                FileLogger.log(TAG, "rotation changed, restarting encoder ${w}x$h")
                restartEncoder()
            }
        }
    }

    fun start() {
        check(!running) { "pipeline already running" }
        lastRotation = currentRotation()
        startVideo()
        startAudio()
        running = true
        context.getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        FileLogger.log(TAG, "pipeline started audio=${settings.audioSource} size=${currentWidth}x$currentHeight rotation=$lastRotation")
    }

    /** 取最近 [windowUs] 的视频分片与对齐音频帧快照。 */
    fun snapshot(windowUs: Long): Pair<List<VideoSegment>, List<EncodedPacket>> =
        videoBuffer.snapshot(windowUs) to audioBuffer.snapshot(0L)

    fun requestKeyFrame() = encoder?.requestKeyFrame()

    /** 运行中调整码率(降级用)。 */
    fun adjustBitrate(bps: Int) = encoder?.adjustBitrate(bps)

    /** 画面采集是否已停滞([stallMs] 毫秒内没有任何编码包)。 */
    fun videoStalled(stallMs: Long = 3_000): Boolean {
        if (!running) return false
        val last = lastVideoPacketNs
        if (last <= 0) return false
        return (System.nanoTime() - last) / 1_000_000 > stallMs
    }

    /**
     * 看门狗触发的编码器恢复:重启编码器(复用 VirtualDisplay)。
     * 限频 10 秒一次,防止显示事件风暴下反复重启把缓冲反复清空。
     * @return true 表示本次执行了重启
     */
    @Synchronized
    fun recoverEncoder(): Boolean {
        if (!running || virtualDisplay == null) return false
        val now = System.currentTimeMillis()
        if (now - lastRestartMs < 10_000) return false
        lastRestartMs = now
        FileLogger.log(TAG, "watchdog recovery: restarting encoder")
        restartEncoder()
        return true
    }

    /** 旋转变化:重建编码器,复用原 VirtualDisplay(Android 14+ 同一投影实例禁止二次创建)。 */
    @Synchronized
    private fun restartEncoder() {
        val vd = virtualDisplay
        if (vd == null) {
            FileLogger.log(TAG, "restart without virtual display, ignored")
            return
        }
        FileLogger.log(TAG, "restartEncoder begin")
        encoder?.stop()
        encoder = null
        videoBuffer.clear()
        videoFormat = null
        try {
            val enc = createEncoder()
            encoder = enc
            // 换绑到新编码器输入面,不新建 VirtualDisplay
            vd.resize(currentWidth, currentHeight, context.resources.displayMetrics.densityDpi)
            vd.setSurface(enc.inputSurface)
            // 宽限期:重启后短时间内看门狗不应误报
            lastVideoPacketNs = System.nanoTime()
            lastRestartMs = System.currentTimeMillis()
            FileLogger.log(TAG, "encoder restarted ${currentWidth}x$currentHeight rotation=$lastRotation")
        } catch (t: Throwable) {
            FileLogger.log(TAG, "restart encoder failed", t)
            listener?.onError(t)
            return
        }
        listener?.onRotationChanged()
    }

    private var currentWidth = 0
    private var currentHeight = 0

    /** 创建并启动视频编码器(尺寸按当前旋转与编码器能力解析)。 */
    private fun createEncoder(): VideoEncoder {
        val (w, h) = resolveSize()
        currentWidth = w
        currentHeight = h
        val enc = VideoEncoder(
            width = w,
            height = h,
            frameRate = settings.frameRate,
            bitrateBps = settings.videoBitrateBps,
            mime = settings.videoMime,
        )
        enc.listener = object : VideoEncoder.Listener {
            override fun onPacket(packet: EncodedPacket) {
                lastVideoPacketNs = System.nanoTime()
                videoBuffer.onPacket(packet)
            }

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
        // 编码器不发周期 IDR 时,缓冲请求强制关键帧
        videoBuffer.onSegmentOverrun = { enc.requestKeyFrame() }
        return enc
    }

    private fun startVideo() {
        val enc = createEncoder()
        encoder = enc
        lastVideoPacketNs = System.nanoTime()
        // 首次启动才创建 VirtualDisplay(同一 MediaProjection 只能建一个)
        virtualDisplay = projection.createVirtualDisplay(
            "highlight-recorder",
            currentWidth, currentHeight,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            enc.inputSurface, null, null,
        )
        Log.i(TAG, "video started ${currentWidth}x$currentHeight rotation=$lastRotation")
    }

    private fun startAudio() {
        if (settings.audioSource == AudioSource.MUTE) return
        try {
            val audio = AudioCaptureEncoder(settings.audioSource, projection)
            audio.listener = object : AudioCaptureEncoder.Listener {
                override fun onPacket(packet: EncodedPacket) = audioBuffer.onPacket(packet)

                override fun onOutputFormat(format: MediaFormat) {
                    audioFormat = format
                }

                override fun onError(t: Throwable) {
                    FileLogger.log(TAG, "audio error, continue without audio: ${t.message}")
                    Log.w(TAG, "audio error, continue without audio", t)
                    listener?.onAudioUnavailable("运行中音频出错: ${t.message ?: t.javaClass.simpleName}")
                }
            }
            audio.start()
            audioEncoder = audio
        } catch (t: Throwable) {
            FileLogger.log(TAG, "audio capture unavailable, continue muted: ${t.message}")
            Log.w(TAG, "audio capture unavailable, continue muted", t)
            listener?.onAudioUnavailable("音频初始化失败: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.unregisterDisplayListener(displayListener)
        }
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
        FileLogger.log(TAG, "pipeline stopped")
    }

    private fun currentRotation(): Int {
        val dm = context.getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        return dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    /**
     * 按设置与当前方向解析输出分辨率,保持屏幕宽高比,
     * 并收敛到编码器能力范围内(部分芯片长边上限 1920,超限 configure 直接失败)。
     *
     * 注意:Display.getRealSize() 返回的已是**当前方向**的尺寸(系统已随旋转交换),
     * 不能再按 rotation 手动交换——否则横屏会被二次旋转成竖屏尺寸(本 bug 的教训:
     * 双重旋转导致横竖屏算出同一尺寸,旋转检测被误判为"无变化"而跳过编码器重启)。
     */
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

        val (w, h) = if (settings.resolutionShortEdge <= 0) {
            even(sw) to even(sh)
        } else {
            // 目标短边不超过屏幕实际短边,避免无谓放大(如 4K 档位用于 1080p 屏)
            val target = minOf(settings.resolutionShortEdge, minOf(sw, sh))
            if (sw <= sh) {
                target to (target.toLong() * sh / sw).toInt()
            } else {
                (target.toLong() * sw / sh).toInt() to target
            }.let { even(it.first) to even(it.second) }
        }
        return clampToEncoderCaps(w, h)
    }

    /** 等比缩小到编码器支持的尺寸(含对齐约束);完全不支持时保底 720p。 */
    private fun clampToEncoderCaps(w: Int, h: Int): Pair<Int, Int> {
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(settings.videoMime, ignoreCase = true) }
        } ?: return w to h
        val caps = runCatching {
            info.getCapabilitiesForType(settings.videoMime).videoCapabilities
        }.getOrNull() ?: return w to h

        val wa = caps.widthAlignment
        val ha = caps.heightAlignment
        var cw = w / wa * wa
        var ch = h / ha * ha
        var guard = 0
        while (!caps.isSizeSupported(cw, ch) && guard++ < 40 && cw > 320 && ch > 320) {
            cw = (cw * 9 / 10) / wa * wa
            ch = (ch * 9 / 10) / ha * ha
        }
        if (!caps.isSizeSupported(cw, ch)) {
            Log.w(TAG, "encoder caps unsupported even after clamp, fallback 1280x720")
            return 1280 to 720
        }
        if (cw != w || ch != h) {
            Log.w(TAG, "size clamped to encoder caps: ${w}x$h -> ${cw}x$ch")
        }
        return cw to ch
    }

    private fun even(v: Int): Int = v - (v % 2)
}
