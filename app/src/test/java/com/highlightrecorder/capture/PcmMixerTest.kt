package com.highlightrecorder.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmMixerTest {

    private fun bufOf(vararg shorts: Int): ByteBuffer {
        val b = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { b.putShort(it.toShort()) }
        return b
    }

    private fun shorts(b: ByteBuffer, count: Int): List<Int> =
        (0 until count).map { b.getShort(it * 2).toInt() }

    @Test
    fun `立体声混音相加且不改变原始字节序`() {
        val dst = bufOf(100, -200, 3000, -4000)
        val src = bufOf(50, -50, 1000, 1000)
        val n = PcmMixer.mixStereoInto(dst, src, 8, 8)
        assertEquals(8, n)
        assertEquals(listOf(150, -250, 4000, -3000), shorts(dst, 4))
    }

    @Test
    fun `混音截幅不溢出`() {
        val dst = bufOf(30000, -30000)
        val src = bufOf(10000, -10000)
        PcmMixer.mixStereoInto(dst, src, 4, 4)
        assertEquals(listOf(32767, -32768), shorts(dst, 2))
    }

    @Test
    fun `单声道混入立体声两声道同加`() {
        val dst = bufOf(1000, 2000, -1000, -2000) // 2 帧立体声
        val src = bufOf(500, -500) // 2 帧单声道
        PcmMixer.mixMonoIntoStereo(dst, src, 4, 8)
        assertEquals(listOf(1500, 2500, -1500, -2500), shorts(dst, 4))
    }

    @Test
    fun `单声道扩展立体声`() {
        val src = bufOf(123, -456, 789)
        val dst = ByteBuffer.allocate(16)
        val bytes = PcmMixer.monoToStereo(dst, src, 6)
        assertEquals(12, bytes)
        assertEquals(listOf(123, 123, -456, -456, 789, 789), shorts(dst, 6))
    }

    @Test
    fun `源短于目标时只混有效部分`() {
        val dst = bufOf(1, 2, 3, 4)
        val src = bufOf(10, 20)
        val n = PcmMixer.mixStereoInto(dst, src, 4, 8)
        assertEquals(4, n)
        assertEquals(listOf(11, 22, 3, 4), shorts(dst, 4))
    }
}
