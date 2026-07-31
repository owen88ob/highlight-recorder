package com.highlightrecorder.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import com.highlightrecorder.data.SettingsHolder
import com.highlightrecorder.service.RecordingService
import kotlin.math.abs

/**
 * 悬浮窗管理:显示/隐藏、拖动、松手贴边(可选半隐藏)、透明度、
 * 单击=保存回放,长按=开始/停止录制(未录制时拉起 App 走授权流程)。
 * 生命周期绑定 RecordingService:服务运行时显示,销毁时移除。
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
        private const val PREFS = "overlay"
        private const val KEY_X = "pos_x"
        private const val KEY_Y = "pos_y"
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private var button: FloatingButtonView? = null
    private var lp: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = button != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (button != null) return
        if (!canDraw()) {
            Log.w(TAG, "no overlay permission")
            return
        }
        val settings = SettingsHolder.current
        val view = FloatingButtonView(context)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(16))
            y = prefs.getInt(KEY_Y, dp(200))
        }

        view.alpha = settings.overlayAlpha
        view.scaleX = settings.overlayScale
        view.scaleY = settings.overlayScale
        view.setRecording(RecordingService.state.value == RecordingService.State.Recording)
        attachTouch(view, params)

        wm.addView(view, params)
        button = view
        lp = params
        Log.i(TAG, "overlay shown")
    }

    fun setRecordingState(recording: Boolean) {
        button?.setRecording(recording)
    }

    fun hide() {
        button?.let {
            try {
                wm.removeView(it)
            } catch (t: Throwable) {
                Log.w(TAG, "remove overlay", t)
            }
        }
        button = null
        lp = null
        Log.i(TAG, "overlay hidden")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(view: FloatingButtonView, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressed = false

        val longPressRunnable = Runnable {
            longPressed = true
            onLongPress()
        }

        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    handler.postDelayed(longPressRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        when {
                            dragging -> snapToEdge(view, params)
                            !longPressed -> onClick()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 松手后吸附到最近的左右边;开启贴边隐藏时半个按钮探出屏外。 */
    private fun snapToEdge(view: FloatingButtonView, params: WindowManager.LayoutParams) {
        val screenW = context.resources.displayMetrics.widthPixels
        val center = params.x + view.width / 2
        val edgeHide = SettingsHolder.current.overlayEdgeHide
        val target = if (center < screenW / 2) {
            if (edgeHide) -view.width / 2 else 0
        } else {
            if (edgeHide) screenW - view.width / 2 else screenW - view.width
        }
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 180
            addUpdateListener {
                params.x = it.animatedValue as Int
                try {
                    wm.updateViewLayout(view, params)
                } catch (_: Throwable) {
                }
            }
            start()
        }
        prefs.edit().putInt(KEY_X, target).putInt(KEY_Y, params.y).apply()
    }

    private fun onClick() {
        if (RecordingService.state.value == RecordingService.State.Recording) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_SAVE),
            )
        } else {
            Toast.makeText(context, "未在录制,长按开始", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onLongPress() {
        if (RecordingService.state.value == RecordingService.State.Recording) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            )
            Toast.makeText(context, "已停止循环录制", Toast.LENGTH_SHORT).show()
        } else {
            // 开始录制需要 MediaProjection 同意,只能由 Activity 发起:拉起主界面走引导
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                Toast.makeText(context, "请在 App 内确认开始录制", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
