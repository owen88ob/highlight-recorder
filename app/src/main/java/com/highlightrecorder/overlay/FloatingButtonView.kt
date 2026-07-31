package com.highlightrecorder.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 悬浮主按钮:深色半透明圆底 + 中心"存"字 + 右上角呼吸红点(录制中)。
 */
class FloatingButtonView(context: Context) : FrameLayout(context) {

    private val dot: View
    private var breathing: ValueAnimator? = null

    init {
        val size = dp(56)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC1B1B2F.toInt())
            setStroke(dp(1.5f), Color.WHITE)
        }
        background = bg

        val label = TextView(context).apply {
            text = "存"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        addView(label, LayoutParams(size, size))

        dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE53935.toInt())
            }
            visibility = GONE
        }
        val dotSize = dp(12)
        val dotLp = LayoutParams(dotSize, dotSize, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4)
            marginEnd = dp(4)
        }
        addView(dot, dotLp)

        layoutParams = ViewGroup.LayoutParams(size, size)
    }

    fun setRecording(recording: Boolean) {
        if (recording) {
            dot.visibility = VISIBLE
            if (breathing == null) {
                breathing = ValueAnimator.ofFloat(1f, 0.25f).apply {
                    duration = 900
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { dot.alpha = it.animatedValue as Float }
                    start()
                }
            }
        } else {
            breathing?.cancel()
            breathing = null
            dot.alpha = 1f
            dot.visibility = GONE
        }
    }

    private fun dp(v: Float): Int =
        (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics) + 0.5f).toInt()

    private fun dp(v: Int): Int = dp(v.toFloat())
}
