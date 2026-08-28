package com.highlightrecorder.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PtsRebaserTest {

    private fun makeSegments(): List<VideoSegment> {
        // 两个分片,起点 5s,每段 1s,30fps
        val out = ArrayList<VideoSegment>()
        for (s in 0 until 2) {
            val seg = VideoSegment(5_000_000L + s * 1_000_000L)
            for (f in 0 until 30) {
                seg.append(
                    EncodedPacket(
                        ByteArray(10),
                        5_000_000L + s * 1_000_000L + f * 33_333L,
                        isKeyFrame = f == 0,
                    )
                )
            }
            out.add(seg)
        }
        return out
    }

    @Test
    fun `视频重定基后首帧为 0 且单调递增`() {
        val rebased = PtsRebaser.rebaseVideo(makeSegments())
        assertEquals(0L, rebased.first().ptsUs)
        assertTrue(rebased.zipWithNext().all { (a, b) -> b.ptsUs >= a.ptsUs })
        assertEquals(60, rebased.size)
    }

    @Test
    fun `音频按视频起点截断并重定基`() {
        val audio = (0 until 300).map {
            EncodedPacket(ByteArray(10), 4_500_000L + it * 21_333L)
        }
        val rebased = PtsRebaser.rebaseAudio(audio, makeSegments())
        assertTrue(rebased.isNotEmpty())
        assertTrue(rebased.first().ptsUs >= 0)
        assertTrue(rebased.all { it.ptsUs >= 0 })
        assertTrue(rebased.zipWithNext().all { (a, b) -> b.ptsUs >= a.ptsUs })
    }

    @Test
    fun `空输入安全`() {
        assertTrue(PtsRebaser.rebaseVideo(emptyList()).isEmpty())
        assertTrue(PtsRebaser.rebaseAudio(listOf(EncodedPacket(ByteArray(1), 0)), emptyList()).isEmpty())
    }

    @Test
    fun `重定基丢弃首个关键帧之前的包(强制切段安全网)`() {
        // 第一个分片以非关键帧开头(强制切段产生),第二个分片正常
        val bad = VideoSegment(1_000_000L)
        bad.append(EncodedPacket(ByteArray(10), 1_000_000L, isKeyFrame = false))
        bad.append(EncodedPacket(ByteArray(10), 1_033_333L, isKeyFrame = false))
        val good = VideoSegment(2_000_000L)
        good.append(EncodedPacket(ByteArray(10), 2_000_000L, isKeyFrame = true))
        good.append(EncodedPacket(ByteArray(10), 2_033_333L, isKeyFrame = false))

        val rebased = PtsRebaser.rebaseVideo(listOf(bad, good))
        assertEquals(2, rebased.size)
        assertEquals(0L, rebased.first().ptsUs)
        assertTrue(rebased.first().isKeyFrame)

        // 完全没有关键帧时返回空(调用方判空处理)
        assertTrue(PtsRebaser.rebaseVideo(listOf(bad)).isEmpty())
    }
}
