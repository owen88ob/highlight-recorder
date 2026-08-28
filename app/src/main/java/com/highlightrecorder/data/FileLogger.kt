package com.highlightrecorder.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量文件日志:写到应用私有外部目录(adb 可直接拉取),
 * 用于排查 OEM ROM 抑制 logcat 应用日志时的现场问题。上限 2MB,超出截半。
 */
object FileLogger {
    private const val MAX_BYTES = 2 * 1024 * 1024L
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null), "highlight.log")
    }

    @Synchronized
    fun log(tag: String, msg: String, t: Throwable? = null) {
        Log.i(tag, msg, t)
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val tail = f.readBytes().let { it.copyOfRange(it.size / 2, it.size) }
                f.writeBytes(tail)
            }
            val line = buildString {
                append(fmt.format(Date())).append(" ").append(tag).append(": ").append(msg)
                if (t != null) append("\n").append(Log.getStackTraceString(t))
                append("\n")
            }
            f.appendText(line)
        } catch (_: Throwable) {
        }
    }
}
