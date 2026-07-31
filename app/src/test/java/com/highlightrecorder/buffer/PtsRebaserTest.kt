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
}
