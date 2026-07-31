package com.highlightrecorder.data

/** 录制参数,由设置页持久化(DataStore),服务启动时读取。 */
data class RecordingSettings(
    /** 回退时长(秒)。 */
    val rewindSeconds: Int = 30,
    /** 0=跟随屏幕, 720/1080=短边像素。 */
    val resolutionShortEdge: Int = 720,
    val frameRate: Int = 30,
    /** 码率 bps。 */
    val videoBitrateBps: Int = 8_000_000,
    /** "video/avc" 或 "video/hevc"。 */
    val videoMime: String = "video/avc",
    /** 音频来源。 */
    val audioSource: AudioSource = AudioSource.INTERNAL,
    val overlayAlpha: Float = 0.85f,
    val overlayScale: Float = 1.0f,
    val overlayEdgeHide: Boolean = false,
)

enum class AudioSource { INTERNAL, MIC, MUTE }
