package com.highlightrecorder.buffer

import java.util.ArrayDeque

/**
 * 视频环形分片缓冲:按关键帧切成 Segment,只保留最近 [capacityUs] 的画面。
 *
 * - 分片边界以编码器实际输出的关键帧为准,不假设 I 帧间隔配置一定生效;
 * - 首个包若不是关键帧会被丢弃,直到遇到第一个 IDR 才开始记录;
 * - [snapshot] 返回的分片起点必为 IDR,可直接拼接。
 *
 * 线程安全:所有公共方法加锁,编码器回调线程与保存线程可并发访问。
 */
class RingSegmentBuffer(
    /** 保留时长上限(微秒),通常取 回退时长N + 2s 富余。 */
    private val capacityUs: Long,
) {
    private val lock = Any()
    private val segments = ArrayDeque<VideoSegment>()
    private var openSegment: VideoSegment? = null
    private var totalBytes: Int = 0

    /** 当前缓冲覆盖时长(微秒)。 */
    val bufferedDurationUs: Long
        get() = synchronized(lock) {
            val first = segments.peekFirst() ?: openSegment ?: return@synchronized 0L
            val last = openSegment ?: segments.peekLast() ?: return@synchronized 0L
            last.endPtsUs - first.startPtsUs
        }

    val bufferedBytes: Int get() = synchronized(lock) { totalBytes }

    val segmentCount: Int get() = synchronized(lock) { segments.size }

    fun onPacket(packet: EncodedPacket) {
        synchronized(lock) {
            if (packet.isKeyFrame) {
                openSegment?.let { segments.addLast(it) }
                openSegment = VideoSegment(packet.ptsUs)
                evictLocked()
            } else if (openSegment == null) {
                // 还没见到第一个关键帧,丢弃无法解码的头
                return
            }
            val seg = openSegment!!
            seg.append(packet)
            totalBytes += packet.size
        }
    }

    /**
     * 取最近 [windowUs] 微秒的分片快照(含正在写入的开放分片)。
     * 返回的列表按时间升序,首分片以 IDR 开头。
     */
    fun snapshot(windowUs: Long): List<VideoSegment> = synchronized(lock) {
        val all = segments.toMutableList()
        openSegment?.takeIf { it.packets.isNotEmpty() }?.let { all.add(it) }
        if (all.isEmpty()) return@synchronized emptyList()

        val newestEnd = all.last().endPtsUs
        val cutoff = newestEnd - windowUs
        val idx = all.indexOfFirst { it.endPtsUs >= cutoff }
        all.subList(if (idx < 0) 0 else idx, all.size).toList()
    }

    fun clear() = synchronized(lock) {
        segments.clear()
        openSegment = null
        totalBytes = 0
    }

    private fun evictLocked() {
        // 新分片加入后,若总跨度超容量,从头部逐出(至少保留 2 个分片防快照为空)
        while (segments.size > 2) {
            val first = segments.peekFirst() ?: break
            val newestEnd = openSegment?.startPtsUs ?: segments.peekLast()?.endPtsUs ?: break
            if (newestEnd - first.startPtsUs <= capacityUs) break
            totalBytes -= segments.removeFirst().sizeBytes
        }
    }
}
