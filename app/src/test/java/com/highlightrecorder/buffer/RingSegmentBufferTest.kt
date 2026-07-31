package com.highlightrecorder.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingSegmentBufferTest {

    private fun feed(
        buffer: RingSegmentBuffer,
        seconds: Int,
        fps: Int = 30,
        startPtsUs: Long = 0L,
        packetSize: Int = 100,
    ) {
        val frameDurUs = 1_000_000L / fps
        for (s in 0 until seconds) {
            for (f in 0 until fps) {
                val pts = startPtsUs + s * 1_000_000L + f * frameDurUs
                buffer.onPacket(EncodedPacket(ByteArray(packetSize), pts, isKeyFrame = f == 0))
            }
        }
    }

    @Test
    fun `首个包非关键帧时被丢弃直到 IDR`() {
        val buf = RingSegmentBuffer(capacityUs = 10_000_000)
        buf.onPacket(EncodedPacket(ByteArray(10), ptsUs = 0, isKeyFrame = false))
        buf.onPacket(EncodedPacket(ByteArray(10), ptsUs = 33_000, isKeyFrame = false))
        assertEquals(0, buf.segmentCount)
        buf.onPacket(EncodedPacket(ByteArray(10), ptsUs = 66_000, isKeyFrame = true))
        feed(buf, seconds = 1, startPtsUs = 1_000_000)
        assertTrue(buf.segmentCount >= 1)
    }

    @Test
    fun `超出容量后旧分片被逐出`() {
        val buf = RingSegmentBuffer(capacityUs = 5_000_000)
        feed(buf, seconds = 20, packetSize = 1000)
        // 跨度应 <= 容量 + 一个分片的余量
        assertTrue("跨度 ${buf.bufferedDurationUs}", buf.bufferedDurationUs <= 7_000_000)
        assertTrue(buf.segmentCount in 2..7)
    }

    @Test
    fun `快照起点必为关键帧且覆盖窗口`() {
        val buf = RingSegmentBuffer(capacityUs = 30_000_000)
        feed(buf, seconds = 20)
        val snap = buf.snapshot(windowUs = 5_000_000)
        assertTrue(snap.isNotEmpty())
        assertTrue(snap.first().packets.first().isKeyFrame)
        val covered = snap.last().endPtsUs - snap.first().startPtsUs
        assertTrue("覆盖 $covered", covered >= 5_000_000 - 33_333)
        assertTrue(covered < 7_000_000)
    }

    @Test
    fun `快照期间继续写入不受影响`() {
        val buf = RingSegmentBuffer(capacityUs = 30_000_000)
        feed(buf, seconds = 10)
        val snap = buf.snapshot(5_000_000)
        val before = snap.last().endPtsUs
        feed(buf, seconds = 2, startPtsUs = 10_000_000)
        // 快照内容(引用拷贝)仍有效,缓冲继续前进
        assertEquals(before, snap.last().endPtsUs)
        assertTrue(buf.bufferedDurationUs >= 5_000_000)
    }

    @Test
    fun `缓冲为空时快照为空`() {
        val buf = RingSegmentBuffer(capacityUs = 10_000_000)
        assertTrue(buf.snapshot(1_000_000).isEmpty())
    }
}
