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

    @Test
    fun `编码器不发关键帧时分片超时回调`() {
        val buf = RingSegmentBuffer(capacityUs = 60_000_000)
        var overruns = 0
        buf.onSegmentOverrun = { overruns++ }
        // 只在开头给一个 IDR,之后 20 秒全是普通帧(模拟不守 I 帧间隔的编码器)
        buf.onPacket(EncodedPacket(ByteArray(100), 0, isKeyFrame = true))
        for (i in 1..600) {
            buf.onPacket(EncodedPacket(ByteArray(100), i * 33_333L, isKeyFrame = false))
        }
        assertTrue("应触发超时回调", overruns >= 1)
    }

    @Test
    fun `编码器不发关键帧时内存仍被容量约束`() {
        val buf = RingSegmentBuffer(capacityUs = 5_000_000)
        buf.onPacket(EncodedPacket(ByteArray(100), 0, isKeyFrame = true))
        for (i in 1..600) {
            buf.onPacket(EncodedPacket(ByteArray(100), i * 33_333L, isKeyFrame = false))
        }
        // 强制切段(4s 硬上限)+ 容量逐出:跨度应有界
        assertTrue(
            "跨度 ${buf.bufferedDurationUs}",
            buf.bufferedDurationUs <= 5_000_000 + 4_000_000 + 1_000_000,
        )
        assertTrue(buf.segmentCount >= 1)
    }

    @Test
    fun `字节安全阀逐出旧分片并回调`() {
        // 容量很大但字节预算小:应触发字节安全阀而不是时长逐出
        val buf = RingSegmentBuffer(capacityUs = 600_000_000, maxBytes = 10_000)
        var evictions = 0
        buf.onByteBudgetEvict = { evictions++ }
        // 每秒一个分片,每片 30 帧 × 100B = 3000B;4 秒即超 10000B 预算
        feed(buf, seconds = 10, packetSize = 100)
        assertTrue("字节 ${buf.bufferedBytes}", buf.bufferedBytes <= 10_000 + 3_000)
        assertTrue("应触发字节安全阀回调", evictions >= 1)
        assertTrue("至少保留一个分片", buf.segmentCount >= 1)
    }
}
