package com.highlightrecorder.buffer

import java.util.ArrayDeque

/**
 * 音频(AAC)环形缓冲:AAC 帧互相独立可解码,按帧逐出即可,
 * 保存时按视频起点 PTS 截断,允许毫秒级对齐误差。
 */
class AudioRingBuffer(
    private val capacityUs: Long,
) {
    private val lock = Any()
    private val packets = ArrayDeque<EncodedPacket>()
    private var totalBytes: Int = 0

    val bufferedBytes: Int get() = synchronized(lock) { totalBytes }

    val packetCount: Int get() = synchronized(lock) { packets.size }

    fun onPacket(packet: EncodedPacket) {
        synchronized(lock) {
            packets.addLast(packet)
            totalBytes += packet.size
            val newest = packet.ptsUs
            while (packets.size > 1) {
                val first = packets.peekFirst() ?: break
                if (newest - first.ptsUs <= capacityUs) break
                totalBytes -= packets.removeFirst().size
            }
        }
    }

    /** 取 pts >= [fromPtsUs] 的音频帧(升序)。 */
    fun snapshot(fromPtsUs: Long): List<EncodedPacket> = synchronized(lock) {
        packets.filter { it.ptsUs >= fromPtsUs }
    }

    fun clear() = synchronized(lock) {
        packets.clear()
        totalBytes = 0
    }
}
