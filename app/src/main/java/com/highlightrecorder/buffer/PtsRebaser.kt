package com.highlightrecorder.buffer

/**
 * 保存剪辑时的 PTS 重定基:以首个关键帧为 0,
 * 视频帧平移;音频帧先按视频起点截断再平移,保证音画对齐且时间戳从 0 单调递增。
 */
object PtsRebaser {

    fun rebaseVideo(segments: List<VideoSegment>): List<EncodedPacket> {
        if (segments.isEmpty()) return emptyList()
        val all = segments.flatMap { it.packets }.sortedBy { it.ptsUs }
        // 安全网:强制切段的分片可能不以关键帧开头,丢弃首个 IDR 之前的包,保证可解码
        val firstKey = all.indexOfFirst { it.isKeyFrame }
        if (firstKey < 0) return emptyList()
        val usable = all.subList(firstKey, all.size)
        val base = usable.first().ptsUs
        return usable.map { EncodedPacket(it.data, it.ptsUs - base, it.isKeyFrame) }
    }

    fun rebaseAudio(audio: List<EncodedPacket>, videoSegments: List<VideoSegment>): List<EncodedPacket> {
        if (videoSegments.isEmpty()) return emptyList()
        // 与 rebaseVideo 同一基准:首个关键帧的 PTS
        val all = videoSegments.flatMap { it.packets }.sortedBy { it.ptsUs }
        val base = all.firstOrNull { it.isKeyFrame }?.ptsUs ?: return emptyList()
        return audio.filter { it.ptsUs >= base }
            .sortedBy { it.ptsUs }
            .map { EncodedPacket(it.data, it.ptsUs - base, false) }
    }
}
