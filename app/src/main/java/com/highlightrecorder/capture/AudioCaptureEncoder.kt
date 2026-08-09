package com.highlightrecorder.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.highlightrecorder.buffer.EncodedPacket
import com.highlightrecorder.data.AudioSource
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频采集 + AAC 编码:内录(AudioPlaybackCapture, API 29+)、麦克风,
 * 或两者混音(INTERNAL_AND_MIC:两路 PCM 相加截幅后送同一编码器,输出单音轨)。
 * 单线程同时喂 PCM 输入与 drain AAC 输出;PTS 以 System.nanoTime 为基,
 * 与视频编码器的时间基准一致。
 *
 * 麦克风在部分设备上不支持立体声,会自动回退单声道并正确上混。
 */
class AudioCaptureEncoder(
    private val source: AudioSource,
    private val projection: MediaProjection?,
) {
    companion object {
        private const val TAG = "AudioCaptureEncoder"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val BITRATE = 128_000
        private const val MIME = "audio/mp4a-latm"
    }

    interface Listener {
        fun onPacket(packet: EncodedPacket)
        fun onOutputFormat(format: MediaFormat)
        fun onError(t: Throwable)
    }

    var listener: Listener? = null

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    var outputFormat: MediaFormat? = null
        private set

    private data class RecordChannel(val record: AudioRecord, val channels: Int)

    @SuppressLint("MissingPermission")
    fun start() {
        check(source != AudioSource.MUTE)
        val records = buildAudioRecords()
        val codec = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createAudioFormat(
            MIME, SAMPLE_RATE,
            if (CHANNELS == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
        ).apply {
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        records.forEach { it.record.startRecording() }
        running.set(true)

        thread = Thread {
            runLoop(records, codec)
        }.apply {
            name = "AudioCaptureEncoder"
            start()
        }
        Log.i(
            TAG,
            "audio started source=$source records=" +
                records.joinToString { "${it.channels}ch" },
        )
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.join(1500)
        thread = null
        Log.i(TAG, "audio stopped")
    }

    private fun runLoop(records: List<RecordChannel>, codec: MediaCodec) {
        val primary = records[0]
        val secondary = records.getOrNull(1)
        // 混音/上混暂存缓冲
        val mixBuf = ByteBuffer.allocateDirect(SAMPLE_RATE / 10 * CHANNELS * 2)

        val baseUs = System.nanoTime() / 1000
        var framesRead = 0L
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                // 喂输入
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    if (buf != null) {
                        val bytes = readPrimary(primary, buf, mixBuf)
                        if (bytes > 0) {
                            secondary?.let { mixSecondary(it, buf, bytes, mixBuf) }
                            val pts = baseUs + framesRead * 1_000_000L / SAMPLE_RATE
                            framesRead += bytes / 2 / CHANNELS
                            codec.queueInputBuffer(inIdx, 0, bytes, pts, 0)
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, baseUs, 0)
                        }
                    }
                }
                // drain 输出
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormat = codec.outputFormat
                            listener?.onOutputFormat(codec.outputFormat)
                        }
                        outIdx >= 0 -> {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                val buf = codec.getOutputBuffer(outIdx)
                                if (buf != null) {
                                    val data = ByteArray(info.size)
                                    buf.position(info.offset)
                                    buf.limit(info.offset + info.size)
                                    buf.get(data)
                                    listener?.onPacket(EncodedPacket(data, info.presentationTimeUs))
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                        else -> break
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) listener?.onError(t)
        } finally {
            records.forEach { rc ->
                try {
                    rc.record.stop()
                } catch (_: Throwable) {
                }
                rc.record.release()
            }
            try {
                codec.stop()
            } catch (_: Throwable) {
            }
            codec.release()
        }
    }

    /** 读主路到 [buf](编码器输入),单声道源自动上混为立体声。返回立体声字节数。 */
    private fun readPrimary(primary: RecordChannel, buf: ByteBuffer, mixBuf: ByteBuffer): Int {
        buf.clear()
        if (primary.channels == CHANNELS) {
            return primary.record.read(buf, buf.remaining())
        }
        // 单声道主路(纯麦克风且设备仅支持 mono):先读 mono 再扩展
        mixBuf.clear()
        val monoBytes = primary.record.read(mixBuf, minOf(mixBuf.capacity(), buf.remaining() / 2))
        return if (monoBytes > 0) PcmMixer.monoToStereo(buf, mixBuf, monoBytes) else monoBytes
    }

    /** 读第二路并混入 [buf] 中已采集的立体声数据。 */
    private fun mixSecondary(
        secondary: RecordChannel,
        buf: ByteBuffer,
        bytes: Int,
        mixBuf: ByteBuffer,
    ) {
        val want = if (secondary.channels == 1) {
            minOf(bytes / 2, mixBuf.capacity())
        } else {
            minOf(bytes, mixBuf.capacity())
        }
        mixBuf.clear()
        val n = try {
            secondary.record.read(mixBuf, want)
        } catch (t: Throwable) {
            Log.w(TAG, "secondary read failed", t)
            return
        }
        if (n <= 0) return
        if (secondary.channels == 1) {
            PcmMixer.mixMonoIntoStereo(buf, mixBuf, n, bytes)
        } else {
            PcmMixer.mixStereoInto(buf, mixBuf, n, bytes)
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecords(): List<RecordChannel> {
        val internal: RecordChannel? = when (source) {
            AudioSource.INTERNAL, AudioSource.INTERNAL_AND_MIC -> buildInternalRecord()
            else -> null
        }
        val mic: RecordChannel? = when (source) {
            AudioSource.MIC, AudioSource.INTERNAL_AND_MIC -> buildMicRecord()
            else -> null
        }
        // 混音时一路不可用则退化为另一路;权限缺失等异常由上层兜底
        val out = listOfNotNull(internal, mic)
        check(out.isNotEmpty()) { "no usable audio source" }
        if (source == AudioSource.INTERNAL_AND_MIC && out.size == 1) {
            Log.w(TAG, "mixed source degraded to single track")
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun buildInternalRecord(): RecordChannel? {
        if (Build.VERSION.SDK_INT < 29 || projection == null) {
            Log.w(TAG, "内录需要 Android 10+ 且持有 MediaProjection,跳过")
            return null
        }
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(pcmFormat(CHANNELS))
            .setBufferSizeInBytes(bufferSize(CHANNELS))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "internal record init failed")
            record.release()
            return null
        }
        return RecordChannel(record, CHANNELS)
    }

    @SuppressLint("MissingPermission")
    private fun buildMicRecord(): RecordChannel? {
        // 需 RECORD_AUDIO 权限,由调用方保证。立体声不被支持时回退单声道。
        for (ch in listOf(CHANNELS, 1)) {
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(pcmFormat(ch))
                .setBufferSizeInBytes(bufferSize(ch))
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                if (ch == 1) Log.i(TAG, "mic fallback to mono")
                return RecordChannel(record, ch)
            }
            Log.w(TAG, "mic record init failed for ${ch}ch")
            record.release()
        }
        return null
    }

    private fun pcmFormat(channels: Int): AudioFormat {
        val channelConfig = if (channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        return AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(channelConfig)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
    }

    private fun bufferSize(channels: Int): Int {
        val channelConfig = if (channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT,
        )
        return maxOf(minBuf * 2, SAMPLE_RATE / 10 * channels * 2)
    }
}
