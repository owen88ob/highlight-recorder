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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频采集 + AAC 编码:内录(AudioPlaybackCapture, API 29+)或麦克风。
 * 单线程同时喂 PCM 输入与 drain AAC 输出;PTS 以 System.nanoTime 为基,
 * 与视频编码器的时间基准一致。
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

    @SuppressLint("MissingPermission")
    fun start() {
        check(source != AudioSource.MUTE)
        val record = buildAudioRecord()
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
        record.startRecording()
        running.set(true)

        thread = Thread {
            runLoop(record, codec)
        }.apply {
            name = "AudioCaptureEncoder"
            start()
        }
        Log.i(TAG, "audio started source=$source")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.join(1500)
        thread = null
        Log.i(TAG, "audio stopped")
    }

    private fun runLoop(record: AudioRecord, codec: MediaCodec) {
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
                        buf.clear()
                        val bytes = record.read(buf, buf.remaining())
                        if (bytes > 0) {
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
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            record.release()
            try {
                codec.stop()
            } catch (_: Throwable) {
            }
            codec.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(): AudioRecord {
        val channelConfig = if (CHANNELS == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val pcmFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(channelConfig)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf * 2, SAMPLE_RATE / 10 * CHANNELS * 2)

        if (source == AudioSource.INTERNAL) {
            if (Build.VERSION.SDK_INT < 29 || projection == null) {
                throw UnsupportedOperationException("内录需要 Android 10+ 且持有 MediaProjection")
            }
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            return AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(pcmFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
        }
        // 麦克风(需 RECORD_AUDIO 权限,由调用方保证)
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(pcmFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }
}
