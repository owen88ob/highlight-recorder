package com.highlightrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.highlightrecorder.buffer.ClipWriter
import com.highlightrecorder.capture.CapturePipeline
import com.highlightrecorder.data.AudioSource
import com.highlightrecorder.data.SettingsHolder
import com.highlightrecorder.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台录制服务:持有 MediaProjection 与采集管线。
 *
 * 启动方式:Activity 先走 MediaProjection 同意流程,把 resultCode/data
 * 以 [ACTION_START] 传入;Android 14+ 要求先 startForeground 再取投影。
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.highlightrecorder.action.START"
        const val ACTION_STOP = "com.highlightrecorder.action.STOP"
        const val ACTION_SAVE = "com.highlightrecorder.action.SAVE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001

        val state = MutableStateFlow<State>(State.Idle)
        val bufferedSeconds = MutableStateFlow(0)
        val savedEvents = MutableSharedFlow<SavedEvent>(extraBufferCapacity = 4)

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    sealed class State {
        data object Idle : State()
        data object Recording : State()
        data object Saving : State()
        data class Error(val message: String) : State()
    }

    /** 最近一次保存结果(UI/悬浮窗用于弹提示)。 */
    data class SavedEvent(val uri: String, val durationMs: Long)

    private var pipeline: CapturePipeline? = null
    private var projection: MediaProjection? = null
    private var ticker: Job? = null
    private var overlay: OverlayManager? = null

    /** 降级状态:低电量/低内存时码率减半,恢复时还原。 */
    private var degraded = false

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (pipeline == null) return
            when (intent.action) {
                Intent.ACTION_BATTERY_LOW -> degrade("电量低,已降低录制码率")
                Intent.ACTION_BATTERY_OKAY -> restore()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (pipeline == null) return
        when {
            level >= TRIM_MEMORY_RUNNING_CRITICAL -> {
                updateNotification("内存不足,已暂停录制")
                stopRecording()
            }
            level >= TRIM_MEMORY_RUNNING_LOW -> degrade("内存不足,已降低录制码率")
        }
    }

    private fun degrade(reason: String) {
        if (degraded) return
        degraded = true
        val halved = SettingsHolder.current.videoBitrateBps / 2
        pipeline?.adjustBitrate(halved)
        pipeline?.requestKeyFrame()
        updateNotification(reason)
        Log.i(TAG, "degraded: $reason")
    }

    private fun restore() {
        if (!degraded) return
        degraded = false
        pipeline?.adjustBitrate(SettingsHolder.current.videoBitrateBps)
        pipeline?.requestKeyFrame()
        updateNotification("高光回录运行中")
        Log.i(TAG, "restored from degraded")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data == null) {
                    fail("缺少 MediaProjection 授权数据")
                } else {
                    startRecording(resultCode, data)
                }
            }
            ACTION_STOP -> stopRecording()
            ACTION_SAVE -> saveClip()
            else -> Log.w(TAG, "unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (pipeline != null) {
            Log.w(TAG, "already recording")
            return
        }
        val settings = SettingsHolder.current
        try {
            startForegroundWithType(settings.audioSource == AudioSource.MIC)

            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, data)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "projection stopped by system")
                    stopRecording()
                }
            }, null)
            projection = proj

            val p = CapturePipeline(this, settings, proj)
            p.listener = object : CapturePipeline.Listener {
                override fun onError(t: Throwable) {
                    Log.e(TAG, "pipeline error", t)
                    fail("编码出错: ${t.message}")
                }

                override fun onVideoFormat(format: MediaFormat) = Unit
            }
            p.start()
            pipeline = p
            state.value = State.Recording
            startTicker()
            showOverlay()
            degraded = false
            runCatching {
                registerReceiver(batteryReceiver, android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_LOW)
                    addAction(Intent.ACTION_BATTERY_OKAY)
                })
            }
            scope.launch {
                savedEvents.collect { ev ->
                    android.widget.Toast.makeText(
                        this@RecordingService,
                        "已保存 %.1f 秒高光".format(ev.durationMs / 1000f),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            Log.i(TAG, "recording started, rewind=${settings.rewindSeconds}s")
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            cleanup()
            fail("启动失败: ${t.message}")
        }
    }

    private fun stopRecording() {
        cleanup()
        state.value = State.Idle
        bufferedSeconds.value = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "recording stopped")
    }

    /** 保存最近 N 秒。快照只拷引用,编码与录制全程不中断。 */
    private fun saveClip() {
        val p = pipeline ?: run {
            Log.w(TAG, "save ignored: not recording")
            return
        }
        val settings = SettingsHolder.current
        val videoFormat = p.videoFormat ?: run {
            Log.w(TAG, "save ignored: no video format yet")
            return
        }
        if (state.value == State.Saving) return
        state.value = State.Saving

        val (video, audio) = p.snapshot(settings.rewindSeconds * 1_000_000L)
        val audioFormat = p.audioFormat

        scope.launch(Dispatchers.IO) {
            try {
                if (video.isEmpty()) {
                    Log.w(TAG, "buffer empty, nothing to save")
                    return@launch
                }
                val result = ClipWriter.write(
                    this@RecordingService, video, audio, videoFormat,
                    if (audio.isEmpty()) null else audioFormat,
                )
                savedEvents.tryEmit(SavedEvent(result.uri.toString(), result.durationMs))
                updateNotification("已保存 %.1f 秒高光".format(result.durationMs / 1000f))
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
                updateNotification("保存失败: ${t.message}")
            } finally {
                if (pipeline != null) state.value = State.Recording
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                bufferedSeconds.value = ((pipeline?.videoBuffer?.bufferedDurationUs ?: 0L) / 1_000_000L).toInt()
                delay(500)
            }
        }
    }

    private fun cleanup() {
        ticker?.cancel()
        ticker = null
        overlay?.hide()
        overlay = null
        runCatching { unregisterReceiver(batteryReceiver) }
        degraded = false
        try {
            pipeline?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "pipeline stop", t)
        }
        pipeline = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
    }

    private fun fail(message: String) {
        state.value = State.Error(message)
        updateNotification(message)
    }

    // ---- 悬浮窗 ----

    private fun showOverlay() {
        if (overlay == null) overlay = OverlayManager(this)
        overlay?.show()
        overlay?.setRecordingState(true)
    }

    // ---- 前台服务与通知 ----

    private fun startForegroundWithType(withMic: Boolean) {
        createChannel()
        val notification = buildNotification("高光回录运行中")
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (withMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "高光回录", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val savePi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_SAVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPi = PendingIntent.getActivity(
            this, 3,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("高光回录")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "保存回放", savePi).build())
            .addAction(Notification.Action.Builder(null, "停止", stopPi).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        super.onDestroy()
    }
}
