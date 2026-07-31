package com.highlightrecorder.buffer

/**
 * 一段以关键帧(IDR)开头、到下一个关键帧之前的连续编码数据。
 * 任何拼接都必须从 Segment 边界开始,否则解码会花屏。
 */
class VideoSegment(
    val startPtsUs: Long,
) {
    private val packetsInternal = ArrayList<EncodedPacket>(64)

    /** 段内全部包(首包必为关键帧)。 */
    val packets: List<EncodedPacket> get() = packetsInternal

    var endPtsUs: Long = startPtsUs
        private set

    val durationUs: Long get() = endPtsUs - startPtsUs

    val sizeBytes: Int get() = packetsInternal.sumOf { it.size }

    fun append(packet: EncodedPacket) {
        packetsInternal.add(packet)
        if (packet.ptsUs > endPtsUs) endPtsUs = packet.ptsUs
    }
}
