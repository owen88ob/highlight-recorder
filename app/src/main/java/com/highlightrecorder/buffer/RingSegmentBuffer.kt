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
    /** 开放分片超过该时长未见到关键帧时,回调 [onSegmentOverrun] 请求编码器补 IDR。 */
    private val softSegmentUs: Long = 1_600_000L,
    /** 编码器始终不给关键帧时的硬上限:强制切段,防止内存无限增长(OOM)。 */
    private val hardSegmentUs: Long = 4_000_000L,
    /** 缓冲总字节安全阀:高码率+长回退时超出则从头部逐出(缩短实际可回退时长,防 OOM)。 */
    private val maxBytes: Int = 300 * 1024 * 1024,
) {
    /** 分片超时回调(由管线接到编码器 requestKeyFrame)。 */
    var onSegmentOverrun: (() -> Unit)? = null

    /** 字节安全阀逐出回调(诊断用,记录实际码率超过预估的场景)。 */
    var onByteBudgetEvict: ((totalBytes: Int) -> Unit)? = null

    private val lock = Any()
    private val segments = ArrayDeque<VideoSegment>()
    private var openSegment: VideoSegment? = null
    private var totalBytes: Int = 0
    private var lastOverrunNotifyUs = 0L

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
            } else {
                val seg = openSegment!!
                val span = packet.ptsUs - seg.startPtsUs
                if (span > hardSegmentUs) {
                    // 编码器迟迟不发 IDR:强制切段兜底,否则内存无限增长
                    segments.addLast(seg)
                    openSegment = VideoSegment(packet.ptsUs)
                    evictLocked()
                } else if (span > softSegmentUs &&
                    packet.ptsUs - lastOverrunNotifyUs > 1_000_000L
                ) {
                    lastOverrunNotifyUs = packet.ptsUs
                    onSegmentOverrun?.invoke()
                }
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
        // 新分片加入后,若总跨度超容量,从头部逐出(至少留 1 个已封闭分片)
        while (segments.size > 1) {
            val first = segments.peekFirst() ?: break
            val newestEnd = openSegment?.startPtsUs ?: segments.peekLast()?.endPtsUs ?: break
            if (newestEnd - first.startPtsUs <= capacityUs) break
            totalBytes -= segments.removeFirst().sizeBytes
        }
        // 字节安全阀:超出预算同样从头部逐出(至少留 1 个)
        while (segments.size > 1 && totalBytes > maxBytes) {
            totalBytes -= segments.removeFirst().sizeBytes
            onByteBudgetEvict?.invoke(totalBytes)
        }
    }
}
