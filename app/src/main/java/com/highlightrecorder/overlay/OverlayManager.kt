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
        /** 位置同时按屏幕比例存一份,旋转后按比例还原,避免绝对像素坐标出屏/跑偏。 */
        private const val KEY_XF = "pos_x_frac"
        private const val KEY_YF = "pos_y_frac"
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
        if (settings.overlayHidden) {
            Log.i(TAG, "overlay hidden by settings")
            return
        }
        val view = FloatingButtonView(context)

        // 优先按比例还原位置(旋转后依然贴着原来的边),老版本绝对坐标兜底并钳制
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val maxX = (screenW - dp(56)).coerceAtLeast(0)
        val maxY = (screenH - dp(56)).coerceAtLeast(0)
        val clampedX: Int
        val clampedY: Int
        if (prefs.contains(KEY_XF)) {
            clampedX = (prefs.getFloat(KEY_XF, 0f) * maxX).toInt().coerceIn(0, maxX)
            clampedY = (prefs.getFloat(KEY_YF, 0.3f) * maxY).toInt().coerceIn(0, maxY)
        } else {
            clampedX = prefs.getInt(KEY_X, dp(16)).coerceIn(0, maxX)
            clampedY = prefs.getInt(KEY_Y, dp(200)).coerceIn(0, maxY)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampedX
            y = clampedY
        }

        view.alpha = settings.overlayAlpha
        view.scaleX = settings.overlayScale
        view.scaleY = settings.overlayScale
        view.setRecording(RecordingService.state.value == RecordingService.State.Recording)
        attachTouch(view, params)

        wm.addView(view, params)
        button = view
        lp = params
        com.highlightrecorder.data.FileLogger.log(TAG, "overlay shown at ($clampedX,$clampedY) screen ${screenW}x$screenH")
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
        com.highlightrecorder.data.FileLogger.log(TAG, "overlay hidden")
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
                        // 拖动时钳制在屏幕内,防止拖出边界后找不到悬浮球
                        val sw = context.resources.displayMetrics.widthPixels
                        val sh = context.resources.displayMetrics.heightPixels
                        params.x = (startX + dx.toInt()).coerceIn(0, (sw - view.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (sh - view.height).coerceAtLeast(0))
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
        val screenH = context.resources.displayMetrics.heightPixels
        // y 也钳制回屏幕内(拖动已限制,这里是旋转等边界情况的兜底)
        params.y = params.y.coerceIn(0, (screenH - view.height).coerceAtLeast(0))
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
        prefs.edit()
            .putInt(KEY_X, target).putInt(KEY_Y, params.y)
            .putFloat(KEY_XF, if (screenW - view.width > 0) target.toFloat() / (screenW - view.width) else 0f)
            .putFloat(KEY_YF, if (screenH - view.height > 0) params.y.toFloat() / (screenH - view.height) else 0f)
            .apply()
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
