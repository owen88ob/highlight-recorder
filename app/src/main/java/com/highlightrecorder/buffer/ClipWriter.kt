package com.highlightrecorder.buffer

import android.content.ContentValues
import android.content.Context
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把环形缓冲的快照封装为 MP4 写入 Movies/高光回录/。
 * 视频从 IDR 分片边界开始拼接,PTS 重定基为 0,保证可解码不花屏。
 */
object ClipWriter {
    private const val TAG = "ClipWriter"
    const val SUB_DIR = "高光回录"

    class Result(
        val uri: Uri,
        val durationMs: Long,
        val sizeBytes: Long,
    )

    /**
     * 同步写出(请在 IO 线程调用)。
     * [videoFormat] 为编码器输出格式(含 csd);[audioFormat] 为空则只写视频轨。
     */
    fun write(
        context: Context,
        video: List<VideoSegment>,
        audio: List<EncodedPacket>,
        videoFormat: MediaFormat,
        audioFormat: MediaFormat?,
    ): Result {
        require(video.isNotEmpty()) { "no video segments" }

        val name = "高光_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        // 目标:API 29+ 走 MediaStore(PENDING),26-28 写公共目录
        var legacyFile: File? = null
        var pendingUri: Uri? = null
        val muxer: MediaMuxer

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SUB_DIR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values,
            ) ?: error("MediaStore insert failed")
            pendingUri = uri
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: error("open fd failed")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            pfd.close()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                SUB_DIR,
            ).apply { mkdirs() }
            val file = File(dir, name)
            legacyFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        var ok = false
        try {
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = if (audioFormat != null && audio.isNotEmpty()) {
                muxer.addTrack(audioFormat)
            } else {
                -1
            }
            muxer.start()

            val info = android.media.MediaCodec.BufferInfo()
            val videoPackets = PtsRebaser.rebaseVideo(video)
            for (p in videoPackets) {
                info.set(0, p.size, p.ptsUs, if (p.isKeyFrame) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(videoTrack, java.nio.ByteBuffer.wrap(p.data), info)
            }
            if (audioTrack >= 0) {
                val audioPackets = PtsRebaser.rebaseAudio(audio, video)
                for (p in audioPackets) {
                    info.set(0, p.size, p.ptsUs, 0)
                    muxer.writeSampleData(audioTrack, java.nio.ByteBuffer.wrap(p.data), info)
                }
            }
            muxer.stop()
            ok = true

            val last = videoPackets.last()
            val durationMs = last.ptsUs / 1000

            if (pendingUri != null && Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(pendingUri, done, null, null)
                val size = legacyFile?.length() ?: querySize(context, pendingUri)
                Log.i(TAG, "clip saved: $pendingUri ${durationMs}ms")
                return Result(pendingUri, durationMs, size)
            }
            val file = legacyFile!!
            val uri = Uri.fromFile(file)
            Log.i(TAG, "clip saved: $file ${durationMs}ms")
            return Result(uri, durationMs, file.length())
        } finally {
            try {
                muxer.release()
            } catch (_: Throwable) {
            }
            if (!ok) {
                // 失败清理半成品
                try {
                    pendingUri?.let { context.contentResolver.delete(it, null, null) }
                    legacyFile?.delete()
                } catch (t: Throwable) {
                    Log.w(TAG, "cleanup failed", t)
                }
            }
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }
}
