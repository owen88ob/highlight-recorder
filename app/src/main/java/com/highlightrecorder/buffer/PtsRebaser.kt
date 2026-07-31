package com.highlightrecorder.buffer

/**
 * 保存剪辑时的 PTS 重定基:以首段视频起点为 0,
 * 视频帧平移;音频帧先按视频起点截断再平移,保证音画对齐且时间戳从 0 单调递增。
 */
object PtsRebaser {

    fun rebaseVideo(segments: List<VideoSegment>): List<EncodedPacket> {
        if (segments.isEmpty()) return emptyList()
        val base = segments.first().startPtsUs
        return segments.flatMap { it.packets }
            .sortedBy { it.ptsUs }
            .map { EncodedPacket(it.data, it.ptsUs - base, it.isKeyFrame) }
    }

    fun rebaseAudio(audio: List<EncodedPacket>, videoSegments: List<VideoSegment>): List<EncodedPacket> {
        if (videoSegments.isEmpty()) return emptyList()
        val base = videoSegments.first().startPtsUs
        return audio.filter { it.ptsUs >= base }
            .sortedBy { it.ptsUs }
            .map { EncodedPacket(it.data, it.ptsUs - base, false) }
    }
}
