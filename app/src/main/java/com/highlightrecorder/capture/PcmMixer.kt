package com.highlightrecorder.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 16bit 混音工具。Android PCM 一律 little-endian,操作前必须显式设置
 * [ByteOrder.LITTLE_ENDIAN](ByteBuffer 默认 big-endian,不设就是全频噪音)。
 */
object PcmMixer {

    /** 两路立体声混音:[src] 的 [srcBytes] 字节相加截幅混入 [dst](原位)。返回参与混音的字节数。 */
    fun mixStereoInto(dst: ByteBuffer, src: ByteBuffer, srcBytes: Int, dstBytes: Int): Int {
        dst.order(ByteOrder.LITTLE_ENDIAN)
        src.order(ByteOrder.LITTLE_ENDIAN)
        val samples = minOf(srcBytes, dstBytes) / 2
        for (i in 0 until samples) {
            val mixed = dst.getShort(i * 2).toInt() + src.getShort(i * 2).toInt()
            dst.putShort(i * 2, clamp16(mixed))
        }
        return samples * 2
    }

    /** 单声道混入立体声:每个 mono 采样同时加到左右声道。 */
    fun mixMonoIntoStereo(dst: ByteBuffer, src: ByteBuffer, srcBytes: Int, dstBytes: Int) {
        dst.order(ByteOrder.LITTLE_ENDIAN)
        src.order(ByteOrder.LITTLE_ENDIAN)
        val frames = minOf(srcBytes / 2, dstBytes / 4)
        for (f in 0 until frames) {
            val m = src.getShort(f * 2).toInt()
            val l = dst.getShort(f * 4).toInt()
            val r = dst.getShort(f * 4 + 2).toInt()
            dst.putShort(f * 4, clamp16(l + m))
            dst.putShort(f * 4 + 2, clamp16(r + m))
        }
    }

    /** 单声道扩展为立体声,返回扩展后字节数。[dst] 剩余容量须 ≥ [srcBytes]*2。 */
    fun monoToStereo(dst: ByteBuffer, src: ByteBuffer, srcBytes: Int): Int {
        dst.order(ByteOrder.LITTLE_ENDIAN)
        src.order(ByteOrder.LITTLE_ENDIAN)
        val frames = minOf(srcBytes / 2, dst.remaining() / 4)
        for (f in 0 until frames) {
            val s = src.getShort(f * 2)
            dst.putShort(f * 4, s)
            dst.putShort(f * 4 + 2, s)
        }
        return frames * 4
    }

    private fun clamp16(v: Int): Short = v.coerceIn(-32768, 32767).toShort()
}
